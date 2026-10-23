import type { Boat } from '../types';
import { isStale } from '../lib/boat';
import { formatCoords, formatDuration, triggerLabel } from '../lib/format';

interface Props {
  sosBoats: Boat[]; // newest first
  now: number;
  alarmActive: boolean;
  soundOn: boolean;
  /** seconds until the acknowledged SOS is cleared, or null when not counting down */
  clearingIn: number | null;
  onAcknowledge: () => void;
  onKeepAlert: () => void;
  onEnableAlerts: () => void;
  onShow: (id: string) => void;
}

export function SosBanner({ sosBoats, now, alarmActive, soundOn, clearingIn, onAcknowledge, onKeepAlert, onEnableAlerts, onShow }: Props) {
  if (sosBoats.length === 0) return null;

  return (
    <section className="sos-banner" aria-label="Active SOS alerts">
      <ul className="sos-lines">
        {sosBoats.map((b) => {
          const since = b.sosSince ?? b.lastSeen;
          const stale = isStale(b, now);
          return (
            <li key={b.id} className="sos-line">
              <p className="sos-text">
                SOS - Boat {b.id} - <span className="mono">{formatCoords(b.lat, b.lon)}</span> - {triggerLabel(b.trigger)} -{' '}
                {formatDuration(now - since)} ago
              </p>
              <div className="sos-meta">
                {b.acked
                  ? <span className="sos-chip sos-chip--ok">Delivered to boat ✓</span>
                  : <span className="sos-chip">Not yet delivered to boat</span>}
                {stale && <span className="sos-chip sos-chip--stale">LAST KNOWN - no contact for {formatDuration(now - b.lastSeen)}</span>}
                <button type="button" className="btn btn--banner" onClick={() => onShow(b.id)}>Show on map</button>
              </div>
            </li>
          );
        })}
      </ul>
      <div className="sos-controls">
        {clearingIn !== null ? (
          <>
            <span className="sos-silenced" aria-live="polite">Acknowledged · clearing in {clearingIn} s</span>
            <button type="button" className="btn btn--banner" onClick={onKeepAlert}>Keep alert</button>
          </>
        ) : (
          <button type="button" className="btn btn--ack" onClick={onAcknowledge}>
            {alarmActive ? 'Acknowledge' : 'Acknowledge & clear'}
          </button>
        )}
        {!soundOn && (
          <button type="button" className="btn btn--banner" onClick={onEnableAlerts}>Sound is off · Enable sound &amp; notifications</button>
        )}
      </div>
    </section>
  );
}
