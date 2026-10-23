// Sends fake uploads exactly like the SeaMesh phone app, to test the backend and
// the dashboard without boats or phones.
//
//   API_URL=https://<your-service>.onrender.com API_KEY=<key> npm run simulate
//
// Boat A (no internet, ~32 km out) is heard directly by boat B (has internet).
// A sends positions, raises an SOS after 20 s, then goes silent after 60 s so the
// dashboard shows its last known location.

const API_URL = (process.env.API_URL || "http://localhost:3000").replace(/\/$/, "");
const API_KEY = process.env.API_KEY || "";

const boatA = { id: "A1B2", lat: 9.95, lon: 75.95, msgId: 1 };
const boatB = { id: "C3D4", lat: 9.98, lon: 76.14, msgId: 1 };

async function upload(boat, type, trigger, hops, via, rssi) {
  boat.lat += (Math.random() - 0.5) * 0.0004;
  boat.lon += (Math.random() - 0.5) * 0.0004;
  const body = {
    type, boat: boat.id, msgId: boat.msgId++, lat: +boat.lat.toFixed(6), lon: +boat.lon.toFixed(6),
    time: Math.floor(Date.now() / 1000), hops, trigger, via, gateway: boatB.id, rssi,
    receivedAt: Date.now(),
  };
  const res = await fetch(`${API_URL}/api/alerts`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-API-Key": API_KEY },
    body: JSON.stringify(body),
  });
  console.log(`${type} ${boat.id} #${body.msgId} -> HTTP ${res.status} ${await res.text()}`);
}

async function main() {
  console.log(`Simulating against ${API_URL}`);
  const start = Date.now();
  while (true) {
    const t = (Date.now() - start) / 1000;
    if (t < 60) {
      const sos = t >= 20;
      await upload(boatA, sos ? "S" : "P", sos ? "WATER" : "-", 0, boatA.id, -60 - Math.round(Math.random() * 20));
    } else if (t < 61) {
      console.log("Boat A went silent - watch it turn into LAST KNOWN on the dashboard");
    }
    await upload(boatB, "P", "-", 0, boatB.id, 0);   // the gateway's own position
    if (t > 150) break;
    await new Promise((r) => setTimeout(r, 5000));
  }
}

main().catch((e) => { console.error(e.message); process.exit(1); });
