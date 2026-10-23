import type { Boat } from '../types';
import { DRIFT_KMH, MIN_SEARCH_RADIUS_KM, STALE_AFTER_MS } from '../config';

export const isStale = (b: Boat, now: number) => now - b.lastSeen > STALE_AFTER_MS;

/** Radius of the estimated search area around a silent boat's last position. */
export function searchRadiusKm(b: Boat, now: number): number {
  const hours = Math.max(0, now - b.lastSeen) / 3_600_000;
  return Math.max(MIN_SEARCH_RADIUS_KM, DRIFT_KMH * hours);
}

/** Identifies one SOS incident, so a new SOS from the same boat alarms again. */
export const sosKey = (b: Boat) => `${b.id}:${b.sosSince ?? 'unknown'}`;

const sosStart = (b: Boat) => b.sosSince ?? b.lastSeen;

/** SOS first (newest first), then stale, then normal; ties by id. */
export function sortBoats(boats: Boat[], now: number): Boat[] {
  const rank = (b: Boat) => (b.status === 'sos' ? 0 : isStale(b, now) ? 1 : 2);
  return boats.slice().sort((a, b) =>
    rank(a) - rank(b) ||
    (a.status === 'sos' ? sosStart(b) - sosStart(a) : 0) ||
    a.id.localeCompare(b.id));
}

export type BoatLook = 'sos' | 'stale' | 'normal';

/** Colour family for map and badges. A silent SOS boat stays red. */
export const boatLook = (b: Boat, now: number): BoatLook =>
  b.status === 'sos' ? 'sos' : isStale(b, now) ? 'stale' : 'normal';
