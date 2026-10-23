import type { Boat } from '../types';
import { KOCHI_HARBOUR } from '../config';
import { fromHarbour } from './geo';

/** 12s, 3m 20s, 1h 5m */
export function formatDuration(ms: number): string {
  const s = Math.floor(Math.max(0, ms) / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** HH:MM:SS in the browser's local time. */
export function formatClock(ts: number): string {
  return new Date(ts).toLocaleTimeString('en-GB', { hour12: false });
}

export const formatCoords = (lat: number, lon: number) => `${lat.toFixed(5)}, ${lon.toFixed(5)}`;

export function triggerLabel(trigger: Boat['trigger']): string {
  if (trigger === 'BTN') return 'SOS button';
  if (trigger === 'WATER') return 'water sensor';
  return 'trigger unknown';
}

/** "direct" or "2 hops, last via 91C0" */
export function routeLabel(b: Boat): string {
  if (b.hops === 0 && b.via === b.id) return 'direct';
  if (b.hops === 0) return `relayed, last via ${b.via}`;
  return `${b.hops} hop${b.hops === 1 ? '' : 's'}, last via ${b.via}`;
}

/** "21.4 km WSW of Kochi harbour" */
export function harbourLabel(lat: number, lon: number, withName = true): string {
  const { km, dir } = fromHarbour(lat, lon);
  return `${km.toFixed(1)} km ${dir}${withName ? ` of ${KOCHI_HARBOUR.name}` : ''}`;
}

export const googleMapsUrl = (lat: number, lon: number) =>
  `https://www.google.com/maps?q=${lat.toFixed(6)},${lon.toFixed(6)}`;
