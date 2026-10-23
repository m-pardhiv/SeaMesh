// SeaMesh mesh node - ESP32 + Ra-02 (SX1278, 433 MHz)
//
// The same firmware runs on every boat. Only the settings block changes.
//  - Boat WITHOUT signal: broadcasts its own position/SOS and relays others.
//  - Boat WITH signal (joined to the phone hotspot = "cellular"): uploads
//    every packet it hears to the dashboard and ACKs SOS messages.
//
// Packet (text, ~45 bytes):
//   type,origin,msgId,lat,lon,time,hop,trigger,via
//   type: P = position, S = SOS, A = ack     via = last boat that transmitted it
//
// Serial commands (115200): sos, cancel, pos, offline, online, status

#include <SPI.h>
#include <LoRa.h>
#include <WiFi.h>
#include <WebServer.h>

// ================= Per-boat settings =================
#define BOAT_ID   "A"          // A, B, C ... (letters/numbers only)
#define WIFI_SSID ""           // phone hotspot = this boat's "cellular". "" = no signal
#define WIFI_PASS ""
#define BASE_LAT  9.9500       // simulated GPS start position (off Kochi)
#define BASE_LON  75.9500
#define USE_WATER_SENSOR true  // two bare wires on GPIO4 (touch pin)
// =====================================================

// Radio - minimum power, because there is no antenna
#define FREQ      433E6
#define TX_POWER  2
#define SYNC_WORD 0x5E
#define MAX_HOPS  4

// Ra-02 -> ESP32 wiring
#define PIN_SS   5
#define PIN_RST  14
#define PIN_DIO0 26
#define PIN_SCK  18
#define PIN_MISO 19
#define PIN_MOSI 23

#define PIN_SOS_BTN 27         // push button to GND
#define PIN_WATER   4          // touch pin T0
#define LED_PIN     2

#define POS_INTERVAL    20000  // routine position update
#define SOS_INTERVAL    4000   // SOS repeat
#define SOS_MAX_REPEATS 15

struct Packet {
  char type;
  String origin;
  uint16_t msgId;
  float lat, lon;
  uint32_t t;
  uint8_t hop;
  String trig;
  String via;
};

// ---------- state ----------
uint16_t nextMsgId = 1;
float curLat = BASE_LAT, curLon = BASE_LON;
unsigned long lastPos = 0;
bool forceOffline = false;
bool serverStarted = false;

bool sosActive = false;
String sosTrig = "";
int sosRepeats = 0;
unsigned long lastSos = 0;

uint32_t waterBaseline = 0;
int wetCount = 0;
bool lastBtn = HIGH;

// duplicate filter
#define SEEN_SIZE 40
String seen[SEEN_SIZE];
int seenPos = 0;

// relay queue
#define QUEUE_SIZE 6
struct Queued { bool used; Packet p; unsigned long due; };
Queued queue[QUEUE_SIZE];

// dashboard (only used when this boat is online)
struct BoatInfo {
  String id; float lat, lon; char type; int hops; String via;
  uint16_t msgId; unsigned long seenAt; int rssi; String trig;
};
#define MAX_BOATS 8
BoatInfo boats[MAX_BOATS];
int boatCount = 0;
#define MAX_EVENTS 12
String events[MAX_EVENTS];
int eventCount = 0;

WebServer server(80);

// ---------- helpers ----------
void blink() {
  digitalWrite(LED_PIN, HIGH);
  delay(30);
  digitalWrite(LED_PIN, sosActive ? HIGH : LOW);
}

bool online() {
  return strlen(WIFI_SSID) > 0 && !forceOffline && WiFi.status() == WL_CONNECTED;
}

uint32_t uptimeSec() { return millis() / 1000; }

bool isSafeField(const String& s) {
  if (s.length() == 0 || s.length() > 8) return false;
  for (unsigned int i = 0; i < s.length(); i++) {
    if (!isAlphaNumeric(s[i]) && s[i] != '-') return false;
  }
  return true;
}

