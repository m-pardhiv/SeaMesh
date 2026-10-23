const toRad = (deg: number) => (deg * Math.PI) / 180;

/** Longitude offset for an east/west distance at a latitude, used to place map labels on circle edges. */
export const kmToLonDeg = (km: number, lat: number) => km / (111.32 * Math.cos(toRad(lat)));
