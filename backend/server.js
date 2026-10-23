// SeaMesh backend
//
// Phones (the SeaMesh app on a boat with internet) upload LoRa packets here.
// The server keeps the latest state of every boat in memory and pushes each
// change to the dashboards over a WebSocket.
//
//   POST /api/alerts   upload one packet (header X-API-Key required)
//   GET  /api/boats    current state: { serverTime, boats, events }
//   WS   /live        snapshot on connect, then { kind: "boat" | "event" } messages
//   POST /api/reset    clear all state (X-API-Key), handy between demo runs
//   GET  /health       for Render's health check

const http = require("http");
const crypto = require("crypto");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 3000;
const API_KEY = process.env.API_KEY || "";
const SOS_HOLD_MS = (Number(process.env.SOS_HOLD_MIN) || 10) * 60 * 1000;
const TRAIL_LEN = 20;
const MAX_EVENTS = 200;
const MAX_BODY = 4096;

const boats = new Map();     // id -> Boat
const seen = new Map();      // "type:boat:msgId" -> time, to drop duplicate uploads
let events = [];
let eventSeq = 0;

// ---------- state ----------
function addEvent(type, boat, text) {
  const event = { id: "e" + ++eventSeq, time: Date.now(), type, boat, text };
  events.push(event);
  if (events.length > MAX_EVENTS) events = events.slice(-MAX_EVENTS);
  broadcast({ kind: "event", event });
}

function describeRoute(p) {
  const route = p.hops === 0 ? "direct" : `${p.hops} relay hop(s), last via ${p.via}`;
  return `${route}, uploaded by ${p.gateway}`;
}

function applyPacket(p) {
  const now = Date.now();
  let boat = boats.get(p.boat);
  if (!boat) {
    boat = { id: p.boat, status: "normal", trigger: "-", sosSince: null, acked: false, trail: [] };
    boats.set(p.boat, boat);
  }

  Object.assign(boat, {
    lat: p.lat, lon: p.lon, lastSeen: now, hops: p.hops, via: p.via,
    gateway: p.gateway, rssi: p.rssi, msgId: p.msgId,
  });
  boat.trail.push([p.lat, p.lon]);
  if (boat.trail.length > TRAIL_LEN) boat.trail.shift();

  if (p.type === "S") {
    if (boat.status !== "sos") {
      boat.sosSince = now;
      const why = p.trigger === "WATER" ? "water sensor" : "SOS button";
      addEvent("sos", p.boat, `SOS from ${p.boat} (${why}) at ${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}, ${describeRoute(p)}`);
    }
    boat.status = "sos";
    boat.trigger = p.trigger;
    boat.lastSos = now;
    boat.acked = true;   // the uploading phone now tells its ESP32 to send the LoRa ACK
  } else {
    addEvent("position", p.boat, `Position from ${p.boat}, ${describeRoute(p)}`);
  }

  broadcast({ kind: "boat", boat: publicBoat(boat) });
}

// An SOS stays active until no SOS packet has arrived for SOS_HOLD_MIN minutes,
// even if the boat goes silent - silence is exactly when rescuers need it.
function expireSos() {
  const now = Date.now();
  for (const boat of boats.values()) {
    if (boat.status === "sos" && now - boat.lastSos > SOS_HOLD_MS) {
      boat.status = "normal";
      boat.trigger = "-";
      boat.sosSince = null;
      boat.acked = false;
      addEvent("resolved", boat.id, `SOS from ${boat.id} cleared after ${SOS_HOLD_MS / 60000} min without new SOS packets`);
      broadcast({ kind: "boat", boat: publicBoat(boat) });
    }
  }
  for (const [key, time] of seen) if (now - time > 60 * 60 * 1000) seen.delete(key);
}
setInterval(expireSos, 5000);

function publicBoat(b) {
  return {
    id: b.id, lat: b.lat, lon: b.lon, status: b.status, trigger: b.trigger,
    lastSeen: b.lastSeen, sosSince: b.sosSince, acked: b.acked, hops: b.hops,
    via: b.via, gateway: b.gateway, rssi: b.rssi, msgId: b.msgId, trail: b.trail,
  };
}