String encode(const Packet& p) {
  return String(p.type) + "," + p.origin + "," + String(p.msgId) + "," +
         String(p.lat, 5) + "," + String(p.lon, 5) + "," + String(p.t) + "," +
         String(p.hop) + "," + p.trig + "," + p.via;
}

bool decode(const String& s, Packet& p) {
  String f[9];
  int idx = 0, start = 0;
  for (unsigned int i = 0; i <= s.length() && idx < 9; i++) {
    if (i == s.length() || s[i] == ',') {
      f[idx++] = s.substring(start, i);
      start = i + 1;
    }
  }
  if (idx != 9 || f[0].length() != 1) return false;
  p.type = f[0][0];
  if (p.type != 'P' && p.type != 'S' && p.type != 'A') return false;
  if (!isSafeField(f[1]) || !isSafeField(f[7]) || !isSafeField(f[8])) return false;
  p.origin = f[1];
  p.msgId = f[2].toInt();
  p.lat = f[3].toFloat();
  p.lon = f[4].toFloat();
  p.t = f[5].toInt();
  p.hop = f[6].toInt();
  p.trig = f[7];
  p.via = f[8];
  return true;
}

String keyOf(const Packet& p) {
  return String(p.type) + p.origin + ":" + String(p.msgId);
}

bool alreadySeen(const String& key) {
  for (int i = 0; i < SEEN_SIZE; i++) if (seen[i] == key) return true;
  return false;
}

void remember(const String& key) {
  seen[seenPos] = key;
  seenPos = (seenPos + 1) % SEEN_SIZE;
}

void addEvent(const String& e) {
  String line = String(uptimeSec()) + "s  " + e;
  Serial.println("EVENT " + line);
  if (eventCount < MAX_EVENTS) {
    events[eventCount++] = line;
  } else {
    for (int i = 1; i < MAX_EVENTS; i++) events[i - 1] = events[i];
    events[MAX_EVENTS - 1] = line;
  }
}

// ---------- radio ----------
byte readChipVersion() {
  pinMode(PIN_RST, OUTPUT);
  digitalWrite(PIN_RST, LOW);
  delay(10);
  digitalWrite(PIN_RST, HIGH);
  delay(10);
  pinMode(PIN_SS, OUTPUT);
  digitalWrite(PIN_SS, HIGH);
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  digitalWrite(PIN_SS, LOW);
  SPI.transfer(0x42 & 0x7F);
  byte v = SPI.transfer(0x00);
  digitalWrite(PIN_SS, HIGH);
  SPI.endTransaction();
  return v;
}

void transmit(Packet p) {
  p.via = BOAT_ID;
  String s = encode(p);
  LoRa.beginPacket();
  LoRa.print(s);
  LoRa.endPacket();
  Serial.println("TX    " + s);
  blink();
}

void enqueue(const Packet& p) {
  // SOS and ACKs go out almost immediately, routine updates wait a little
  // (random jitter so two relays don't transmit on top of each other)
  unsigned long delayMs = (p.type == 'P') ? random(200, 800) : random(20, 80);
  for (int i = 0; i < QUEUE_SIZE; i++) {
    if (!queue[i].used) {
      queue[i] = { true, p, millis() + delayMs };
      return;
    }
  }
  Serial.println("WARN  relay queue full, dropped " + keyOf(p));
}

void processQueue() {
  int pick = -1;
  for (int i = 0; i < QUEUE_SIZE; i++) {
    if (!queue[i].used || millis() < queue[i].due) continue;
    if (pick < 0 || (queue[i].p.type != 'P' && queue[pick].p.type == 'P')) pick = i;
  }
  if (pick >= 0) {
    queue[pick].used = false;
    transmit(queue[pick].p);
  }
}

