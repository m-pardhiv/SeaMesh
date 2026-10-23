import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ACK_CLEAR_MS, API_URL, DEMO } from './config';
import { useLiveData } from './data/useLiveData';
import { useNow } from './lib/useNow';
import { sortBoats, sosKey } from './lib/boat';
import { Alarm } from './lib/alarm';
import { formatCoords, harbourLabel, triggerLabel } from './lib/format';
import { StatusBar } from './components/StatusBar';
import { SosBanner } from './components/SosBanner';
import { BoatMap, type FocusRequest } from './components/BoatMap';
import { BoatList } from './components/BoatList';
import { EventLog } from './components/EventLog';

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
      const summary = `Boat ${b.id} at ${formatCoords(b.lat, b.lon)}, ${triggerLabel(b.trigger)}, ${harbourLabel(b.lat, b.lon)}`;
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
  const mapRef = useRef<HTMLDivElement>(null);
  const showBoat = useCallback((id: string) => {
    setFocus((f) => ({ id, nonce: (f?.nonce ?? 0) + 1 }));
    // On phones the map is above the panels; bring it into view.
    if (window.matchMedia('(max-width: 899px)').matches) {
      const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      mapRef.current?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
    }
  }, []);

  return (
    <div className={`app${sosBoats.length ? ' app--sos' : ''}`}>
      <div className="top">
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
        {error && <p className="config-error" role="alert">{error}</p>}
        {clearError && <p className="config-error" role="alert">{clearError}</p>}
      </div>

      <main className="main">
        <div className="map-col" ref={mapRef}>
          <BoatMap boats={boats} now={now} focus={focus} />
        </div>
        <aside className="side">
          <BoatList boats={boats} now={now} onShow={showBoat} />
          <EventLog events={events} />
        </aside>
      </main>

      <p className="sr-only" aria-live="assertive">{announcement}</p>
    </div>
  );
}
