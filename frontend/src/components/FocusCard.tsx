import type { Boat } from '../types';
import { isStale } from '../lib/boat';
import { formatCoords, formatDuration, routeLabel, triggerLabel } from '../lib/format';

interface Props {
  boat: Boat | null;
  now: number;
  /** why this boat is shown: picked on the map/list, newest SOS, or the first boat */
  reason: 'selected' | 'sos' | 'first';
  onShow: (id: string) => void;
}

const REASON: Record<Props['reason'], string> = {
  selected: 'Selected boat',
  sos: 'SOS boat',
  first: 'Boat',
};

/** Boarding-pass style card for one boat: the boat, the route its last packet took, and the gateway. */
export function FocusCard({ boat, now, reason, onShow }: Props) {
  if (!boat) {
    return (
      <section className="card ticket ticket--empty" aria-label="Selected boat">
        <span className="eyebrow">Selected boat</span>
        <p className="empty">Waiting for the first boat to report...</p>
      </section>
    );
  }

  const stale = isStale(boat, now);
  const sos = boat.status === 'sos';
  const ownUpload = boat.gateway === boat.id;
  const look = sos ? 'sos' : stale ? 'stale' : 'live';

  return (
    <section className={`card ticket ticket--${look}`} aria-label={`${REASON[reason]}: Boat ${boat.id}`}>
      <div className="ticket-top">
        <span className="eyebrow">{REASON[reason]}</span>
        <span className="badges">
          {sos && <span className="badge badge--sos">SOS</span>}
          {stale ? <span className="badge badge--stale">LAST KNOWN</span> : !sos && <span className="badge badge--live">LIVE</span>}
        </span>
      </div>

      <div className="ticket-route">
        <div>
          <span className="ticket-label">Boat</span>
          <strong className="ticket-code">{boat.id}</strong>
        </div>
        <div className="ticket-path" aria-hidden="true">
          <span className="ticket-line" />
          <svg viewBox="0 0 24 24" width="26" height="26">
            <path d="M3 15h18l-2.5 4h-13L3 15Zm4-1V8l5-3v9m1 0V9l5 5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
          </svg>
          <span className="ticket-line" />
        </div>
        <div className="ticket-end">
          <span className="ticket-label">{ownUpload ? 'Own phone' : 'Gateway'}</span>
          <strong className="ticket-code">{boat.gateway || '-'}</strong>
        </div>
      </div>
      <p className="ticket-via">{routeLabel(boat)}</p>
      {sos && <p className="ticket-sos">{triggerLabel(boat.trigger)} · {boat.acked ? 'Delivered to boat ✓' : 'Not yet delivered to boat'}</p>}

      <div className="ticket-stub">
        <div>
          <span className="ticket-label">Position</span>
          <span className="mono">{formatCoords(boat.lat, boat.lon)}</span>
        </div>
        <div>
          <span className="ticket-label">Last seen</span>
          <span>{formatDuration(now - boat.lastSeen)} ago</span>
        </div>
        <div className="ticket-end">
          <span className="ticket-label">RSSI</span>
          <span className="mono">{Number.isFinite(boat.rssi) && boat.rssi !== 0 ? `${boat.rssi} dBm` : '-'}</span>
        </div>
      </div>
      <button type="button" className="btn btn--small ticket-btn" onClick={() => onShow(boat.id)}>Show on map</button>
    </section>
  );
}
