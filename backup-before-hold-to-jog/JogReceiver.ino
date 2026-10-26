/*
 * JogReceiver - ESP32-S3 side of the "Jog The Robot" Android app (BLE version).
 *
 * The ESP32-S3 only has Bluetooth Low Energy, so instead of BluetoothSerial
 * this sketch exposes a Nordic UART Service (NUS):
 *   - the app WRITES commands to the RX characteristic
 *   - the sketch NOTIFIES replies on the TX characteristic
 *
 * Commands are text lines like "A1:L\n" or "A3:R\n" (axis 1..5, Left/Right).
 * Each command is printed to the USB Serial Monitor at 115200 baud.
 *
 * Board: "ESP32S3 Dev Module" (esp32 by Espressif, 2.x or 3.x).
 * For Serial Monitor over the board's USB-C port set Tools > USB CDC On Boot > Enabled.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

const char *BLE_NAME = "JogTheRobot";   // name shown in the app's device scan

// Nordic UART Service UUIDs (must match BleUuids in the Android app)
#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // phone -> ESP32
#define TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // ESP32 -> phone

BLEServer *server = nullptr;
BLECharacteristic *txChar = nullptr;
volatile bool deviceConnected = false;
bool wasConnected = false;

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

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  Serial.print("[RX] Raw: ");
  Serial.println(cmd);

  // Expected: A<n>:<L|R>
  int sep = cmd.indexOf(':');
  if (cmd.charAt(0) != 'A' || sep < 2 || sep == (int)cmd.length() - 1) {
    Serial.println("[RX] Unknown command format");
    return;
  }

  int axis = cmd.substring(1, sep).toInt();   // 1..5
  char dir = cmd.charAt(sep + 1);             // 'L' or 'R'

  if (axis < 1 || axis > 5 || (dir != 'L' && dir != 'R')) {
    Serial.println("[RX] Invalid axis or direction");
    return;
  }

  Serial.print("[JOG] Axis ");
  Serial.print(axis);
  Serial.print(" -> ");
  Serial.println(dir == 'L' ? "LEFT" : "RIGHT");

  // TODO: drive the motor for this axis here.

  // Optional reply to the app (arrives in controller.incoming)
  reply("OK " + cmd + "\n");
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer *s) override {
    deviceConnected = false;
  }
};

// Runs in the BLE task: only buffer here, print/handle in loop().
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

void setup() {
  Serial.begin(115200);
  // Native USB: the port re-appears on the PC after reset, so wait (up to 5 s)
  // for the Serial Monitor to reconnect before printing the startup lines.
  unsigned long waitStart = millis();
  while (!Serial && millis() - waitStart < 5000) delay(10);
  delay(200);
  Serial.println();
  Serial.println("[BOOT] JogReceiver starting");

  lineQueue = xQueueCreate(16, sizeof(Line));

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

void loop() {
  // Heartbeat so you can tell the board is alive in the Serial Monitor
  static unsigned long lastBeat = 0;
  if (millis() - lastBeat > 3000) {
    lastBeat = millis();
    Serial.print("[ALIVE] ");
    Serial.println(deviceConnected ? "phone connected" : "advertising, waiting for phone");
  }

  // Connection state changes
  if (deviceConnected && !wasConnected) {
    Serial.println("[BLE] Phone connected");
    wasConnected = true;
  }
  if (!deviceConnected && wasConnected) {
    Serial.println("[BLE] Phone disconnected, advertising again");
    rxLen = 0;
    delay(300);                       // let the stack settle
    BLEDevice::startAdvertising();
    wasConnected = false;
  }

  // Handle any complete command lines received over BLE
  Line line;
  while (xQueueReceive(lineQueue, &line, 0) == pdTRUE) {
    handleCommand(String(line.text));
  }

  delay(5);
}
