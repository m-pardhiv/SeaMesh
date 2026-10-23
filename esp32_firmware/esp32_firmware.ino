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
#include <esp_task_wdt.h>

// If loop() stops running for this long, the chip restarts itself (the phone
// reconnects on its own), instead of the node silently freezing
#define LOOP_WDT_MS 8000

// Radio. 10 dBm = the 10 mW license-free limit for 433 MHz in India. Without
// antennas, 2 dBm did not reach the other board even at 30 cm; keep transmissions
// short until antennas are fitted (reflected power stresses the PA, not heat).
#define FREQ      433E6
#define TX_POWER  10         // default dBm; change at runtime with "power N" (2-17)
#define SYNC_WORD 0x5E
#define SPREADING_FACTOR 9   // SF9: ~6 dB more sensitive than SF7, 51-byte packet ~0.37 s
#define MAX_HOPS  4

int txPower = TX_POWER;

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
// SOS button: an interrupt records when the pin goes LOW / HIGH, so a press is
// caught even while loop() is busy (a LoRa send blocks it for ~0.33 s, longer than
// some quick taps). loop() then decides what the press was.
#define BTN_MIN_MS 25               // shorter than this is contact noise, not a press
volatile bool btnPending = false;   // a press started and has not been handled yet
volatile unsigned long btnDownAt = 0, btnUpAt = 0;
void IRAM_ATTR onButtonEdge() {
  unsigned long t = millis();
  if (digitalRead(PIN_SOS_BTN) == LOW) { btnDownAt = t; btnPending = true; }
  else btnUpAt = t;
}

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

// LoRa's own CRC only covers the air link. Packets were seen corrupted while
// being read out of the radio over SPI ("B908" arriving as "B9r8"), so every
// packet on the air also carries "*XX", a CRC-8 of the text, checked after the
// read. The phone and backend never see it.
uint8_t crc8(const String& s) {
  uint8_t c = 0;
  for (unsigned int i = 0; i < s.length(); i++) {
    c ^= (uint8_t)s[i];
    for (int b = 0; b < 8; b++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1);
  }
  return c;
}

String withChecksum(const String& s) {
  char hex[3];
  snprintf(hex, sizeof(hex), "%02X", crc8(s));
  return s + "*" + hex;
}

