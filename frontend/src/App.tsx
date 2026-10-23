import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ACK_CLEAR_MS, API_URL, DEMO } from './config';
import { useLiveData } from './data/useLiveData';
import { useNow } from './lib/useNow';
import { isStale, sortBoats, sosKey } from './lib/boat';
import { Alarm } from './lib/alarm';
import { formatCoords, triggerLabel } from './lib/format';
import { StatusBar } from './components/StatusBar';
import { SosBanner } from './components/SosBanner';
import { BoatMap, type FocusRequest } from './components/BoatMap';
import { BoatList } from './components/BoatList';
import { EventLog } from './components/EventLog';
import { FleetCard } from './components/FleetCard';
import { FocusCard } from './components/FocusCard';

const notificationsSupported = typeof window !== 'undefined' && 'Notification' in window;
const currentPermission = (): NotificationPermission | 'unsupported' =>
  notificationsSupported ? Notification.permission : 'unsupported';

export default function App() {
  const { boats: boatMap, events, conn, clockOffset, error, restartDemo } = useLiveData();
  const now = useNow(clockOffset);

  // SOS incidents the operator acknowledged and cleared. Shown as normal right away,
  // before the server's update arrives (and in demo mode, where there is no server).
  const [cleared, setCleared] = useState<Set<string>>(() => new Set());
  const boats = useMemo(() => sortBoats(
    Object.values(boatMap).map((b) => (b.status === 'sos' && cleared.has(sosKey(b)) ? { ...b, status: 'normal' as const } : b)),
    now,
  ), [boatMap, now, cleared]);
  const sosBoats = useMemo(() => boats.filter((b) => b.status === 'sos'), [boats]); // already newest first
  const sosKeys = sosBoats.map(sosKey);
  const sosKeysJoined = sosKeys.join('|');

  // ----- Alarm & notifications -----
  const alarm = useRef<Alarm | null>(null);
  alarm.current ??= new Alarm();
  const [soundOn, setSoundOn] = useState(false);
  const [notifPerm, setNotifPerm] = useState(currentPermission);
  const [silenced, setSilenced] = useState<Set<string>>(() => new Set());
  const alarmActive = sosKeys.some((k) => !silenced.has(k));

  const enableAlerts = useCallback(async () => {
    // Kick both off inside the click so the browser counts it as a user gesture.
    const audio = alarm.current!.enable();
    const perm = notificationsSupported && Notification.permission === 'default'
      ? Notification.requestPermission().catch(() => Notification.permission)
      : Promise.resolve(currentPermission());
    setSoundOn(await audio);
    setNotifPerm(await perm);
  }, []);

  // Acknowledge silences the alarm now and clears the SOS incidents currently shown
  // ACK_CLEAR_MS later, unless the operator presses "Keep alert" first.
  const [clearing, setClearing] = useState<{ targets: { id: string; key: string }[]; at: number } | null>(null);
  const [clearError, setClearError] = useState<string | null>(null);

  const acknowledge = useCallback(() => {
    setSilenced((prev) => new Set([...prev, ...sosKeysJoined.split('|').filter(Boolean)]));
    setClearing({ targets: sosBoats.map((b) => ({ id: b.id, key: sosKey(b) })), at: Date.now() + ACK_CLEAR_MS });
    setClearError(null);
  }, [sosKeysJoined, sosBoats]);

  const keepAlert = useCallback(() => setClearing(null), []);

  useEffect(() => {
    if (!clearing || Date.now() < clearing.at) return;
    const { targets } = clearing;
    setClearing(null);
    setCleared((prev) => new Set([...prev, ...targets.map((t) => t.key)]));
    if (DEMO) return;
    Promise.all(targets.map(async (t) => {
      const res = await fetch(`${API_URL}/api/boats/${encodeURIComponent(t.id)}/resolve`, { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
    })).catch((err) => setClearError(`Could not clear the SOS on the server (${err.message}); other dashboards still show it.`));
    // `now` ticks every second, which is what drives the countdown check
  }, [clearing, now]);

  const clearingIn = clearing ? Math.max(0, Math.ceil((clearing.at - Date.now()) / 1000)) : null;

  useEffect(() => {
    if (soundOn && alarmActive) alarm.current!.start();
    else alarm.current!.stop();
  }, [soundOn, alarmActive]);

  useEffect(() => () => alarm.current?.stop(), []);

  // One browser notification and one screen-reader announcement per new SOS incident.
  const notified = useRef(new Set<string>());
  const announced = useRef(new Set<string>());
  const [announcement, setAnnouncement] = useState('');
  useEffect(() => {
    for (const b of sosBoats) {
      const key = sosKey(b);
      const summary = `Boat ${b.id} at ${formatCoords(b.lat, b.lon)}, ${triggerLabel(b.trigger)}`;
      if (!announced.current.has(key)) {
        announced.current.add(key);
        setAnnouncement(`SOS: ${summary}`);
      }
      if (notifPerm === 'granted' && !notified.current.has(key)) {
        notified.current.add(key);
        try {
          new Notification(`SOS - Boat ${b.id}`, { body: summary, tag: key, requireInteraction: true });
        } catch {
          // Some mobile browsers only allow notifications from a service worker.
        }
      }
    }
    // Only needs to re-run when the set of SOS incidents or the permission changes.
  }, [sosKeysJoined, notifPerm]);

  useEffect(() => {
    document.title = sosBoats.length ? `SOS (${sosBoats.length}) · SeaMesh Shore Dashboard` : 'SeaMesh Shore Dashboard';
  }, [sosBoats.length]);

  // ----- Map focus -----
  const [focus, setFocus] = useState<FocusRequest | null>(null);
  const mapRef = useRef<HTMLElement>(null);
  const showBoat = useCallback((id: string) => {
    setFocus((f) => ({ id, nonce: (f?.nonce ?? 0) + 1 }));
    // On phones the map is above the panels; bring it into view.
    if (window.matchMedia('(max-width: 899px)').matches) {
      const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      mapRef.current?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
    }
  }, []);

  // ----- Search: filters the boat list by ID; the map keeps showing every boat -----
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLElement && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);
      if (e.key === '/' && !typing) {
        e.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const q = query.trim().toUpperCase();
  const listed = useMemo(() => (q ? boats.filter((b) => b.id.toUpperCase().includes(q)) : boats), [boats, q]);

  // ----- Ticket card: the boat picked on the map or list, else the newest SOS, else the first boat -----
  const picked = focus ? boats.find((b) => b.id === focus.id) : undefined;
  const focusBoat = picked ?? sosBoats[0] ?? boats[0] ?? null;
  const focusReason = picked ? 'selected' : sosBoats[0] ? 'sos' : 'first';

  const liveCount = boats.filter((b) => !isStale(b, now)).length;

  return (
    <div className={`app${sosBoats.length ? ' app--sos' : ''}`}>
      {/* Header and SOS banner stay pinned while the dashboard scrolls */}
      <div className="top">
        <StatusBar
          conn={conn}
          boatCount={boats.length}
          sosCount={sosBoats.length}
          now={now}
          demo={DEMO}
          onRestartDemo={restartDemo}
          soundOn={soundOn}
          notifications={notifPerm}
          onEnableAlerts={enableAlerts}
        />
        <SosBanner
          sosBoats={sosBoats}
          now={now}
          alarmActive={alarmActive}
          soundOn={soundOn}
          clearingIn={clearingIn}
          onAcknowledge={acknowledge}
          onKeepAlert={keepAlert}
          onEnableAlerts={enableAlerts}
          onShow={showBoat}
        />
        {error && <p className="config-error" role="alert">{error}</p>}
        {clearError && <p className="config-error" role="alert">{clearError}</p>}
      </div>

      <div className="page-head">
        <h1>Dashboard</h1>
        <label className="search">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <circle cx="11" cy="11" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
            <path d="m16 16 4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
          <span className="sr-only">Search boats by ID</span>
          <input
            ref={searchRef}
            type="search"
            placeholder="Search boats by ID"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape') setQuery(''); }}
          />
          <kbd aria-hidden="true">/</kbd>
        </label>
      </div>

      <main className="layout">
        <div className="area-fleet">
          <FleetCard boats={boats} now={now} soundOn={soundOn} notifications={notifPerm} onEnableAlerts={enableAlerts} />
        </div>
        <div className="area-focus">
          <FocusCard boat={focusBoat} now={now} reason={focusReason} onShow={showBoat} />
        </div>

        <section className="card map-card area-map" id="map-card" aria-label="Map" ref={mapRef}>
          <BoatMap boats={boats} now={now} focus={focus} />
          <div className="map-chip map-chip--title" aria-hidden="true">
            <span className={`conn-dot conn-dot--${conn}`} /> Live map · {boats.length} boat{boats.length === 1 ? '' : 's'}
          </div>
          <ul className="map-legend" aria-label="Map legend">
            <li><span className="key key--live" /> Live</li>
            <li><span className="key key--sos" /> SOS</li>
            <li><span className="key key--stale" /> Last known</li>
            <li><span className="key key--mesh" /> Mesh route</li>
          </ul>
          <div className="map-progress">
            <div>
              <span className="eyebrow">Boats reporting</span>
              <strong>{liveCount} <small>/ {boats.length}</small></strong>
            </div>
            <div className="ticks" aria-hidden="true">
              <span style={{ width: `${boats.length ? (liveCount / boats.length) * 100 : 0}%` }} />
            </div>
            <span className="map-chip map-chip--inline">{sosBoats.length ? `${sosBoats.length} SOS active` : 'All clear'}</span>
          </div>
        </section>

        <div className="area-boats">
          <BoatList boats={listed} now={now} onShow={showBoat} total={boats.length} query={query.trim()} />
        </div>
        <div className="area-events">
          <EventLog events={events} />
        </div>
      </main>

      <p className="sr-only" aria-live="assertive">{announcement}</p>
    </div>
  );
}
