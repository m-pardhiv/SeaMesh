// Tunable constants. Change the reference point or thresholds here.

/** Reference point for "distance and direction from shore". */
export const KOCHI_HARBOUR = { name: 'Kochi harbour', lat: 9.94, lon: 76.26 };

export const MAP_CENTER: [number, number] = [9.96, 76.1];
export const MAP_ZOOM = 10;

/** Rough area where boats still have a phone signal. */
export const COVERAGE = { lat: 9.9312, lon: 76.2673, radiusKm: 20, label: 'Approx. cellular coverage' };

/** A boat is stale ("last known") after this long without a packet. */
export const STALE_AFTER_MS = 60_000;

/** Estimated search area for a silent SOS boat: max(MIN, DRIFT * hours). */
export const DRIFT_KMH = 1.5;
export const MIN_SEARCH_RADIUS_KM = 0.5;

/** After Acknowledge, the SOS is cleared on the server this long later (unless "Keep alert"). */
export const ACK_CLEAR_MS = 10_000;

export const MAX_EVENTS = 200;
export const RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000];
/** Show "Offline" instead of "Reconnecting..." after this many failed attempts in a row. */
export const OFFLINE_AFTER_ATTEMPTS = 4;

export const DEMO = import.meta.env.VITE_DEMO === '1';
export const API_URL = (import.meta.env.VITE_API_URL ?? '').trim().replace(/\/+$/, '');
/** http -> ws, https -> wss */
export const WS_URL = API_URL ? API_URL.replace(/^http/i, 'ws') + '/live' : '';