function snapshot() {
  return { serverTime: Date.now(), boats: [...boats.values()].map(publicBoat), events };
}

// ---------- validation ----------
const ID = /^[A-Za-z0-9-]{1,8}$/;

function parsePacket(body) {
  const p = {
    type: body.type, boat: body.boat, msgId: Number(body.msgId),
    lat: Number(body.lat), lon: Number(body.lon), time: Number(body.time) || 0,
    hops: Number(body.hops) || 0, trigger: body.trigger || "-",
    via: body.via || body.boat, gateway: body.gateway, rssi: Number(body.rssi) || 0,
  };
  if (p.type !== "P" && p.type !== "S") return "type must be P or S";
  if (!ID.test(p.boat || "") || !ID.test(p.via) || !ID.test(p.gateway || "")) return "bad boat/via/gateway id";
  if (!ID.test(p.trigger)) return "bad trigger";
  if (!Number.isInteger(p.msgId) || p.msgId < 0) return "bad msgId";
  if (!(Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180)) return "bad lat/lon";
  return p;
}

function validKey(req) {
  const given = Buffer.from(String(req.headers["x-api-key"] || ""));
  const expected = Buffer.from(API_KEY);
  return API_KEY !== "" && given.length === expected.length && crypto.timingSafeEqual(given, expected);
}

// ---------- HTTP ----------
function send(res, status, obj) {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "no-store",
  });
  res.end(JSON.stringify(obj));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error("body too large")); req.destroy(); }
      else chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString() || "{}")); }
      catch { reject(new Error("invalid JSON")); }
    });
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const path = req.url.split("?")[0];

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, X-API-Key",
    });
    return res.end();
  }

  if (req.method === "GET" && (path === "/" || path === "/health")) {
    return send(res, 200, { service: "SeaMesh backend", ok: true, boats: boats.size });
  }

  if (req.method === "GET" && path === "/api/boats") return send(res, 200, snapshot());

  if (req.method === "POST" && (path === "/api/alerts" || path === "/api/reset")) {
    if (!API_KEY) return send(res, 503, { error: "server has no API_KEY configured" });
    if (!validKey(req)) return send(res, 401, { error: "invalid API key" });

    if (path === "/api/reset") {
      boats.clear();
      seen.clear();
      events = [];
      broadcast({ kind: "snapshot", ...snapshot() });
      return send(res, 200, { ok: true });
    }

    let body;
    try { body = await readJson(req); }
    catch (e) { return send(res, 400, { error: e.message }); }

    const p = parsePacket(body);
    if (typeof p === "string") return send(res, 400, { error: p });

    // the same packet can be uploaded by several gateway boats - keep the first
    const key = `${p.type}:${p.boat}:${p.msgId}`;
    if (seen.has(key)) return send(res, 200, { ok: true, duplicate: true });
    seen.set(key, Date.now());

    applyPacket(p);
    console.log(`${p.type === "S" ? "SOS" : "POS"} ${p.boat} #${p.msgId} ${p.lat},${p.lon} via ${p.via} gw ${p.gateway} rssi ${p.rssi}`);
    return send(res, 200, { ok: true });
  }

  send(res, 404, { error: "not found" });
});

// ---------- WebSocket ----------
const wss = new WebSocketServer({ server, path: "/live" });

function broadcast(msg) {
  const data = JSON.stringify(msg);
  for (const client of wss.clients) if (client.readyState === 1) client.send(data);
}

wss.on("connection", (ws) => {
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });
  ws.send(JSON.stringify({ kind: "snapshot", ...snapshot() }));
});

// Ping every 30 s so idle connections aren't closed by proxies, and drop dead ones
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

server.listen(PORT, () => {
  console.log(`SeaMesh backend listening on port ${PORT}`);
  if (!API_KEY) console.warn("WARNING: API_KEY is not set - uploads will be refused");
});
