/*
 * JogReceiver - ESP32-S3-ETH bridge for the "Jog The Robot" Android app.
 *
 *   Android app --BLE--> this ESP32 --Modbus TCP--> B&R X20CP0484 PLC --> igus robot
 *
 * BLE side (Nordic UART Service, same UUIDs as BleUuids in the app):
 *   The app writes text lines to the RX characteristic:
 *     "A1:R\n" .. "A5:R\n"  jog axis 1..5 in the positive direction
 *     "A1:L\n" .. "A5:L\n"  jog axis 1..5 in the negative direction
 *     "S\n"                 stop
 *   While a jog button is held the app repeats the jog command every 100 ms.
 *   Each jog command is valid for only JOG_TIMEOUT_MS (deadman): if no new
 *   command arrives in time, the jog is stopped here.
 *
 * Ethernet side (on-board W5500): this sketch is a Modbus TCP SERVER at
 * LOCAL_IP, port 502. The PLC is the client and reads 4 input registers
 * (function code 4) starting at address 0:
 *     IR0  heartbeat  +1 every 100 ms while loop() runs (PLC watchdog)
 *     IR1  enable     1 = phone connected over BLE, 0 = not connected
 *     IR2  axis       0 = none, 1..5
 *     IR3  direction  0 = stop, 1 = positive (R), 2 = negative (L)
 *
 * Board:     "ESP32S3 Dev Module", board package "esp32 by Espressif" 3.x
 *            (3.x is needed: its ETH library drives the W5500).
 * Libraries: "modbus-esp8266" by Alexander Emelianov (Library Manager).
 * Serial Monitor at 115200 baud over the board's USB-C port:
 *            set Tools > USB CDC On Boot > Enabled.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ETH.h>
#include <SPI.h>
#include <ModbusIP_ESP8266.h>

const char *BLE_NAME = "JogTheRobot";   // name shown in the app's device scan

// Nordic UART Service UUIDs (must match BleUuids in the Android app)
#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // phone -> ESP32
#define TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // ESP32 -> phone

// W5500 pins of the Waveshare ESP32-S3-ETH. Check them against your board's pinout.
#define W5500_ADDR      1
#define W5500_CS       14
#define W5500_IRQ      10
#define W5500_RST       9
#define W5500_SCK      13
#define W5500_MISO     12
#define W5500_MOSI     11

// Network: same subnet as the PLC's IF2 port (194.94.86.2 / 255.255.255.0).
// .9 and .11 are taken, .10 is kept free. Before using an address, ping it: no reply = free.
const IPAddress LOCAL_IP(194, 94, 86, 15);
const IPAddress GATEWAY(194, 94, 86, 1);
const IPAddress SUBNET(255, 255, 255, 0);

// Modbus input register addresses
enum { REG_HEARTBEAT = 0, REG_ENABLE, REG_AXIS, REG_DIR, REG_COUNT };

const unsigned long JOG_TIMEOUT_MS = 300;   // deadman: no command for this long = stop
const unsigned long HEARTBEAT_MS = 100;

ModbusIP mb;

BLEServer *server = nullptr;
BLECharacteristic *txChar = nullptr;
volatile bool deviceConnected = false;
bool wasConnected = false;

// Current jog request. Written and read only in loop().
uint16_t jogAxis = 0;          // 0 = none, 1..5
uint16_t jogDir = 0;           // 0 = stop, 1 = positive (R), 2 = negative (L)
unsigned long lastJogCmd = 0;  // millis() of the last jog command
uint16_t heartbeat = 0;

// Complete command lines are handed from the BLE task to loop() through a queue.
struct Line { char text[65]; };
QueueHandle_t lineQueue;
char rxBuffer[65];        // collects characters until '\n' (BLE task only)
size_t rxLen = 0;

void reply(const String &msg) {
  if (!deviceConnected || txChar == nullptr) return;
  txChar->setValue((uint8_t *)msg.c_str(), msg.length());
  txChar->notify();
}

void stopJog(const char *why) {
  if (jogDir != 0) {
    Serial.print("[JOG] STOP (");
    Serial.print(why);
    Serial.println(")");
  }
  jogAxis = 0;
  jogDir = 0;
}

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd == "S") {
    stopJog("released");
    reply("OK S\n");
    return;
  }

  // Expected: A<n>:<L|R>
  int sep = cmd.indexOf(':');
  if (cmd.charAt(0) != 'A' || sep < 2 || sep == (int)cmd.length() - 1) {
    Serial.print("[RX] Unknown command: ");
    Serial.println(cmd);
    return;
  }

  int axis = cmd.substring(1, sep).toInt();   // 1..5
  char dir = cmd.charAt(sep + 1);             // 'L' or 'R'

  if (axis < 1 || axis > 5 || (dir != 'L' && dir != 'R')) {
    Serial.print("[RX] Invalid axis or direction: ");
    Serial.println(cmd);
    return;
  }

  // A command still in the queue after the phone disconnected must not start a jog.
  if (!deviceConnected) return;

  uint16_t newDir = (dir == 'R') ? 1 : 2;
  bool changed = (axis != jogAxis || newDir != jogDir);
  jogAxis = axis;
  jogDir = newDir;
  lastJogCmd = millis();   // every repeat refreshes the deadman timer

  // Print and reply only when the jog changes, not 10 times a second.
  if (changed) {
    Serial.print("[JOG] Axis ");
    Serial.print(axis);
    Serial.println(newDir == 1 ? " +  (R)" : " -  (L)");
    reply("OK " + cmd + "\n");
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer *s) override {
    deviceConnected = false;
  }
};

// Runs in the BLE task: only buffer here, handle in loop().
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String value = String(c->getValue().c_str());
    for (size_t i = 0; i < value.length(); i++) {
      char ch = value[i];
      if (ch == '\n') {
        Line line;
        memcpy(line.text, rxBuffer, rxLen);
        line.text[rxLen] = '\0';
        xQueueSend(lineQueue, &line, 0);   // drop if loop() is far behind
        rxLen = 0;
      } else if (ch != '\r') {
        if (rxLen < sizeof(rxBuffer) - 1) rxBuffer[rxLen++] = ch;
        else rxLen = 0;                    // guard against garbage without '\n'
      }
    }
  }
};

void setupBle() {
  BLEDevice::init(BLE_NAME);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  txChar = service->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  BLECharacteristic *rxChar = service->createCharacteristic(
      RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rxChar->setCallbacks(new RxCallbacks());

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);   // name goes in the scan response
  BLEDevice::startAdvertising();

  Serial.print("[BLE] Ready. Scan for \"");
  Serial.print(BLE_NAME);
  Serial.println("\" in the app");
}

void setupEthernetAndModbus() {
  if (!ETH.begin(ETH_PHY_W5500, W5500_ADDR, W5500_CS, W5500_IRQ, W5500_RST, SPI2_HOST,
                 W5500_SCK, W5500_MISO, W5500_MOSI)) {
    Serial.println("[ETH] W5500 not found, check the pin numbers");
  }
  ETH.config(LOCAL_IP, GATEWAY, SUBNET);

  mb.server();                              // Modbus TCP server on port 502
  mb.addIreg(REG_HEARTBEAT, 0, REG_COUNT);  // input registers 0..3, all 0

  Serial.print("[ETH] Modbus server at ");
  Serial.println(LOCAL_IP);
}

void setup() {
  Serial.begin(115200);
  // Native USB: the port re-appears on the PC after reset, so wait (up to 5 s)
  // for the Serial Monitor to reconnect before printing the startup lines.
  unsigned long waitStart = millis();
  while (!Serial && millis() - waitStart < 5000) delay(10);
  delay(200);
  Serial.println();
  Serial.println("[BOOT] JogReceiver (BLE -> Modbus TCP) starting");

  lineQueue = xQueueCreate(16, sizeof(Line));

  setupEthernetAndModbus();
  setupBle();
}

void loop() {
  // 1. Answer the PLC's Modbus requests
  mb.task();

  // 2. Handle any complete command lines received over BLE
  Line line;
  while (xQueueReceive(lineQueue, &line, 0) == pdTRUE) {
    handleCommand(String(line.text));
  }

  // 3. Safety: stop when the phone goes quiet or disconnects
  if (jogDir != 0 && millis() - lastJogCmd > JOG_TIMEOUT_MS) stopJog("timeout, no command from phone");
  if (!deviceConnected) stopJog("phone disconnected");

  // 4. Heartbeat: proves to the PLC that this loop is alive
  static unsigned long lastHb = 0;
  if (millis() - lastHb >= HEARTBEAT_MS) {
    lastHb = millis();
    heartbeat++;   // wraps from 65535 to 0, that's fine
  }

  // 5. Publish everything to the Modbus input registers
  mb.Ireg(REG_HEARTBEAT, heartbeat);
  mb.Ireg(REG_ENABLE, deviceConnected ? 1 : 0);
  mb.Ireg(REG_AXIS, jogAxis);
  mb.Ireg(REG_DIR, jogDir);

  // Status line every 3 s so you can see the board is alive
  static unsigned long lastBeat = 0;
  if (millis() - lastBeat > 3000) {
    lastBeat = millis();
    Serial.print("[ALIVE] BLE: ");
    Serial.print(deviceConnected ? "phone connected" : "advertising");
    Serial.print(" | Ethernet: ");
    Serial.print(ETH.linkUp() ? "link up" : "NO LINK");
    Serial.print(" | heartbeat ");
    Serial.println(heartbeat);
  }

  // Connection state changes
  if (deviceConnected && !wasConnected) {
    Serial.println("[BLE] Phone connected");
    wasConnected = true;
  }
  if (!deviceConnected && wasConnected) {
    Serial.println("[BLE] Phone disconnected, advertising again");
    rxLen = 0;
    delay(300);                       // let the stack settle (PLC watchdog allows 500 ms)
    BLEDevice::startAdvertising();
    wasConnected = false;
  }

  delay(5);
}
