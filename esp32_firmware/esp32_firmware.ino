// SeaMesh mesh node - ESP32 + Ra-02 (SX1278, 433 MHz)
//
// Identical firmware for every boat - nothing to edit per board.
//  - Boat ID comes from the chip's MAC address (e.g. "3F2A"); BLE name "SeaMesh-3F2A".
//  - The boat's GPS + internet link is a phone running the SeaMesh app over BLE
//    (demo stand-in for the boat's existing GPS/GPRS tracker).
//  - Phone offline (or not connected): broadcast own position/SOS over LoRa and
//    relay packets from other boats.
//  - Phone online: hand every packet heard to the phone for upload; once the phone
//    confirms the upload, send a LoRa ACK back to the boat that raised the SOS.
//
// LoRa packet (text):  type,origin,msgId,lat,lon,time,hop,trigger,via
//   type: P = position, S = SOS, A = ack     via = last boat that transmitted it
//   time: unix time of the GPS fix (0 = no phone fix, simulated position)
//
// BLE service 6e5a0001-5ea3-4e5a-9c1d-2b7f0a4c8e10
//   GPS      ...0002 WRITE   "lat,lon,accuracy_m,fix_unix_time"
//   CONTROL  ...0003 WRITE   "NET,1" / "NET,0" / "ACK,<origin>,<msgId>"
//   PACKETS  ...0004 NOTIFY  "type,origin,msgId,lat,lon,time,hop,trigger,via,gateway,rssi"
//
// Serial commands (115200): sos, cancel, pos, offline, online, status

#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>

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

// Sensors are optional: with nothing wired, both pins are held HIGH by the
// internal pull-ups and read as "no emergency". A missing sensor is never a fault.
#define PIN_SOS_BTN 27         // push button to GND
#define PIN_WATER   33         // bare wire; second bare wire to GND. Water bridging
                               // them pulls the pin LOW (use salty water for the demo)
#define WATER_WET_MS 2000      // must stay wet this long before it counts
#define LED_PIN     2

#define POS_INTERVAL    20000  // routine position update
#define SOS_INTERVAL    4000   // SOS repeat
#define SOS_MAX_REPEATS 15

#define SERVICE_UUID "6e5a0001-5ea3-4e5a-9c1d-2b7f0a4c8e10"
#define GPS_UUID     "6e5a0002-5ea3-4e5a-9c1d-2b7f0a4c8e10"
#define CONTROL_UUID "6e5a0003-5ea3-4e5a-9c1d-2b7f0a4c8e10"
#define PACKETS_UUID "6e5a0004-5ea3-4e5a-9c1d-2b7f0a4c8e10"

struct Packet {
  char type;
  String origin;
  uint16_t msgId;
  double lat, lon;
  uint32_t t;
  uint8_t hop;
  String trig;
  String via;
};

// ---------- state ----------
String boatId;                 // from the chip MAC, e.g. "3F2A"
uint16_t nextMsgId = 1;

double simLat, simLon;         // fallback position when the phone has no GPS fix
double fixLat = 0, fixLon = 0;
uint32_t fixTime = 0;          // unix time of the phone's last fix, 0 = none
unsigned long fixReceivedAt = 0;

bool bleConnected = false;
bool phoneOnline = false;      // the phone reported working internet
bool forceOffline = false;     // serial "offline" command, for the demo
unsigned long lastPos = 0;

bool sosActive = false;
uint16_t sosFirstMsgId = 0;    // msgId of the first packet of the current SOS
String sosTrig = "";
int sosRepeats = 0;
unsigned long lastSos = 0;

unsigned long wetSince = 0;
bool lastBtn = HIGH;

// duplicate filter
#define SEEN_SIZE 40
String seen[SEEN_SIZE];
int seenPos = 0;

// LoRa relay queue
#define QUEUE_SIZE 6
struct Queued { bool used; Packet p; unsigned long due; };
Queued queue[QUEUE_SIZE];

// SOS packets handed to the phone, waiting for its upload confirmation
#define PENDING_SIZE 8
Packet pending[PENDING_SIZE];
bool pendingUsed[PENDING_SIZE];

// BLE writes arrive on the Bluetooth task; they are queued and handled in loop()
#define BLE_MSG_LEN 72
QueueHandle_t bleInbox;
BLECharacteristic* packetsChar;

// ---------- helpers ----------
void blink() {
  digitalWrite(LED_PIN, HIGH);
  delay(30);
  digitalWrite(LED_PIN, sosActive ? HIGH : LOW);
}

bool online() {
  return bleConnected && phoneOnline && !forceOffline;
}

bool isSafeField(const String& s) {
  if (s.length() == 0 || s.length() > 8) return false;
  for (unsigned int i = 0; i < s.length(); i++) {
    if (!isAlphaNumeric(s[i]) && s[i] != '-') return false;
  }
  return true;
}

String encode(const Packet& p) {
  return String(p.type) + "," + p.origin + "," + String(p.msgId) + "," +
         String(p.lat, 6) + "," + String(p.lon, 6) + "," + String(p.t) + "," +
         String(p.hop) + "," + p.trig + "," + p.via;
}

