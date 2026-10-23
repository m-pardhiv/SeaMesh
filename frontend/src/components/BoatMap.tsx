import { useEffect, useMemo, useRef, type MutableRefObject } from 'react';
import L from 'leaflet';
import { Circle, MapContainer, Marker, Polyline, Popup, TileLayer, useMap } from 'react-leaflet';
import type { Boat } from '../types';
import { MAP_CENTER, MAP_ZOOM } from '../config';
import { boatLook, isStale, searchRadiusKm, type BoatLook } from '../lib/boat';
import { kmToLonDeg } from '../lib/geo';
import { formatCoords, formatDuration, googleMapsUrl, routeLabel, triggerLabel } from '../lib/format';

export interface FocusRequest {
  id: string;
  nonce: number;
  /** minimum zoom to fly to (default 12) */
  zoom?: number;
}

interface Props {
  boats: Boat[];
  now: number;
  focus: FocusRequest | null;
  /** an SOS is active: do not pull the view back to the whole fleet when a new boat appears */
  holdView: boolean;
  /** bump to fit the view to all boats again (e.g. after the last SOS is cleared) */
  refit: number;
}

const COLORS: Record<BoatLook, string> = { normal: '#49DC7A', sos: '#FF3B3B', stale: '#8A9A98' };
const MESH_COLOR = '#22F2EF';

const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c);

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

function boatIcon(id: string, look: BoatLook, stale: boolean): L.DivIcon {
  const cls = `bm bm--${look}${stale ? ' bm--stale' : ''}`;
  const tags = [
    look === 'sos' ? '<span class="bm-tag bm-tag--sos">SOS</span>' : '',
    stale ? '<span class="bm-tag bm-tag--stale">LAST KNOWN - no contact for <span class="bm-time"></span></span>' : '',
  ].join('');
  return L.divIcon({
    className: 'boat-icon',
    iconSize: [22, 22],
    iconAnchor: [11, 11],
    popupAnchor: [0, -14],
    html: `<div class="${cls}">${look === 'sos' ? '<span class="bm-ring"></span>' : ''}<span class="bm-dot"></span>`
      + `<span class="bm-label"><strong>Boat ${esc(id)}</strong>${tags}</span></div>`,
  });
}

function textIcon(text: string, cls: string): L.DivIcon {
  return L.divIcon({ className: `map-text ${cls}`, iconSize: [0, 0], html: `<span>${esc(text)}</span>` });
}


function BoatPopup({ boat, now }: { boat: Boat; now: number }) {
  const stale = isStale(boat, now);
  return (
    <div className="popup">
      <p className="popup-title">
        Boat {boat.id} {boat.status === 'sos' && <span className="badge badge--sos">SOS</span>}
        {stale && <span className="badge badge--stale">LAST KNOWN</span>}
      </p>
      <p className="mono">{formatCoords(boat.lat, boat.lon)}</p>
      <p>Last seen {formatDuration(now - boat.lastSeen)} ago</p>
      {boat.status === 'sos' && <p>Trigger: {triggerLabel(boat.trigger)} · {boat.acked ? 'Delivered to boat ✓' : 'Not yet delivered to boat'}</p>}
      <p>Route: {routeLabel(boat)} · gateway {boat.gateway || '-'}</p>
      <p><a href={googleMapsUrl(boat.lat, boat.lon)} target="_blank" rel="noopener noreferrer">Open in Google Maps</a></p>
    </div>
  );
}

