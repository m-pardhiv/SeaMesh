import type { Boat } from '../types';
import { isStale } from '../lib/boat';

interface Props {
  boats: Boat[];
  now: number;
  soundOn: boolean;
  notifications: NotificationPermission | 'unsupported';
  onEnableAlerts: () => void;
}

/** Fleet at a glance: who is reporting, who went silent, and how much is reaching shore through the mesh. */
export function FleetCard({ boats, now, soundOn, notifications, onEnableAlerts }: Props) {
  const live = boats.filter((b) => !isStale(b, now));
  const silent = boats.length - live.length;
  const sos = boats.filter((b) => b.status === 'sos').length;
  // A live boat uploaded by another boat's phone reached shore over LoRa
  const viaMesh = live.filter((b) => b.gateway && b.gateway !== b.id).length;
  const gateways = new Set(live.map((b) => b.gateway).filter(Boolean)).size;

  return (
    <section className="card fleet-card" aria-labelledby="fleet-title">
      <h2 id="fleet-title" className="sr-only">Fleet summary</h2>
      <div className="fleet-pair">
        <div>
          <span className="eyebrow">Live</span>
          <strong className="fleet-big">{live.length}</strong>
          <span className="fleet-sub">boats reporting</span>
        </div>
        <svg className="fleet-swap" viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
          <path d="M4 8h14l-3-3M20 16H6l3 3" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <div className="fleet-right">
          <span className="eyebrow">Last known</span>
          <strong className={`fleet-big${silent ? ' fleet-big--warn' : ''}`}>{silent}</strong>
          <span className="fleet-sub">silent &gt; 60 s</span>
        </div>
      </div>

      <div className="fleet-row">
        <div>
          <span className="eyebrow">Active SOS</span>
          <span className={`fleet-mid${sos ? ' fleet-mid--sos' : ''}`}>{sos}</span>
        </div>
        <div className="fleet-right">
          <span className="eyebrow">Through the mesh</span>
          <span className="fleet-mid">
            {viaMesh} <small>via LoRa · {gateways} gateway{gateways === 1 ? '' : 's'}</small>
          </span>
        </div>
      </div>

      {soundOn ? (
        <p className="pill-btn pill-btn--done" role="status">
          Sound on{notifications === 'granted' ? ' · notifications on' : notifications === 'denied' ? ' · notifications blocked' : ''}
        </p>
      ) : (
        <button type="button" className="pill-btn" onClick={onEnableAlerts}>Enable sound &amp; notifications</button>
      )}
    </section>
  );
}
