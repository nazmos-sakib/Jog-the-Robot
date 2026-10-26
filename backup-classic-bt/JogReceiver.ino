/*
 * JogReceiver - ESP32 side of the "Jog The Robot" Android app.
 *
 * Receives commands over Classic Bluetooth (SPP) and prints them to the
 * USB Serial Monitor (115200 baud).
 *
 * Command format sent by the app:  <AXIS>:<DIR>\n
 *   e.g.  A1:L   -> Axis 1, move Left
 *         A3:R   -> Axis 3, move Right
 *
 * Board: classic ESP32 (ESP32-WROOM / ESP32 DevKit). ESP32-S2/S3/C3/C6 have
 * NO Classic Bluetooth and cannot run BluetoothSerial.
 *
 * Pairing: open the phone's Bluetooth settings, pair with "JogTheRobot",
 * then pick it in the app under "Show saved devices".
 */

#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled. Use a classic ESP32 board.
#endif

#if !defined(CONFIG_BT_SPP_ENABLED)
#error Serial Bluetooth (SPP) is not available on this chip.
#endif

BluetoothSerial SerialBT;

const char *BT_NAME = "JogTheRobot";   // name shown on the phone

String rxBuffer;                        // collects characters until '\n'

// Called by the Bluetooth stack when the phone connects / disconnects
void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  if (event == ESP_SPP_SRV_OPEN_EVT) {
    Serial.println("[BT] Phone connected");
  } else if (event == ESP_SPP_CLOSE_EVT) {
    Serial.println("[BT] Phone disconnected");
    rxBuffer = "";
  }
}

void handleCommand(String cmd) {
  cmd.trim();                           // removes \r, spaces
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
  SerialBT.print("OK ");
  SerialBT.println(cmd);
}

void setup() {
  Serial.begin(115200);
  delay(200);

  SerialBT.register_callback(btCallback);
  if (!SerialBT.begin(BT_NAME)) {       // start as Bluetooth slave
    Serial.println("[BT] Failed to start Bluetooth!");
    while (true) delay(1000);
  }

  Serial.print("[BT] Ready. Pair your phone with \"");
  Serial.print(BT_NAME);
  Serial.println("\"");
}

void loop() {
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      handleCommand(rxBuffer);
      rxBuffer = "";
    } else {
      rxBuffer += c;
      if (rxBuffer.length() > 64) {     // protect against garbage without '\n'
        Serial.println("[RX] Buffer overflow, clearing");
        rxBuffer = "";
      }
    }
  }
}
