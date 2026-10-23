import type { ReactNode } from 'react';
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
  /** shown between the brand and the status pills (the boat search) */
  children?: ReactNode;
}

export function StatusBar(p: Props) {
  const soundLabel = p.soundOn
    ? `Sound on${p.notifications === 'granted' ? ' · notifications on' : p.notifications === 'denied' ? ' · notifications blocked' : ''}`
    : 'Enable sound & notifications';

  return (
    <header className="statusbar">
      <div className="brand">
        <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
          <path d="M4 13c3-3.4 6.3-3.4 9.3 0s6.3 3.4 9.3 0 4.4-2.3 5.4-2" fill="none" stroke="#49DC7A" strokeWidth="2.6" strokeLinecap="round" />
          <path d="M4 21c3-3.4 6.3-3.4 9.3 0s6.3 3.4 9.3 0 4.4-2.3 5.4-2" fill="none" stroke="#22F2EF" strokeWidth="2.6" strokeLinecap="round" />
        </svg>
        <h1 className="brand-name">SeaMesh</h1>
        <span className="brand-sub">Shore Dashboard</span>
      </div>

      {p.children}

      <div className="stats">
        {p.demo && (
          <span className="demo">
            <span className="demo-badge">DEMO · simulated data</span>
            <button type="button" className="btn btn--small" onClick={p.onRestartDemo}>Restart demo</button>
          </span>
        )}
        <span className={`conn conn--${p.conn}`} role="status">
          <span className="conn-dot" aria-hidden="true" />
          {CONN_LABEL[p.conn]}
        </span>
        <span className="stat">Boats <strong>{p.boatCount}</strong></span>
        <span className={`stat${p.sosCount ? ' stat--sos' : ''}`}>Active SOS <strong>{p.sosCount}</strong></span>
        <button
          type="button"
          className={`icon-btn${p.soundOn ? ' icon-btn--on' : ' icon-btn--attention'}`}
          onClick={p.soundOn ? undefined : p.onEnableAlerts}
          aria-disabled={p.soundOn}
          title={soundLabel}
          aria-label={soundLabel}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15L6 16Z M10 20a2 2 0 0 0 4 0" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
          </svg>
          {!p.soundOn && <span className="icon-btn-dot" aria-hidden="true" />}
        </button>
        <time className="clock mono" dateTime={new Date(p.now).toISOString()}>{formatClock(p.now)}</time>
      </div>
    </header>
  );
}
