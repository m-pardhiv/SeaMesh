// Demo mode: simulates three boats off Kochi with no network calls.
//   A ~32 km out, relayed via C (1 hop) to gateway B. C and B are direct.
//   t=20s A raises SOS (water sensor); t=23s the ack reaches A;
//   t=60s A stops sending, so it goes stale 60 s later and shows a search area.
import type { Boat, SeaEvent } from '../types';

const TICK_MS = 5000;
const SOS_AT_MS = 20_000;
const ACK_AT_MS = 23_000;
const A_SILENT_AT_MS = 60_000;
const DRIFT_DEG = 0.002; // max step per tick, about 200 m

interface DemoBoatSpec {
  id: string;
  lat: number;
  lon: number;
  hops: number;
  via: string;
  rssi: number;
}

const SPECS: DemoBoatSpec[] = [
  { id: 'A', lat: 9.95, lon: 75.95, hops: 1, via: 'C', rssi: -104 },
  { id: 'C', lat: 9.965, lon: 76.04, hops: 0, via: 'C', rssi: -96 },
  { id: 'B', lat: 9.98, lon: 76.14, hops: 0, via: 'B', rssi: -52 },
];
const GATEWAY = 'B';

export interface DemoSink {
  boat(b: Boat): void;
  event(e: SeaEvent): void;
}

/** Starts the simulation; returns a function that stops it. */
export function startDemo(sink: DemoSink): () => void {
  const t0 = Date.now();
  const timers: number[] = [];
  let seq = 0;

  const boats = new Map<string, Boat>(
    SPECS.map((s) => [s.id, {
      id: s.id, lat: s.lat, lon: s.lon, status: 'normal', trigger: '-', lastSeen: t0,
      sosSince: null, acked: false, hops: s.hops, via: s.via, gateway: GATEWAY,
      rssi: s.rssi, msgId: 0, trail: [[s.lat, s.lon]],
    }]),
  );
  const baseRssi = new Map(SPECS.map((s) => [s.id, s.rssi]));

  const emitBoat = (b: Boat) => sink.boat({ ...b, trail: b.trail.slice() });
  const emitEvent = (type: SeaEvent['type'], boat: string, text: string) =>
    sink.event({ id: `demo-${t0}-${++seq}`, time: Date.now(), type, boat, text });

  const route = (b: Boat) =>
    b.hops > 0 ? `via ${b.via} (${b.hops} hop), gateway ${b.gateway}`
      : b.id === b.gateway ? 'direct (gateway boat)' : `direct to gateway ${b.gateway}`;

  function packet(b: Boat, move: boolean) {
    if (move) {
      b.lat += (Math.random() - 0.5) * DRIFT_DEG;
      b.lon += (Math.random() - 0.5) * DRIFT_DEG;
      b.trail = [...b.trail, [b.lat, b.lon] as [number, number]].slice(-20);
    }
    b.lastSeen = Date.now();
    b.msgId += 1;
    b.rssi = (baseRssi.get(b.id) ?? -90) + Math.round((Math.random() - 0.5) * 8);
    emitBoat(b);
  }

  for (const b of boats.values()) {
    packet(b, false);
    emitEvent('position', b.id, `Boat ${b.id} first heard, ${route(b)}`);
  }

  timers.push(window.setInterval(() => {
    const elapsed = Date.now() - t0;
    for (const b of boats.values()) {
      if (b.id === 'A' && elapsed >= A_SILENT_AT_MS) continue;
      packet(b, true);
      emitEvent('position', b.id, `Position from ${b.id}, ${route(b)}`);
    }
  }, TICK_MS));

  timers.push(window.setTimeout(() => {
    const a = boats.get('A')!;
    a.status = 'sos';
    a.trigger = 'WATER';
    a.sosSince = Date.now();
    packet(a, false);
    emitEvent('sos', 'A', `SOS from A (water sensor) via ${a.gateway}`);
  }, SOS_AT_MS));

  timers.push(window.setTimeout(() => {
    const a = boats.get('A')!;
    a.acked = true;
    emitBoat(a);
  }, ACK_AT_MS));

  return () => timers.forEach((t) => { window.clearInterval(t); window.clearTimeout(t); });
}