function BoatLayers({ boat, now, byId, onMarker }: {
  boat: Boat;
  now: number;
  byId: Map<string, Boat>;
  onMarker: (id: string, m: L.Marker | null) => void;
}) {
  const stale = isStale(boat, now);
  const look = boatLook(boat, now);
  const color = COLORS[look];
  const pos: [number, number] = [boat.lat, boat.lon];
  const icon = useMemo(() => boatIcon(boat.id, look, stale), [boat.id, look, stale]);
  const markerRef = useRef<L.Marker | null>(null);

  // Update the "no contact for" text in place, so the icon (and the SOS pulse) is not rebuilt every second.
  const silentFor = formatDuration(now - boat.lastSeen);
  useEffect(() => {
    const el = markerRef.current?.getElement()?.querySelector('.bm-time');
    if (el) el.textContent = silentFor;
  });

  const trail = useMemo(() => {
    const pts = boat.trail.slice();
    const last = pts[pts.length - 1];
    if (!last || last[0] !== boat.lat || last[1] !== boat.lon) pts.push([boat.lat, boat.lon]);
    return pts;
  }, [boat.trail, boat.lat, boat.lon]);

  const relayed = boat.hops > 0 || boat.via !== boat.id;
  const mesh: [number, number][] = [];
  if (relayed) {
    mesh.push(pos);
    const via = byId.get(boat.via);
    if (via && via.id !== boat.id && via.id !== boat.gateway) mesh.push([via.lat, via.lon]);
    const gw = byId.get(boat.gateway);
    if (gw && gw.id !== boat.id) mesh.push([gw.lat, gw.lon]);
  }

  const showSearch = boat.status === 'sos' && stale;
  const radiusKm = showSearch ? searchRadiusKm(boat, now) : 0;

  return (
    <>
      {trail.length > 1 && (
        <Polyline positions={trail} pathOptions={{ color, weight: 2, opacity: 0.75, dashArray: stale ? '4 6' : undefined }} />
      )}
      {mesh.length > 1 && (
        <Polyline positions={mesh} pathOptions={{ color: MESH_COLOR, weight: 2, opacity: 0.9, dashArray: '8 8' }} />
      )}
      {showSearch && (
        <>
          <Circle center={pos} radius={radiusKm * 1000}
            pathOptions={{ color: COLORS.sos, weight: 2, dashArray: '6 8', fillColor: COLORS.sos, fillOpacity: 0.1 }} />
          {/* West edge of the circle: boat labels extend east, so this side stays clear. */}
          <Marker position={[boat.lat, boat.lon - kmToLonDeg(radiusKm, boat.lat)]} interactive={false} keyboard={false}
            icon={textIcon(`ESTIMATED search area · r ≈ ${radiusKm.toFixed(1)} km`, 'map-text--search')} />
        </>
      )}
      <Marker
        position={pos}
        icon={icon}
        title={`Boat ${boat.id}`}
        alt={`Boat ${boat.id}`}
        zIndexOffset={look === 'sos' ? 1000 : stale ? 0 : 500}
        ref={(m) => { markerRef.current = m; onMarker(boat.id, m); }}
      >
        <Popup>
          <BoatPopup boat={boat} now={now} />
        </Popup>
      </Marker>
    </>
  );
}

/** Fit the view to all boats whenever a boat id appears that we have not seen. */
function AutoFit({ boats, holdView, refit }: { boats: Boat[]; holdView: boolean; refit: number }) {
  const map = useMap();
  const known = useRef(new Set<string>());
  const lastRefit = useRef(refit);
  const boatsRef = useRef(boats);
  boatsRef.current = boats;

  const fitAll = () => {
    const points: [number, number][] = boatsRef.current.map((b) => [b.lat, b.lon]);
    if (points.length === 0) return;
    // Extra room on the left: the "ESTIMATED search area" label sits west of a silent SOS boat
    map.fitBounds(L.latLngBounds(points), { paddingTopLeft: [260, 70], paddingBottomRight: [90, 70], maxZoom: 12 });
  };

  useEffect(() => {
    if (boats.length === 0) {
      known.current.clear();
      return;
    }
    const fresh = boats.some((b) => !known.current.has(b.id));
    boats.forEach((b) => known.current.add(b.id));
    if (fresh && !holdView) fitAll();
  }, [boats, map, holdView]);

  useEffect(() => {
    if (refit !== lastRefit.current) {
      lastRefit.current = refit;
      fitAll();
    }
  }, [refit]);
  return null;
}

function FocusHandler({ focus, boats, markers }: {
  focus: FocusRequest | null;
  boats: Boat[];
  markers: MutableRefObject<Map<string, L.Marker>>;
}) {
  const map = useMap();
  const boatsRef = useRef(boats);
  boatsRef.current = boats;

  useEffect(() => {
    if (!focus) return;
    const boat = boatsRef.current.find((b) => b.id === focus.id);
    if (!boat) return;
    let opened = false;
    const open = () => {
      if (opened) return;
      opened = true;
      markers.current.get(boat.id)?.openPopup();
    };
    const zoom = Math.max(map.getZoom(), focus.zoom ?? 12);
    map.once('moveend', open);
    if (reducedMotion()) map.setView([boat.lat, boat.lon], zoom, { animate: false });
    else map.flyTo([boat.lat, boat.lon], zoom, { duration: 0.8 });
    const fallback = window.setTimeout(open, 1200);
    return () => {
      map.off('moveend', open);
      window.clearTimeout(fallback);
    };
  }, [focus, map, markers]);
  return null;
}

export function BoatMap({ boats, now, focus, holdView, refit }: Props) {
  const markers = useRef(new Map<string, L.Marker>());
  const byId = useMemo(() => new Map(boats.map((b) => [b.id, b])), [boats]);
  const onMarker = (id: string, m: L.Marker | null) => {
    if (m) markers.current.set(id, m);
    else markers.current.delete(id);
  };
  // Draw SOS boats last so they sit on top of everything else.
  const drawOrder = useMemo(() => boats.slice().reverse(), [boats]);

  return (
    <MapContainer center={MAP_CENTER} zoom={MAP_ZOOM} className="map" worldCopyJump>
      {/* Standard OpenStreetMap tiles (no API key); styles.css turns them dark to match the theme */}
      <TileLayer
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        maxZoom={18}
      />
      {drawOrder.map((b) => (
        <BoatLayers key={b.id} boat={b} now={now} byId={byId} onMarker={onMarker} />
      ))}
      <AutoFit boats={boats} holdView={holdView} refit={refit} />
      <FocusHandler focus={focus} boats={boats} markers={markers} />
    </MapContainer>
  );
}
