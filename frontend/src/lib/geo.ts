import { KOCHI_HARBOUR } from '../config';

const EARTH_RADIUS_KM = 6371;
const toRad = (deg: number) => (deg * Math.PI) / 180;
const toDeg = (rad: number) => (rad * 180) / Math.PI;

/** Great-circle distance in km (haversine). */
export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
}

/** Initial bearing from point 1 to point 2, degrees clockwise from north (0-360). */
export function bearingDeg(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const φ1 = toRad(lat1);
  const φ2 = toRad(lat2);
  const Δλ = toRad(lon2 - lon1);
  const y = Math.sin(Δλ) * Math.cos(φ2);
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

const COMPASS_16 = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

export function compass16(bearing: number): string {
  return COMPASS_16[Math.round(bearing / 22.5) % 16];
}

/** Distance and compass direction of a position as seen from Kochi harbour. */
export function fromHarbour(lat: number, lon: number): { km: number; dir: string } {
  const km = haversineKm(KOCHI_HARBOUR.lat, KOCHI_HARBOUR.lon, lat, lon);
  const dir = compass16(bearingDeg(KOCHI_HARBOUR.lat, KOCHI_HARBOUR.lon, lat, lon));
  return { km, dir };
}

/** Latitude offset for a north/south distance, used to place map labels on circle edges. */
export const kmToLatDeg = (km: number) => km / 110.574;
export const kmToLonDeg = (km: number, lat: number) => km / (111.32 * Math.cos(toRad(lat)));
