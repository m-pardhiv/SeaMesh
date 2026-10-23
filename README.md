# SeaMesh

Low-cost boat-to-boat LoRa mesh that keeps fishing boats trackable after they lose cellular coverage offshore. Each boat broadcasts its position and SOS alerts over license-free LoRa. Nearby boats relay the packets hop by hop until a boat with signal uploads them to the shore dashboard.

This repo holds the hackathon prototype. Phones stand in for Kerala's existing GPS/GPRS systems: a phone hotspot plays the part of a boat's cellular link, and the gateway ESP32 serves a live map dashboard to that phone.

## How it works

1. **Packet creation.** Each boat packs its ID, position, timestamp, status (position or SOS), a message number and a hop counter into a ~45-byte packet:
   `type,origin,msgId,lat,lon,time,hop,trigger,via`, e.g. `S,A,7,9.95012,75.94988,42,0,BTN,A`
2. **Broadcast** over LoRa in all directions. No cellular network is involved.
3. **Duplicate check.** A receiving boat drops packets it has already seen, so nothing bounces forever.
4. **Relay or upload.** A boat with signal uploads the packet to the dashboard. A boat without signal increments the hop counter and rebroadcasts it.
5. **Hop limit.** Packets are dropped after 4 hops.
6. **SOS priority.** SOS packets go out first, are repeated every 4 s, and are relayed without delay.
7. **Acknowledgement.** The uploading boat sends an ACK back over LoRa, and the sender stops repeating its SOS.

The same firmware runs on every boat. A boat becomes a gateway automatically when it is connected to the hotspot.

## Hardware (per boat)

- ESP32 DevKit (30-pin)
- Ra-02 LoRa module (SX1278, 433 MHz)
- SOS push button (stranded boat)
- Water sensor: two bare wires on a touch pin (stranded boat)
- Power: USB, or an HW-131 breadboard supply (5V to VIN)

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
| Water sensor wires | D4 and GND (tips ~1 cm apart) |

Never power the board from USB and VIN at the same time.

## Setup

1. Install the Arduino IDE, the **esp32** board package (Espressif) and the **LoRa** library (Sandeep Mistry).
2. Open `firmware/seamesh_mesh/seamesh_mesh.ino` and edit the settings block for each board:
   ```cpp
   #define BOAT_ID   "A"   // unique per boat
   #define WIFI_SSID ""    // gateway boat only: phone hotspot name (2.4 GHz)
   #define WIFI_PASS ""
   ```
   Do not commit real hotspot passwords.
3. Select **ESP32 Dev Module** and upload.
4. On the gateway boat, open the IP address printed in the Serial Monitor in the phone's browser to see the live map.

### Dashboard

- Blue pin: boat heard recently. Red pin: SOS. Grey dashed pin: **last known position**. The boat has not been heard for over 60 s, so the map keeps showing where it was last reported, with the time since contact.
- Each boat shows a trail of its last 10 reported positions.
- The green dashed circle marks the approximate cellular coverage (~20 km from Kochi), so boats outside it are the ones relying on the mesh.

### Serial commands (115200 baud)

`sos`, `cancel`, `pos`, `offline` (gateway pretends to lose cellular and relays instead), `online`, `status`

## Prototype limitations

- Transmit power is set to the 2 dBm minimum because the test boards have no antennas, so the range is a few metres. Fit 433 MHz antennas before raising it.
- In India, 433 MHz is license-free only at low power (about 10 mW). A deployment would move to the 865–867 MHz band (up to 1 W) with SX1276-based modules and mast-mounted antennas.
- GPS positions are simulated: each boat starts at sea off Kochi based on its ID (A ~32 km out, C ~24 km, B ~12 km, inside coverage) and drifts slightly.
- The system only helps when another boat is within LoRa range; a lone boat far offshore still needs satellite.
