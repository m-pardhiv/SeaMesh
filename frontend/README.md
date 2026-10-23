# SeaMesh Shore Dashboard

Live shore-side dashboard for SeaMesh. Fishing boats that lose cellular coverage relay their GPS position and SOS alerts boat-to-boat over LoRa until a boat with internet uploads them to the server. This page is what coast guard and fisheries staff watch. It makes an SOS impossible to miss and always shows each boat's last known position, even after the boat goes silent.

Read-only: no login, no database, no commands sent to boats.

## Run locally

Requires Node 18+.

```sh
npm install
npm run dev:demo      # simulated boats, no network calls
npm run dev           # real server, needs VITE_API_URL (see below)
```

For the real server, create `.env.local`:

```sh
VITE_API_URL=https://seamesh.onrender.com
```

| Variable       | Meaning                                                                          |
| -------------- | -------------------------------------------------------------------------------- |
| `VITE_API_URL` | Server base URL. `https://` is turned into `wss://` for the live feed.            |
| `VITE_DEMO`    | `1` runs the built-in simulation and makes no network calls.                      |

These are baked in at build time, so rebuild after changing them.

## Deploy on Render (static site)

- Build command: `npm install && npm run build`
- Publish directory: `dist`
- Environment: `VITE_API_URL=https://seamesh.onrender.com` (or `VITE_DEMO=1` for a demo deployment)

The dashboard is served over https, so the API must be https too (browsers block `http://` and `ws://` from an https page).

## Server contract

1. `GET {VITE_API_URL}/api/boats` returns `{ serverTime, boats: Boat[], events: Event[] }`.
2. WebSocket `{VITE_API_URL}/live` (`http` → `ws`, `https` → `wss`) sends:
   - `{ "kind": "snapshot", "serverTime", "boats", "events" }` on connect
   - `{ "kind": "boat", "boat": Boat }` — replaces the boat with the same id
   - `{ "kind": "event", "event": Event }` — appended to the log

The socket reconnects with backoff (1 s, 2 s, 5 s, then every 10 s) and re-fetches `/api/boats` after reconnecting. The status bar shows **Live**, **Reconnecting...**, or **Offline** (after 4 failed attempts in a row, or when the browser is offline). "Last seen" times use the server clock (`serverTime`), so a wrong clock on the operator's PC does not mark boats stale.

See [src/types.ts](src/types.ts) for the `Boat` and `Event` fields.

## What the operator sees

- **SOS banner**: full width at the top while any boat has `status: "sos"`, one line per boat, newest first. For example: `SOS - Boat 3F2A - 9.95123, 75.94877 - water sensor - 2m 10s ago - 21.4 km WSW of Kochi harbour`. It also shows "Delivered to boat ✓" once `acked` is true. The banner stays until the boat is back to `normal`.
- **Alarm**: browsers block audio until the page is clicked, so press **Enable sound & notifications** once per session. After that, a looping alarm plays and a browser notification fires for each new SOS. **Acknowledge** only silences the alarm on this screen. A new SOS starts it again.
- **Last known location**: a boat is stale after 60 s without a packet. It stays on the map in grey with a dashed outline and "LAST KNOWN - no contact for …". A silent SOS boat gets an **estimated** search-area circle of radius `max(0.5, 1.5 × hours since last seen)` km (assumes about 1.5 km/h drift).
- **Map**: OpenStreetMap tiles, darkened. Each boat has a trail, a dashed green circle marks approximate cellular coverage (20 km around Kochi), and a dashed amber line shows the mesh route (boat → last relay → gateway) for relayed packets.
- **Boat list**: sorted SOS, then stale, then normal. Shows coordinates, live "last seen", route, gateway, RSSI, and distance and direction from Kochi harbour, with Copy coordinates and Open in Google Maps buttons. Click a boat to fly the map to it.
- **Event log**: newest first, HH:MM:SS, SOS in red, last 200 kept.

## Demo mode

`VITE_DEMO=1` simulates three boats off Kochi. **A** (about 32 km out) is relayed via **C** to gateway **B**. C and B are direct. All three drift every 5 s.

- At 20 s, A raises an SOS from its water sensor.
- At 23 s, the acknowledgement reaches A.
- At 60 s, A stops sending. It turns stale at about 2 minutes, which shows the last-known tag and the search area.

**Restart demo** in the status bar starts the scenario over.

## Configuration

Reference point, coverage circle, stale threshold, drift rate and reconnect delays are constants in [src/config.ts](src/config.ts).

## Layout

```
src/
  App.tsx               page layout, alarm and notification logic
  config.ts             tunable constants and env
  types.ts              Boat / Event types (server contract)
  data/useLiveData.ts   REST + WebSocket client with reconnect
  data/demo.ts          demo simulation
  data/normalize.ts     defensive parsing of server JSON
  components/           StatusBar, SosBanner, BoatMap, BoatList, EventLog
  lib/                  geo (haversine, bearing, compass), formatting, alarm
```
