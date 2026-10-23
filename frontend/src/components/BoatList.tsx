import { useState } from 'react';
import type { Boat } from '../types';
import { DRIFT_KMH, KOCHI_HARBOUR } from '../config';
import { isStale, searchRadiusKm } from '../lib/boat';
import { formatCoords, formatDuration, googleMapsUrl, harbourLabel, routeLabel, triggerLabel } from '../lib/format';

interface Props {
  boats: Boat[]; // already sorted: SOS, stale, normal
  now: number;
  onShow: (id: string) => void;
}

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // Fallback for browsers without the async clipboard API.
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  }
}

function CopyButton({ text }: { text: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const onClick = async () => {
    setState((await copyText(text)) ? 'copied' : 'failed');
    window.setTimeout(() => setState('idle'), 2000);
  };
  return (
    <button type="button" className="btn btn--small" onClick={onClick} aria-live="polite">
      {state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed' : 'Copy coordinates'}
    </button>
  );
}

export function BoatList({ boats, now, onShow }: Props) {
  return (
    <section className="panel" aria-labelledby="boats-title">
      <div className="panel-head">
        <h2 id="boats-title">Boats</h2>
        <span className="muted">{boats.length} tracked · SOS first</span>
      </div>
      {boats.length === 0 ? (
        <p className="empty">Waiting for the first boat to report...</p>
      ) : (
        <ul className="boat-list">
          {boats.map((b) => {
            const stale = isStale(b, now);
            const sos = b.status === 'sos';
            const coords = formatCoords(b.lat, b.lon);
            return (
              <li key={b.id} className={`boat-card${sos ? ' boat-card--sos' : ''}${stale ? ' boat-card--stale' : ''}`}>
                <button type="button" className="boat-card-head" onClick={() => onShow(b.id)} aria-label={`Show Boat ${b.id} on the map`}>
                  <span className="boat-card-id">Boat {b.id}</span>
                  <span className="badges">
                    {sos && <span className="badge badge--sos">SOS</span>}
                    {stale ? <span className="badge badge--stale">LAST KNOWN</span> : !sos && <span className="badge badge--live">LIVE</span>}
                  </span>
                  <span className="boat-card-show" aria-hidden="true">Show on map</span>
                </button>

                {stale && (
                  <p className="last-known">LAST KNOWN - no contact for {formatDuration(now - b.lastSeen)}</p>
                )}
                {stale && sos && (
                  <p className="estimate">
                    ESTIMATE: search area radius {searchRadiusKm(b, now).toFixed(1)} km around the last position
                    (assumes ~{DRIFT_KMH} km/h drift)
                  </p>
                )}
                {sos && (
                  <p className="sos-detail">
                    {triggerLabel(b.trigger)} · {b.acked ? 'Delivered to boat ✓' : 'Not yet delivered to boat'}
                  </p>
                )}

                <dl className="facts">
                  <div><dt>Position</dt><dd className="mono">{coords}</dd></div>
                  <div><dt>Last seen</dt><dd>{formatDuration(now - b.lastSeen)} ago</dd></div>
                  <div><dt>Route</dt><dd>{routeLabel(b)}</dd></div>
                  <div><dt>Gateway</dt><dd className="mono">{b.gateway || '-'}</dd></div>
                  {/* the server sends rssi 0 when there was no radio hop (own upload) */}
                  <div><dt>RSSI</dt><dd className="mono">{Number.isFinite(b.rssi) && b.rssi !== 0 ? `${b.rssi} dBm` : '-'}</dd></div>
                  <div><dt>From {KOCHI_HARBOUR.name}</dt><dd>{harbourLabel(b.lat, b.lon, false)}</dd></div>
                </dl>

                <div className="actions">
                  <CopyButton text={coords} />
                  <a className="btn btn--small" href={googleMapsUrl(b.lat, b.lon)} target="_blank" rel="noopener noreferrer">
                    Open in Google Maps
                  </a>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