// Split s on commas into at most n fields; returns the number of fields found
int splitFields(const String& s, String* f, int n) {
  int idx = 0, start = 0;
  for (unsigned int i = 0; i <= s.length() && idx < n; i++) {
    if (i == s.length() || s[i] == ',') {
      f[idx++] = s.substring(start, i);
      start = i + 1;
    }
  }
  return idx;
}

bool decode(const String& s, Packet& p) {
  String f[9];
  if (splitFields(s, f, 9) != 9 || f[0].length() != 1) return false;
  p.type = f[0][0];
  if (p.type != 'P' && p.type != 'S' && p.type != 'A') return false;
  if (!isSafeField(f[1]) || !isSafeField(f[7]) || !isSafeField(f[8])) return false;
  p.origin = f[1];
  p.msgId = f[2].toInt();
  p.lat = f[3].toDouble();
  p.lon = f[4].toDouble();
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
  p.via = boatId;
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

// ---------- phone link (BLE) ----------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { bleConnected = true; }
  void onDisconnect(BLEServer*) override {
    bleConnected = false;
    phoneOnline = false;
    BLEDevice::startAdvertising();
  }
};

// tag 'G' = GPS write, 'C' = CONTROL write
class WriteCallbacks : public BLECharacteristicCallbacks {
  char tag;
 public:
  WriteCallbacks(char t) : tag(t) {}
  void onWrite(BLECharacteristic* c) override {
    char msg[BLE_MSG_LEN];
    String v = c->getValue();
    msg[0] = tag;
    strncpy(msg + 1, v.c_str(), BLE_MSG_LEN - 2);
    msg[BLE_MSG_LEN - 1] = '\0';
    xQueueSend(bleInbox, msg, 0);
  }
};