// ---------- dashboard ----------
void upload(const Packet& p, int rssi) {
  int i = 0;
  while (i < boatCount && boats[i].id != p.origin) i++;
  if (i == boatCount) {
    if (boatCount == MAX_BOATS) return;
    boatCount++;
  }
  // an SOS stays on the map as SOS until that boat's SOS is over
  char type = p.type;
  if (type == 'P' && boats[i].id == p.origin && boats[i].type == 'S' &&
      millis() - boats[i].seenAt < 60000) type = 'S';
  boats[i] = { p.origin, p.lat, p.lon, type, p.hop, p.via, p.msgId, millis(), rssi, p.trig };

  String how = (p.origin == BOAT_ID) ? "own position"
             : (p.hop == 0 ? "direct" : String(p.hop) + " relay hop(s), last via " + p.via);
  addEvent(String(p.type == 'S' ? "SOS" : "Position") + " from boat " + p.origin +
           " (" + how + ")" + (rssi != 0 ? ", RSSI " + String(rssi) : ""));
}

const char PAGE[] PROGMEM = R"rawliteral(<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SeaMesh Shore Dashboard</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0b1d2e;color:#e6eef5}
header{padding:10px 14px;background:#102a43;display:flex;justify-content:space-between;align-items:center}
h1{font-size:18px;margin:0}#gw{font-size:13px;opacity:.8}
#sos{display:none;background:#c62828;color:#fff;padding:10px 14px;font-weight:700;animation:f 1s infinite}
@keyframes f{50%{opacity:.6}}
#map{height:55vh}#side{padding:10px 14px}
.boat{background:#16324f;border-radius:8px;padding:8px 10px;margin-bottom:8px;font-size:14px}
.boat.S{border-left:5px solid #ef5350}.boat.P{border-left:5px solid #42a5f5}
#log{font-family:monospace;font-size:12px;opacity:.85;white-space:pre-wrap}
</style></head><body>
<header><h1>SeaMesh Shore Dashboard</h1><span id="gw"></span></header>
<div id="sos"></div><div id="map"></div>
<div id="side"><div id="list"></div><h3>Event log</h3><div id="log"></div></div>
<script>
var map=null,markers={};
if(window.L){map=L.map('map').setView([9.96,76.05],10);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'&copy; OpenStreetMap'}).addTo(map);}
else{document.getElementById('map').innerHTML='<p style="padding:14px">Map needs internet - showing list only.</p>';}
function route(b){return b.hops==0?(b.via==b.id?'direct':'via '+b.via):b.hops+' relay hop(s), last via '+b.via;}
function tick(){fetch('/data').then(r=>r.json()).then(d=>{
 document.getElementById('gw').textContent='Gateway: boat '+d.gw+(d.online?' (online)':' (offline)');
 var sos=d.boats.filter(b=>b.type=='S');var s=document.getElementById('sos');
 s.style.display=sos.length?'block':'none';
 s.textContent=sos.map(b=>'SOS - Boat '+b.id+' at '+b.lat.toFixed(4)+', '+b.lon.toFixed(4)+' ('+b.trig+') received '+route(b)).join(' | ');
 document.getElementById('list').innerHTML=d.boats.map(b=>'<div class="boat '+b.type+'"><b>Boat '+b.id+'</b> - '+(b.type=='S'?'SOS ('+b.trig+')':'normal')+
  '<br>'+b.lat.toFixed(5)+', '+b.lon.toFixed(5)+'<br>'+route(b)+' &middot; msg #'+b.msg+' &middot; '+b.age+'s ago'+(b.rssi?' &middot; RSSI '+b.rssi:'')+'</div>').join('')||'No boats heard yet.';
 document.getElementById('log').textContent=d.events.slice().reverse().join('\n');
 if(map)d.boats.forEach(b=>{var c=b.type=='S'?'#ef5350':'#42a5f5';
  if(!markers[b.id])markers[b.id]=L.circleMarker([b.lat,b.lon],{radius:10}).addTo(map);
  markers[b.id].setLatLng([b.lat,b.lon]).setStyle({color:c,fillColor:c,fillOpacity:.8}).bindTooltip('Boat '+b.id+(b.type=='S'?' SOS':''),{permanent:true});});
}).catch(()=>{});}
tick();setInterval(tick,2000);
</script></body></html>)rawliteral";