// Returns the packet text without "*XX", or "" if the checksum is missing or wrong
String checkChecksum(const String& raw) {
  int star = raw.lastIndexOf('*');
  if (star < 0 || raw.length() - star != 3) return "";
  String body = raw.substring(0, star);
  return withChecksum(body) == raw ? body : "";
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

byte readRadioReg(byte addr) {
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  digitalWrite(PIN_SS, LOW);
  SPI.transfer(addr & 0x7F);
  byte v = SPI.transfer(0x00);
  digitalWrite(PIN_SS, HIGH);
  SPI.endTransaction();
  return v;
}

void writeRadioReg(byte addr, byte v) {
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  digitalWrite(PIN_SS, LOW);
  SPI.transfer(addr | 0x80);
  SPI.transfer(v);
  digitalWrite(PIN_SS, HIGH);
  SPI.endTransaction();
}

bool startRadio() {
  pinMode(PIN_DIO0, INPUT);
  LoRa.setPins(PIN_SS, PIN_RST, PIN_DIO0);
  LoRa.setSPIFrequency(1E6);   // 1 MHz instead of 8: more tolerant of long jumper wires
  if (!LoRa.begin(FREQ)) return false;
  LoRa.setTxPower(txPower);
  LoRa.setSyncWord(SYNC_WORD);
  LoRa.setSpreadingFactor(SPREADING_FACTOR);
  LoRa.enableCrc();
  return true;
}

// A missing or loose Ra-02 must not stop the node: Bluetooth and phone uploads
// keep working, and the radio is retried every RADIO_RETRY_MS.
#define RADIO_RETRY_MS 10000
bool radioOk = false;
unsigned long lastRadioTry = 0;

// With long breadboard jumpers, SPI writes sometimes land in the wrong register
// and silently change the modem settings (seen: bandwidth 125 kHz -> 10 kHz).
// Nodes with different settings can't hear each other, so the settings are
// checked before every send and every RADIO_CHECK_MS, and re-applied if wrong.
#define RADIO_CHECK_MS 3000
unsigned long lastRadioCheck = 0;
int radioFixes = 0;

bool radioConfigOk() {
  return readRadioReg(0x1D) == 0x72 &&       // BW 125 kHz, CR 4/5, explicit header
         readRadioReg(0x1E) == ((SPREADING_FACTOR << 4) | 0x04) &&   // SF, CRC on
         readRadioReg(0x09) == (0x80 | (txPower - 2)) &&    // PA_BOOST at txPower
         readRadioReg(0x39) == SYNC_WORD;
}

void ensureRadioConfig() {
  lastRadioCheck = millis();
  if (!radioOk || radioConfigOk()) return;
  radioFixes++;
  bool fixed = startRadio() && radioConfigOk();
  Serial.println("RADIO settings were corrupted - re-applied (" + String(radioFixes) + " so far)" +
                 (fixed ? "" : " - STILL WRONG, check the Ra-02 wiring"));
}

void tryRadio() {
  lastRadioTry = millis();
  byte v = readChipVersion();
  radioOk = v == 0x12 && startRadio();
  if (radioOk) Serial.println("RADIO Ra-02 found (chip version 0x12)");
  else Serial.println("RADIO Ra-02 not found (chip version 0x" + String(v, HEX) +
                      ") - check its wires; running without LoRa, retrying every " +
                      String(RADIO_RETRY_MS / 1000) + " s");
}

// LoRa.endPacket() in blocking mode waits forever for TX done, which froze the
// whole node when the radio never reported it. Send asynchronously and give up
// after TX_TIMEOUT_MS, logging the radio state and restarting the radio.
#define REG_OP_MODE       0x01
#define REG_IRQ_FLAGS     0x12
#define REG_DIO_MAPPING_1 0x40
#define DIO0_TX_DONE      0x40   // DIO0 goes HIGH when a transmission finishes
#define IRQ_TX_DONE       0x08
#define TX_TIMEOUT_MS     2000

void transmit(Packet p) {
  p.via = boatId;
  String s = encode(p);
  if (!radioOk) {
    Serial.println("NORADIO not sent (no Ra-02): " + s);
    return;
  }
  ensureRadioConfig();
  LoRa.beginPacket();
  LoRa.print(withChecksum(s));
  writeRadioReg(REG_DIO_MAPPING_1, DIO0_TX_DONE);
  LoRa.endPacket(true);

  // Wait on the DIO0 pin, not on SPI register reads: without an antenna, SPI
  // reads during transmission come back corrupted, while DIO0 stays clean.
  unsigned long start = millis();
  while (digitalRead(PIN_DIO0) == LOW) {
    if (millis() - start > TX_TIMEOUT_MS) {
      Serial.println("TXERR no TX-done after " + String(TX_TIMEOUT_MS) + " ms: opmode=0x" +
                     String(readRadioReg(REG_OP_MODE), HEX) + " irq=0x" + String(readRadioReg(REG_IRQ_FLAGS), HEX) +
                     " version=0x" + String(readRadioReg(0x42), HEX) + " - restarting radio");
      if (readRadioReg(0x42) != 0x12 || !startRadio()) {
        radioOk = false;   // radio gone - loop() retries it
        Serial.println("TXERR radio lost - will retry");
      }
      return;
    }
    delay(1);
  }
  writeRadioReg(REG_IRQ_FLAGS, 0xFF);
  Serial.println("TX    " + s + "  (" + String(millis() - start) + " ms)");
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

// Diagnostic for failed transmissions: send one short test packet and print how
// the radio's mode and IRQ flags change. "txtest rfo" uses the low-power RFO
// output instead of PA_BOOST (not wired to the antenna pad on the Ra-02, but it
// shows whether the problem is the power amplifier).
void txTest(bool rfo) {
  if (!radioOk) { Serial.println("TEST  no Ra-02"); return; }
  if (rfo) LoRa.setTxPower(0, PA_OUTPUT_RFO_PIN);
  Serial.println("TEST  sending with " + String(rfo ? "RFO" : "PA_BOOST") +
                 ", opmode before: 0x" + String(readRadioReg(REG_OP_MODE), HEX));
  LoRa.beginPacket();
  LoRa.print("TEST");
  writeRadioReg(REG_DIO_MAPPING_1, DIO0_TX_DONE);
  LoRa.endPacket(true);

  // While transmitting, read the mode register repeatedly: anything other than
  // standby/synth/TX (0x81-0x83) means the SPI read came back corrupted
  int reads = 0, corrupted = 0;
  unsigned long start = millis();
  while (digitalRead(PIN_DIO0) == LOW && millis() - start < TX_TIMEOUT_MS) {
    byte mode = readRadioReg(REG_OP_MODE);
    reads++;
    if (mode < 0x81 || mode > 0x83) corrupted++;
  }
  unsigned long took = millis() - start;
  bool done = digitalRead(PIN_DIO0) == HIGH;
  Serial.println("TEST  " + String(done ? "TX done (DIO0 high) after " + String(took) + " ms"
                                        : "NO TX done within " + String(TX_TIMEOUT_MS) + " ms") +
                 " | corrupted SPI reads during TX: " + String(corrupted) + "/" + String(reads));
  writeRadioReg(REG_IRQ_FLAGS, 0xFF);
  if (rfo) LoRa.setTxPower(txPower);   // back to PA_BOOST
}

// Diagnostic for a link that receives nothing: listen continuously for `ms` and
// report the strongest signal seen and every header / packet / CRC error, which
// LoRa.parsePacket() would otherwise drop silently.
void rxTest(unsigned long ms) {
  if (!radioOk) { Serial.println("TEST  no Ra-02"); return; }
  writeRadioReg(REG_IRQ_FLAGS, 0xFF);
  writeRadioReg(REG_OP_MODE, 0x85);          // LoRa RX continuous
  int maxRssi = -200, minRssi = 0, headers = 0, packets = 0, crcErrors = 0;
  long sum = 0, samples = 0;
  unsigned long start = millis();
  while (millis() - start < ms) {
    int rssi = (int)readRadioReg(0x1B) - 164; // current RSSI, 433 MHz (LF) port
    if (rssi > maxRssi) maxRssi = rssi;
    if (rssi < minRssi) minRssi = rssi;
    sum += rssi;
    samples++;
    byte irq = readRadioReg(REG_IRQ_FLAGS);
    if (irq) {
      if (irq & 0x10) headers++;
      if (irq & 0x40) packets++;
      if (irq & 0x20) crcErrors++;
      writeRadioReg(REG_IRQ_FLAGS, 0xFF);
    }
    esp_task_wdt_reset();
    delay(2);
  }
  writeRadioReg(REG_OP_MODE, 0x81);          // standby; parsePacket() re-arms RX
  Serial.println("TEST  listened " + String(ms / 1000) + " s | RSSI min " + String(minRssi) +
                 " avg " + String(samples ? sum / samples : 0) + " max " + String(maxRssi) + " dBm" +
                 " | headers " + String(headers) + " | packets " + String(packets) +
                 " | CRC errors " + String(crcErrors));
}

// Diagnostic for the SOS button: record every change on the pin for ms, then
// group them into presses (a press ends after 100 ms of the pin staying HIGH) and
// report how long each was held and how many times the contact bounced.
void btnTest(unsigned long ms) {
  Serial.println("TEST  watching D" + String(PIN_SOS_BTN) + " for " + String(ms / 1000) + " s - press the button now (idle level: " +
                 String(digitalRead(PIN_SOS_BTN) ? "HIGH, ok" : "LOW - wire or short problem") + ")");
  btnPending = false;
  const int MAX_EDGES = 400;
  static unsigned long at[MAX_EDGES];
  static bool level[MAX_EDGES];
  int n = 0;
  bool last = digitalRead(PIN_SOS_BTN);
  unsigned long start = millis();
  while (millis() - start < ms) {
    bool now = digitalRead(PIN_SOS_BTN);
    if (now != last && n < MAX_EDGES) { at[n] = micros(); level[n] = now; n++; }
    last = now;
    esp_task_wdt_reset();
    delayMicroseconds(100);
  }
  btnPending = false;   // presses made during the test are not SOS requests
  int presses = 0, tooShort = 0, bouncy = 0, i = 0;
  while (i < n) {
    if (level[i] != LOW) { i++; continue; }
    int first = i, edges = 1;
    unsigned long lastHigh = at[i];
    int j = i + 1;
    for (; j < n; j++) {
      if (level[j] == HIGH) lastHigh = at[j];
      else if (at[j] - lastHigh > 100000UL) break;     // 100 ms stable HIGH: previous press is over
      edges++;
    }
    unsigned long heldMs = (lastHigh - at[first]) / 1000;
    presses++;
    if (heldMs < 30) tooShort++;
    if (edges > 2) bouncy++;
    Serial.println("TEST  press #" + String(presses) + ": held " + String(heldMs) + " ms, " + String(edges) +
                   " edges" + (edges > 2 ? " (bounced)" : "") + (heldMs < 30 ? " - shorter than the old 30 ms check" : ""));
    i = j;
  }
  Serial.println("TEST  done: " + String(presses) + " presses seen, " + String(bouncy) + " bounced, " +
                 String(tooShort) + " shorter than 30 ms");
}

// ---------- inputs ----------
void printStatus() {
  String gps = fixReceivedAt ? String(fixLat, 6) + ", " + String(fixLon, 6) +
                               " (" + String((millis() - fixReceivedAt) / 1000) + " s ago)"
                             : "no phone fix - simulated " + String(simLat, 6) + ", " + String(simLon, 6);
  Serial.println("STAT  boat " + boatId + " | phone " + String(bleConnected ? "connected" : "not connected") +
                 " | " + String(online() ? "ONLINE (gateway)" : "OFFLINE (relay)") +
                 " | SOS " + String(sosActive ? "active" : "off") + " | radio " +
                 String(radioOk ? "OK" : "MISSING") + " (settings re-applied " + String(radioFixes) + "x)" +
                 " | GPS " + gps +
                 " | uptime " + String(millis() / 1000) + " s | free heap " + String(ESP.getFreeHeap()) +
                 " (min " + String(ESP.getMinFreeHeap()) + ")");
}

const char* resetReasonText() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "power on";
    case ESP_RST_SW:       return "software restart";
    case ESP_RST_PANIC:    return "CRASH (panic)";
    case ESP_RST_INT_WDT:  return "CRASH (interrupt watchdog)";
    case ESP_RST_TASK_WDT: return "FROZEN (task watchdog)";
    case ESP_RST_WDT:      return "CRASH (watchdog)";
    case ESP_RST_BROWNOUT: return "POWER DIP (brownout)";
    case ESP_RST_EXT:      return "reset pin";
    default:               return "other";
  }
}

