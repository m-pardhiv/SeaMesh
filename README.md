# SeaMesh

Low-cost boat-to-boat LoRa mesh that keeps fishing boats trackable after they lose cellular coverage offshore. Each boat broadcasts its position and SOS alerts over license-free LoRa. Nearby boats relay the packets hop by hop until a boat with internet uploads them to the shore dashboard.

This repo holds the hackathon prototype. On a real boat, SeaMesh plugs into the boat's existing GPS/GPRS tracker. In the demo, a phone running the SeaMesh app plays that tracker: it gives the ESP32 its GPS position over Bluetooth and uploads alerts when it has internet.

## Repository layout

| Folder | Contents |
|---|---|
| `esp32_firmware/` | ESP32 + Ra-02 firmware (Arduino). Identical on every boat |
| `backend/` | Node.js server on Render: receives uploads from the phones and pushes them to the dashboard |
| `seamesh mobile connection/` | Android app (demo stand-in for the boat's GPS/GPRS unit). Same app on every phone |

`.github/workflows/build-apk.yml` builds the app on GitHub on every push that changes it. The latest APK is at **Releases → apk-latest** (`SeaMesh.apk`), or under the run's artifacts in the Actions tab.

## How it works

```
 Boat in danger (no internet)             Boat with internet                       Shore
 phone (GPS) --BLE--> ESP32 --LoRa--> ... --LoRa--> ESP32 --BLE--> phone --HTTPS--> backend --WebSocket--> dashboard
                       ^                                                                |
                       +------------------ LoRa ACK once the upload succeeded ----------+
```

1. **Packet creation.** Each boat packs its ID, GPS position, fix time, status (position or SOS), a message number and a hop counter into a ~45-byte packet:
   `type,origin,msgId,lat,lon,time,hop,trigger,via*XX`, e.g. `S,3F2A,7,9.951234,75.948765,1761200000,0,BTN,3F2A*5C`. `*XX` is a CRC-8 of the text, checked after the packet is read out of the radio; it is not passed on to the phone.
2. **Broadcast** over LoRa in all directions. No cellular network is involved.
3. **Duplicate check.** A receiving boat drops packets it has already seen, so nothing bounces forever.
4. **Relay or upload.** A boat whose phone has internet hands the packet to the phone, which uploads it. A boat without internet increments the hop counter and rebroadcasts it.
5. **Hop limit.** Packets are dropped after 4 hops.
6. **SOS priority.** SOS packets go out first, are repeated every 4 s, and are relayed without delay.
7. **Acknowledgement.** After the phone confirms the upload, its ESP32 sends an ACK back over LoRa, and the boat in danger stops repeating its SOS.

Every boat runs the same firmware and the same app. Whichever boat currently has internet acts as the gateway.

## Hardware (per boat)

- ESP32 DevKit (30-pin)
- Ra-02 LoRa module (SX1278, 433 MHz)
- SOS push button and water sensor (two bare wires), both optional
- Power: USB, or an HW-131 breadboard supply (5V to VIN)

Sensors are optional. In the demo only one unit has them; a unit with nothing wired never raises an alert or a fault.

### Wiring

| Ra-02 | ESP32 |
|---|---|
| 3.3V | 3V3 (never 5V) |
| GND | GND |
| NSS | D5 |
| MOSI | D23 |
| MISO | D19 |
| SCK | D18 |
| RST | D14 |
| DIO0 | D26 |

| Part | ESP32 |
|---|---|
| SOS button | D27 and GND |
| Water sensor wires | D33 and GND (tips ~1 cm apart). Triggers after 2 s in water; use salty water for a reliable demo |

Never power the board from USB and VIN at the same time.

## Firmware setup

1. Install the Arduino IDE, the **esp32** board package (Espressif) and the **LoRa** library (Sandeep Mistry).
2. Open `esp32_firmware/esp32_firmware.ino`, select **ESP32 Dev Module** and upload. Nothing needs editing: the boat ID comes from the chip (e.g. `3F2A`) and the board advertises over Bluetooth as `SeaMesh-3F2A`.
3. In the SeaMesh app on the boat's phone, pick that device.

If no phone GPS fix has arrived yet, the board uses a simulated position at sea off Kochi (packet `time` = 0).

### Bluetooth contract (ESP32 ↔ phone app)

Service `6e5a0001-5ea3-4e5a-9c1d-2b7f0a4c8e10`

| Characteristic | Direction | Content |
|---|---|---|
| GPS `6e5a0002-…` | phone → ESP32, write | `lat,lon,accuracy_m,fix_unix_time` |
| CONTROL `6e5a0003-…` | phone → ESP32, write | `NET,1` / `NET,0` (phone internet), `ACK,<origin>,<msgId>` (upload succeeded) |
| PACKETS `6e5a0004-…` | ESP32 → phone, notify | `type,origin,msgId,lat,lon,time,hop,trigger,via,gateway,rssi` |

### Serial commands (115200 baud)

`sos`, `cancel`, `pos`, `offline` (ignore the phone's internet and relay over LoRa instead), `online`, `status` (also shows radio state, uptime and free memory), `txtest` / `txtest rfo` (send one test packet), `rxtest` (listen 10 s and report signal strength, headers, packets and CRC errors), `power N` (TX power 2-17 dBm), `regs` (modem registers), `hangtest` (freeze the loop to check the watchdog restarts the board)

## Backend

Node.js 20+, one dependency (`ws`). State is kept in memory, so it resets when the service restarts.

### Deploy on Render

Web service, root directory `backend`, build command `npm install`, start command `npm start`, region Singapore, a paid instance type (free instances sleep after 15 minutes idle and miss the first alert), health check path `/health`.

| Environment variable | Meaning |
|---|---|
| `API_KEY` | Secret the phone app sends in the `X-API-Key` header. Uploads are refused if unset |
| `SOS_HOLD_MIN` | Minutes an SOS stays active after its last SOS packet (default 10) |

### API

| Endpoint | Used by | Purpose |
|---|---|---|
| `POST /api/alerts` | phone app | Upload one packet (`X-API-Key` required). Duplicate uploads of the same packet return `{"ok":true,"duplicate":true}` |
| `GET /api/boats` | dashboard | `{ serverTime, boats, events }` |
| `WS /live` | dashboard | `{kind:"snapshot"}` on connect, then `{kind:"boat"}` and `{kind:"event"}` updates |
| `POST /api/reset` | you, between demo runs | Clears all boats and events (`X-API-Key` required) |
| `GET /health` | Render | Health check |

Each boat in `boats` has `id, lat, lon, status ("normal"/"sos"), trigger, lastSeen, sosSince, acked, hops, via, gateway, rssi, msgId, trail`. A boat that goes silent stays at its last reported position; the dashboard shows it as last known after 60 s without contact.

### Test without phones

```
cd backend
npm install
API_URL=https://<service>.onrender.com API_KEY=<key> npm run simulate
```

This plays boat A sending positions, raising a water-sensor SOS after 20 s and going silent after 60 s, heard by gateway boat B.

## Prototype limitations

- Radio: 433 MHz, SF9, 125 kHz, 10 dBm (the 10 mW license-free limit at 433 MHz in India). The test boards have no antennas, so the working range is about a metre; at SF7 or 2 dBm the boards did not hear each other at all. Fit 433 MHz antennas for any real range test.
- Power the Ra-02 from its own ESP32's 3V3 and GND. Powering it from a separate supply caused SPI corruption (missing radio at boot, modem settings changing by themselves).
- In India, 433 MHz is license-free only at low power (about 10 mW). A deployment would move to the 865–867 MHz band (up to 1 W) with SX1276-based modules and mast-mounted antennas.
- The system only helps when another boat is within LoRa range; a lone boat far offshore still needs satellite.