void handleData() {
  String j = "{\"gw\":\"" BOAT_ID "\",\"online\":" + String(online() ? "true" : "false") + ",\"boats\":[";
  for (int i = 0; i < boatCount; i++) {
    BoatInfo& b = boats[i];
    if (i) j += ",";
    j += "{\"id\":\"" + b.id + "\",\"lat\":" + String(b.lat, 5) + ",\"lon\":" + String(b.lon, 5) +
         ",\"type\":\"" + String(b.type) + "\",\"hops\":" + String(b.hops) + ",\"via\":\"" + b.via +
         "\",\"msg\":" + String(b.msgId) + ",\"age\":" + String((millis() - b.seenAt) / 1000) +
         ",\"rssi\":" + String(b.rssi) + ",\"trig\":\"" + b.trig + "\"}";
  }
  j += "],\"events\":[";
  for (int i = 0; i < eventCount; i++) {
    if (i) j += ",";
    j += "\"" + events[i] + "\"";
  }
  j += "]}";
  server.send(200, "application/json", j);
}

// ---------- own messages ----------
Packet makeOwn(char type, const String& trig) {
  Packet p;
  p.type = type;
  p.origin = BOAT_ID;
  p.msgId = nextMsgId++;
  p.lat = curLat;
  p.lon = curLon;
  p.t = uptimeSec();
  p.hop = 0;
  p.trig = trig;
  p.via = BOAT_ID;
  return p;
}

void sendOwn(const Packet& p) {
  remember(keyOf(p));
  if (online()) {
    upload(p, 0);          // we have signal ourselves - straight to the dashboard
    if (p.type == 'S') sosActive = false;
  } else {
    transmit(p);
  }
}

// Each repeat is a new message id, so a repeat that follows a lost packet
// or a lost ACK is relayed and ACKed again instead of dropped as a duplicate.
void sendSos() {
  sosRepeats++;
  lastSos = millis();
  Serial.println("SOS   attempt " + String(sosRepeats) + "/" + String(SOS_MAX_REPEATS));
  sendOwn(makeOwn('S', sosTrig));
}

void triggerSos(const String& trig) {
  if (sosActive) return;
  sosActive = true;
  sosTrig = trig;
  sosRepeats = 0;
  digitalWrite(LED_PIN, HIGH);
  Serial.println("!!!   SOS triggered by " + trig);
  sendSos();
}

void cancelSos(const String& why) {
  if (!sosActive) return;
  sosActive = false;
  digitalWrite(LED_PIN, LOW);
  Serial.println("SOS   stopped: " + why);
}

// ---------- incoming ----------
void onPacket(Packet p, int rssi) {
  String key = keyOf(p);
  if (alreadySeen(key)) {
    Serial.println("DUP   " + key + " dropped");
    return;
  }
  remember(key);
  Serial.println("RX    " + encode(p) + "  RSSI " + String(rssi));

  // ACK for one of our own SOS messages
  if (p.type == 'A' && p.origin == BOAT_ID) {
    if (sosActive) cancelSos("ACK from shore via boat " + p.via + " - SOS delivered!");
    return;
  }
  if (p.origin == BOAT_ID) return;   // our own packet echoed back by a relay

  if (online() && p.type != 'A') {
    upload(p, rssi);
    if (p.type == 'S') {
      Packet ack = p;
      ack.type = 'A';
      ack.hop = 0;
      ack.trig = "ACK";
      remember(keyOf(ack));
      enqueue(ack);
    }
    return;
  }

  if (p.hop + 1 >= MAX_HOPS) {
    Serial.println("DROP  " + key + " hop limit reached");
    return;
  }
  p.hop++;
  Serial.println("RELAY " + key + " (hop " + String(p.hop) + ")");
  enqueue(p);
}

