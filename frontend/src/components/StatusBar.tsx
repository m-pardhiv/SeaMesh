import type { ConnState } from '../types';
import { formatClock } from '../lib/format';

const CONN_LABEL: Record<ConnState, string> = {
  connecting: 'Connecting...',
  live: 'Live',
  reconnecting: 'Reconnecting...',
  offline: 'Offline',
};

interface Props {
  conn: ConnState;
  boatCount: number;
  sosCount: number;
  now: number;
  demo: boolean;
  onRestartDemo?: () => void;
  soundOn: boolean;
  notifications: NotificationPermission | 'unsupported';
  onEnableAlerts: () => void;
}

export function StatusBar(p: Props) {
  return (
    <header className="statusbar">
      <div className="brand">
        <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
          <path d="M4 13c3-3.4 6.3-3.4 9.3 0s6.3 3.4 9.3 0 4.4-2.3 5.4-2" fill="none" stroke="#B5D334" strokeWidth="2.6" strokeLinecap="round" />
          <path d="M4 21c3-3.4 6.3-3.4 9.3 0s6.3 3.4 9.3 0 4.4-2.3 5.4-2" fill="none" stroke="#E6C229" strokeWidth="2.6" strokeLinecap="round" />
        </svg>
        <span className="brand-name">SeaMesh</span>
        <span className="brand-sub">Shore Dashboard</span>
      </div>

      {p.demo && (
        <div className="demo">
          <span className="demo-badge">DEMO · simulated data</span>
          <button type="button" className="btn btn--small" onClick={p.onRestartDemo}>Restart demo</button>
        </div>
      )}

      <div className="stats">
        <span className={`conn conn--${p.conn}`} role="status">
          <span className="conn-dot" aria-hidden="true" />
          {CONN_LABEL[p.conn]}
        </span>
        <span className="stat">Boats <strong>{p.boatCount}</strong></span>
        <span className={`stat${p.sosCount ? ' stat--sos' : ''}`}>Active SOS <strong>{p.sosCount}</strong></span>
        {p.soundOn ? (
          <span className="stat stat--ok">
            Sound on{p.notifications === 'granted' ? ' · notifications on' : p.notifications === 'denied' ? ' · notifications blocked' : ''}
          </span>
        ) : (
          <button type="button" className="btn btn--accent" onClick={p.onEnableAlerts}>Enable sound &amp; notifications</button>
        )}
        <time className="clock mono" dateTime={new Date(p.now).toISOString()}>{formatClock(p.now)}</time>
      </div>
    </header>
  );
}