void setupBle() {
  bleInbox = xQueueCreate(10, BLE_MSG_LEN);
  BLEDevice::init(("SeaMesh-" + boatId).c_str());
  BLEDevice::setMTU(185);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(SERVICE_UUID);

  service->createCharacteristic(GPS_UUID, BLECharacteristic::PROPERTY_WRITE)
         ->setCallbacks(new WriteCallbacks('G'));
  service->createCharacteristic(CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE)
         ->setCallbacks(new WriteCallbacks('C'));
  packetsChar = service->createCharacteristic(PACKETS_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  packetsChar->addDescriptor(new BLE2902());

  service->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// Hand a packet to the phone for upload
void notifyPhone(const Packet& p, int rssi) {
  String s = encode(p) + "," + boatId + "," + String(rssi);
  packetsChar->setValue((uint8_t*)s.c_str(), s.length());
  packetsChar->notify();
  Serial.println("UP    " + s);

  if (p.type == 'S') {
    for (int i = 0; i < PENDING_SIZE; i++) {
      if (!pendingUsed[i]) { pending[i] = p; pendingUsed[i] = true; return; }
    }
    pendingUsed[0] = true;   // full: overwrite the oldest slot
    pending[0] = p;
  }
}

void cancelSos(const String& why);

// The phone uploaded origin:msgId - for an SOS, tell that boat over LoRa
void onUploadAck(const String& origin, uint16_t msgId) {
  if (origin == boatId) {
    // only an SOS packet's upload ends the SOS, not a late ACK for an older position
    bool isSosPacket = (uint16_t)(msgId - sosFirstMsgId) <= SOS_MAX_REPEATS;
    if (sosActive && isSosPacket) cancelSos("uploaded by our own phone - SOS delivered!");
    return;
  }
  for (int i = 0; i < PENDING_SIZE; i++) {
    if (pendingUsed[i] && pending[i].origin == origin && pending[i].msgId == msgId) {
      pendingUsed[i] = false;
      Packet ack = pending[i];
      ack.type = 'A';
      ack.hop = 0;
      ack.trig = "ACK";
      remember(keyOf(ack));
      enqueue(ack);
      Serial.println("ACK   " + origin + ":" + String(msgId) + " uploaded - sending LoRa ACK");
      return;
    }
  }
}

void handleBleInbox() {
  char msg[BLE_MSG_LEN];
  while (xQueueReceive(bleInbox, msg, 0) == pdTRUE) {
    String body = String(msg + 1);
    body.trim();
    String f[4];
    int n = splitFields(body, f, 4);

    if (msg[0] == 'G' && n == 4) {
      double lat = f[0].toDouble(), lon = f[1].toDouble();
      if (lat == 0 && lon == 0) continue;
      fixLat = lat;
      fixLon = lon;
      fixTime = f[3].toInt();
      fixReceivedAt = millis();
    } else if (msg[0] == 'C' && n == 2 && f[0] == "NET") {
      bool now = f[1] == "1";
      if (now != phoneOnline) {
        phoneOnline = now;
        Serial.println(String("PHONE ") + (now ? "online - this boat forwards to the internet"
                                               : "offline - relaying over LoRa"));
      }
    } else if (msg[0] == 'C' && n == 3 && f[0] == "ACK") {
      onUploadAck(f[1], f[2].toInt());
    }
  }
}

// ---------- own messages ----------
// Fallback position at sea off Kochi, different for each chip, used until the
// phone sends a GPS fix
void initSimPosition() {
  uint32_t h = (uint32_t)ESP.getEfuseMac();
  simLat = 9.9300 + (h % 600) / 10000.0;          // 9.93 - 9.99
  simLon = 75.9500 + ((h >> 10) % 2000) / 10000.0; // 75.95 - 76.15
}

Packet makeOwn(char type, const String& trig) {
  Packet p;
  p.type = type;
  p.origin = boatId;
  p.msgId = nextMsgId++;
  bool haveFix = fixReceivedAt != 0;
  p.lat = haveFix ? fixLat : simLat;
  p.lon = haveFix ? fixLon : simLon;
  p.t = haveFix ? fixTime : 0;
  p.hop = 0;
  p.trig = trig;
  p.via = boatId;
  return p;
}

void sendOwn(const Packet& p) {
  remember(keyOf(p));
  if (online()) notifyPhone(p, 0);   // our own phone has internet - upload directly
  else transmit(p);
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
  sosFirstMsgId = nextMsgId;
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

// ---------- incoming LoRa ----------
void onPacket(Packet p, int rssi) {
  String key = keyOf(p);
  if (alreadySeen(key)) {
    Serial.println("DUP   " + key + " dropped");
    return;
  }
  remember(key);
  Serial.println("RX    " + encode(p) + "  RSSI " + String(rssi));

  // ACK for one of our own SOS messages
  if (p.type == 'A' && p.origin == boatId) {
    if (sosActive) cancelSos("ACK from shore via boat " + p.via + " - SOS delivered!");
    return;
  }
  if (p.origin == boatId) return;   // our own packet echoed back by a relay

  if (online() && p.type != 'A') {
    notifyPhone(p, rssi);
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
void printStatus() {
  String gps = fixReceivedAt ? String(fixLat, 6) + ", " + String(fixLon, 6) +
                               " (" + String((millis() - fixReceivedAt) / 1000) + " s ago)"
                             : "no phone fix - simulated " + String(simLat, 6) + ", " + String(simLon, 6);
  Serial.println("STAT  boat " + boatId + " | phone " + String(bleConnected ? "connected" : "not connected") +
                 " | " + String(online() ? "ONLINE (gateway)" : "OFFLINE (relay)") +
                 " | SOS " + String(sosActive ? "active" : "off") + " | GPS " + gps);
}

void checkInputs() {
  bool btn = digitalRead(PIN_SOS_BTN);
  if (btn == LOW && lastBtn == HIGH) {
    delay(30);
    if (digitalRead(PIN_SOS_BTN) == LOW) triggerSos("BTN");
  }
  lastBtn = btn;

  if (digitalRead(PIN_WATER) == LOW) {
    if (wetSince == 0) wetSince = millis();
    else if (millis() - wetSince > WATER_WET_MS) triggerSos("WATER");
  } else {
    wetSince = 0;
  }

  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd == "sos") triggerSos("BTN");
    else if (cmd == "cancel") cancelSos("cancelled by user");
    else if (cmd == "pos") sendOwn(makeOwn('P', "-"));
    else if (cmd == "offline") { forceOffline = true; Serial.println("MODE  forced offline (no internet)"); }
    else if (cmd == "online") { forceOffline = false; Serial.println("MODE  online allowed"); }
    else if (cmd == "status") printStatus();
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  pinMode(LED_PIN, OUTPUT);
  pinMode(PIN_SOS_BTN, INPUT_PULLUP);
  pinMode(PIN_WATER, INPUT_PULLUP);
  randomSeed(esp_random());
  // Random start, so message ids after a reboot don't repeat ones the phone app and
  // backend already saw (they drop repeats as duplicates)
  nextMsgId = random(1, 60000);

  char id[5];
  uint64_t mac = ESP.getEfuseMac();   // bytes are stored LSB first
  snprintf(id, sizeof(id), "%02X%02X", (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));
  boatId = id;
  initSimPosition();

  Serial.println();
  Serial.println("=== SeaMesh node " + boatId + " ===");

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
  Serial.println("[OK] Sensors optional: SOS button D27, water D33 (nothing wired = no alert)");

  setupBle();
  Serial.println("[OK] Bluetooth: open the SeaMesh app and pick 'SeaMesh-" + boatId + "'");
  Serial.println("Commands: sos, cancel, pos, offline, online, status");
  Serial.println("--------------------------");
}

void loop() {
  handleBleInbox();

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
    if (fixReceivedAt == 0) {               // no phone GPS yet - drift the simulated position
      simLat += random(-20, 21) / 100000.0;
      simLon += random(-20, 21) / 100000.0;
    }
    sendOwn(makeOwn('P', "-"));
  }
}