// ---------- inputs ----------
void checkInputs() {
  bool btn = digitalRead(PIN_SOS_BTN);
  if (btn == LOW && lastBtn == HIGH) {
    delay(30);
    if (digitalRead(PIN_SOS_BTN) == LOW) triggerSos("BTN");
  }
  lastBtn = btn;

  static unsigned long lastWater = 0;
  if (USE_WATER_SENSOR && waterBaseline > 0 && millis() - lastWater > 500) {
    lastWater = millis();
    uint32_t v = touchRead(PIN_WATER);
    wetCount = (v < waterBaseline * 6 / 10) ? wetCount + 1 : 0;
    if (wetCount == 3) triggerSos("WATER");
  }

  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd == "sos") triggerSos("BTN");
    else if (cmd == "cancel") cancelSos("cancelled by user");
    else if (cmd == "pos") sendOwn(makeOwn('P', "-"));
    else if (cmd == "offline") { forceOffline = true; Serial.println("MODE  forced offline (no cellular)"); }
    else if (cmd == "online") { forceOffline = false; Serial.println("MODE  online allowed"); }
    else if (cmd == "status") {
      Serial.println("STAT  boat " BOAT_ID ", " + String(online() ? "ONLINE (gateway)" : "OFFLINE (relay)") +
                     ", SOS " + String(sosActive ? "active" : "off") +
                     (WiFi.status() == WL_CONNECTED ? ", IP " + WiFi.localIP().toString() : ""));
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  pinMode(LED_PIN, OUTPUT);
  pinMode(PIN_SOS_BTN, INPUT_PULLUP);
  randomSeed(esp_random());

  Serial.println();
  Serial.println("=== SeaMesh node " BOAT_ID " ===");

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_SS);
  byte v = readChipVersion();
  Serial.println("Chip version: 0x" + String(v, HEX));
  if (v != 0x12) {
    Serial.println("[FAIL] Ra-02 not found - check wiring");
    while (1) { digitalWrite(LED_PIN, !digitalRead(LED_PIN)); delay(150); }
  }
  LoRa.setPins(PIN_SS, PIN_RST, PIN_DIO0);
  if (!LoRa.begin(FREQ)) {
    Serial.println("[FAIL] LoRa.begin() failed");
    while (1) { digitalWrite(LED_PIN, !digitalRead(LED_PIN)); delay(150); }
  }
  LoRa.setTxPower(TX_POWER);
  LoRa.setSyncWord(SYNC_WORD);
  LoRa.enableCrc();
  Serial.println("[OK] LoRa 433 MHz, TX power " + String(TX_POWER) + " dBm (no antenna mode)");

  if (USE_WATER_SENSOR) {
    uint32_t sum = 0;
    for (int i = 0; i < 10; i++) { sum += touchRead(PIN_WATER); delay(20); }
    waterBaseline = sum / 10;
    Serial.println("[OK] Water sensor baseline " + String(waterBaseline));
  }

  if (strlen(WIFI_SSID) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.println("[..] Joining hotspot '" WIFI_SSID "' (this boat's cellular link)");
  } else {
    WiFi.mode(WIFI_OFF);
    Serial.println("[--] No hotspot set - this boat has no signal, will relay over LoRa");
  }

  server.on("/", []() { server.send_P(200, "text/html", PAGE); });
  server.on("/data", handleData);
  Serial.println("Commands: sos, cancel, pos, offline, online, status");
  Serial.println("--------------------------");
}

void loop() {
  // start the dashboard once the hotspot connection is up
  if (!serverStarted && WiFi.status() == WL_CONNECTED) {
    server.begin();
    serverStarted = true;
    Serial.println("[OK] Online - dashboard at http://" + WiFi.localIP().toString());
    addEvent("Gateway boat " BOAT_ID " online");
  }
  if (serverStarted) server.handleClient();

  int size = LoRa.parsePacket();
  if (size) {
    String s;
    while (LoRa.available()) s += (char)LoRa.read();
    Packet p;
    if (decode(s, p)) onPacket(p, LoRa.packetRssi());
    else Serial.println("BAD   ignored: " + s);
    blink();
  }

  checkInputs();
  processQueue();

  if (sosActive && millis() - lastSos > SOS_INTERVAL) {
    if (sosRepeats >= SOS_MAX_REPEATS) cancelSos("no ACK after " + String(SOS_MAX_REPEATS) + " tries");
    else sendSos();
  }

  if (!sosActive && millis() - lastPos > POS_INTERVAL) {
    lastPos = millis();
    curLat += random(-20, 21) / 100000.0;   // simulated drift
    curLon += random(-20, 21) / 100000.0;
    sendOwn(makeOwn('P', "-"));
  }
}
