// Defensive parsing of server JSON. Anything malformed is dropped, not rendered.
import type { Boat, SeaEvent } from '../types';

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;
const num = (v: unknown, fallback: number) => (typeof v === 'number' && Number.isFinite(v) ? v : fallback);
const str = (v: unknown, fallback: string) => (typeof v === 'string' ? v : fallback);

export function normalizeBoat(raw: unknown): Boat | null {
  if (!isObj(raw)) return null;
  const id = raw.id === undefined || raw.id === null ? '' : String(raw.id);
  const lat = num(raw.lat, NaN);
  const lon = num(raw.lon, NaN);
  if (!id || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;

  const trail = Array.isArray(raw.trail)
    ? raw.trail.filter((p): p is [number, number] =>
        Array.isArray(p) && p.length >= 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]))
        .map((p) => [p[0], p[1]] as [number, number])
    : [];
  const trigger = raw.trigger === 'BTN' || raw.trigger === 'WATER' ? raw.trigger : '-';

  return {
    id,
    lat,
    lon,
    status: raw.status === 'sos' ? 'sos' : 'normal',
    trigger,
    lastSeen: num(raw.lastSeen, 0),
    sosSince: typeof raw.sosSince === 'number' && Number.isFinite(raw.sosSince) ? raw.sosSince : null,
    acked: raw.acked === true,
    hops: Math.max(0, Math.round(num(raw.hops, 0))),
    via: raw.via === undefined || raw.via === null ? id : String(raw.via),
    gateway: raw.gateway === undefined || raw.gateway === null ? '' : String(raw.gateway),
    rssi: num(raw.rssi, NaN),
    msgId: num(raw.msgId, 0),
    trail,
  };
}

export function normalizeEvent(raw: unknown): SeaEvent | null {
  if (!isObj(raw)) return null;
  const id = raw.id === undefined || raw.id === null ? '' : String(raw.id);
  const time = num(raw.time, NaN);
  if (!id || !Number.isFinite(time)) return null;
  const type = raw.type === 'sos' || raw.type === 'resolved' ? raw.type : 'position';
  return { id, time, type, boat: raw.boat === undefined ? '' : String(raw.boat), text: str(raw.text, '') };
}

export const normalizeList = <T,>(raw: unknown, fn: (v: unknown) => T | null): T[] =>
  Array.isArray(raw) ? raw.map(fn).filter((v): v is T => v !== null) : [];
