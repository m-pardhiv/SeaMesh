// Live fleet state: initial GET /api/boats, then the /live WebSocket, with
// automatic reconnect. In demo mode the simulator feeds the same reducer.
import { useCallback, useEffect, useReducer, useState } from 'react';
import type { Boat, ConnState, SeaEvent } from '../types';
import { API_URL, DEMO, MAX_EVENTS, OFFLINE_AFTER_ATTEMPTS, RECONNECT_DELAYS_MS, WS_URL } from '../config';
import { normalizeBoat, normalizeEvent, normalizeList } from './normalize';
import { startDemo } from './demo';

interface State {
  boats: Record<string, Boat>;
  events: SeaEvent[]; // newest first, at most MAX_EVENTS
  conn: ConnState;
  /** serverTime - Date.now(), so "last seen" uses the server's clock */
  clockOffset: number;
  error: string | null;
}

type Action =
  | { type: 'snapshot'; serverTime: number | null; boats: Boat[]; events: SeaEvent[] }
  | { type: 'boat'; boat: Boat }
  | { type: 'event'; event: SeaEvent }
  | { type: 'conn'; conn: ConnState }
  | { type: 'error'; error: string | null }
  | { type: 'reset' };

const initialState: State = { boats: {}, events: [], conn: 'connecting', clockOffset: 0, error: null };

function mergeEvents(current: SeaEvent[], incoming: SeaEvent[]): SeaEvent[] {
  const byId = new Map(current.map((e) => [e.id, e]));
  incoming.forEach((e) => byId.set(e.id, e));
  return [...byId.values()].sort((a, b) => b.time - a.time).slice(0, MAX_EVENTS);
}

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'snapshot':
      return {
        ...state,
        boats: Object.fromEntries(action.boats.map((b) => [b.id, b])),
        events: mergeEvents(state.events, action.events),
        clockOffset: action.serverTime === null ? state.clockOffset : action.serverTime - Date.now(),
      };
    case 'boat':
      return { ...state, boats: { ...state.boats, [action.boat.id]: action.boat } };
    case 'event':
      return { ...state, events: mergeEvents(state.events, [action.event]) };
    case 'conn':
      return state.conn === action.conn ? state : { ...state, conn: action.conn };
    case 'error':
      return { ...state, error: action.error };
    case 'reset':
      return { ...initialState, conn: state.conn };
  }
}

function parseSnapshot(raw: Record<string, unknown>): Action {
  return {
    type: 'snapshot',
    serverTime: typeof raw.serverTime === 'number' ? raw.serverTime : null,
    boats: normalizeList(raw.boats, normalizeBoat),
    events: normalizeList(raw.events, normalizeEvent),
  };
}

export function useLiveData() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const [demoRun, setDemoRun] = useState(0);

  // Demo mode: no network at all.
  useEffect(() => {
    if (!DEMO) return;
    dispatch({ type: 'reset' });
    dispatch({ type: 'conn', conn: 'live' });
    return startDemo({
      boat: (boat) => dispatch({ type: 'boat', boat }),
      event: (event) => dispatch({ type: 'event', event }),
    });
  }, [demoRun]);

  // Server mode.
  useEffect(() => {
    if (DEMO) return;
    if (!API_URL) {
      dispatch({ type: 'conn', conn: 'offline' });
      dispatch({ type: 'error', error: 'VITE_API_URL is not set. Set it to the SeaMesh server URL, or run with VITE_DEMO=1.' });
      return;
    }

    let disposed = false;
    let ws: WebSocket | null = null;
    let attempt = 0;
    let everOpened = false;
    let timer: number | undefined;

    async function fetchState() {
      try {
        const res = await fetch(`${API_URL}/api/boats`, { cache: 'no-store' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json: unknown = await res.json();
        if (!disposed && typeof json === 'object' && json !== null) {
          dispatch(parseSnapshot(json as Record<string, unknown>));
        }
      } catch (err) {
        console.warn('[SeaMesh] GET /api/boats failed:', err);
      }
    }

    function onMessage(data: unknown) {
      if (typeof data !== 'string') return;
      let msg: unknown;
      try {
        msg = JSON.parse(data);
      } catch {
        return;
      }
      if (typeof msg !== 'object' || msg === null) return;
      const m = msg as Record<string, unknown>;
      if (m.kind === 'snapshot') {
        dispatch(parseSnapshot(m));
      } else if (m.kind === 'boat') {
        const boat = normalizeBoat(m.boat);
        if (boat) dispatch({ type: 'boat', boat });
      } else if (m.kind === 'event') {
        const event = normalizeEvent(m.event);
        if (event) dispatch({ type: 'event', event });
      }
    }

    function connect() {
      if (disposed) return;
      window.clearTimeout(timer);
      const socket = new WebSocket(WS_URL);
      ws = socket;
      socket.onopen = () => {
        const isReconnect = everOpened || attempt > 0;
        everOpened = true;
        attempt = 0;
        dispatch({ type: 'conn', conn: 'live' });
        dispatch({ type: 'error', error: null });
        if (isReconnect) void fetchState();
      };
      socket.onmessage = (ev) => onMessage(ev.data);
      socket.onclose = () => {
        if (ws === socket) ws = null;
        if (!disposed) scheduleReconnect();
      };
      // onclose always follows onerror, so reconnect is handled there.
    }

    function scheduleReconnect() {
      const delay = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)];
      attempt += 1;
      const offline = !navigator.onLine || attempt >= OFFLINE_AFTER_ATTEMPTS;
      dispatch({ type: 'conn', conn: offline ? 'offline' : 'reconnecting' });
      timer = window.setTimeout(connect, delay);
    }

    function onOnline() {
      if (!ws) {
        attempt = 0;
        connect();
      }
    }

    void fetchState();
    connect();
    window.addEventListener('online', onOnline);

    return () => {
      disposed = true;
      window.clearTimeout(timer);
      window.removeEventListener('online', onOnline);
      ws?.close();
    };
  }, []);

  const restartDemo = useCallback(() => setDemoRun((n) => n + 1), []);

  return { ...state, restartDemo: DEMO ? restartDemo : undefined };
}
