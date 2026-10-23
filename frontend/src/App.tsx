import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { DEMO } from './config';
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

  const boats = useMemo(() => sortBoats(Object.values(boatMap), now), [boatMap, now]);
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

  const acknowledge = useCallback(() => {
    // Local only: silences the alarm for the SOS incidents currently shown.
    setSilenced((prev) => new Set([...prev, ...sosKeysJoined.split('|').filter(Boolean)]));
  }, [sosKeysJoined]);

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
          onAcknowledge={acknowledge}
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