void checkInputs() {
  if (btnPending) {
    noInterrupts();
    unsigned long down = btnDownAt, up = btnUpAt;
    bool low = digitalRead(PIN_SOS_BTN) == LOW;
    interrupts();
    bool released = !low && up >= down;
    unsigned long held = released ? up - down : millis() - down;
    if (low && held < BTN_MIN_MS) {
      // still pressed but too soon to tell - decide on a later pass
    } else if (held >= BTN_MIN_MS) {
      btnPending = false;
      if (sosActive) Serial.println("BTN   press ignored: an SOS is already being sent (" + String(sosRepeats) + "/" + String(SOS_MAX_REPEATS) + ")");
      else { Serial.println("BTN   press (" + String(held) + " ms) -> SOS"); triggerSos("BTN"); }
    } else if (released) {
      btnPending = false;
      Serial.println("BTN   glitch ignored (" + String(held) + " ms)");
    }
    // pin HIGH but up < down: contact bounced back down, wait for the next edge
  }

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
    else if (cmd == "txtest" || cmd == "txtest rfo") txTest(cmd == "txtest rfo");
    else if (cmd == "rxtest") rxTest(10000);
    else if (cmd == "btntest") btnTest(20000);
    else if (cmd.startsWith("power ")) {
      int level = cmd.substring(6).toInt();
      if (level < 2 || level > 17) {
        Serial.println("POWER must be 2-17 dBm");
      } else {
        txPower = level;
        if (radioOk) LoRa.setTxPower(txPower);
        Serial.println("POWER TX power now " + String(txPower) + " dBm" +
                       (txPower > 10 ? " (above the 10 mW / 10 dBm license-free limit in India)" : ""));
      }
    }
    else if (cmd == "regs") {
      // modem settings; expected for SF9 / 125 kHz / CR 4/5 / CRC on:
      // 0x1D=0x72, 0x1E=0x94 (SF9 + CRC), 0x26=0x04, sync word 0x39=0x5E
      const byte regs[] = { 0x01, 0x06, 0x07, 0x08, 0x09, 0x1D, 0x1E, 0x26, 0x39, 0x40, 0x42 };
      String out = "REGS ";
      for (byte r : regs) out += "0x" + String(r, HEX) + "=0x" + String(readRadioReg(r), HEX) + " ";
      Serial.println(out);
    }
    else if (cmd == "hangtest") { Serial.println("TEST  freezing loop - watchdog should restart in ~8 s"); while (true) {} }
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  pinMode(LED_PIN, OUTPUT);
  pinMode(PIN_SOS_BTN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_SOS_BTN), onButtonEdge, CHANGE);
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
  tryRadio();
  if (radioOk) Serial.println("[OK] LoRa 433 MHz, TX power " + String(txPower) + " dBm");
  Serial.println("[OK] Sensors optional: SOS button D27, water D33 (nothing wired = no alert)");

  setupBle();
  Serial.println("[OK] Bluetooth: open the SeaMesh app and pick 'SeaMesh-" + boatId + "'");
  Serial.println("Commands: sos, cancel, pos, offline, online, status");
  Serial.println("Last restart: " + String(resetReasonText()));
  Serial.println("--------------------------");

  esp_task_wdt_config_t wdt = { .timeout_ms = LOOP_WDT_MS, .idle_core_mask = 0, .trigger_panic = true };
  esp_task_wdt_reconfigure(&wdt);
  esp_task_wdt_add(NULL);   // watch this (loop) task
}

void loop() {
  esp_task_wdt_reset();
  handleBleInbox();

  if (!radioOk && millis() - lastRadioTry > RADIO_RETRY_MS) tryRadio();
  if (radioOk && millis() - lastRadioCheck > RADIO_CHECK_MS) ensureRadioConfig();

  int size = radioOk ? LoRa.parsePacket() : 0;
  if (size) {
    String raw;
    while (LoRa.available()) raw += (char)LoRa.read();
    String s = checkChecksum(raw);
    Packet p;
    if (s.length() == 0) Serial.println("BAD   checksum, ignored: " + raw);
    else if (decode(s, p)) onPacket(p, LoRa.packetRssi());
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
