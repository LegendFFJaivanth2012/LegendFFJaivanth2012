#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>
#include "web_ui.h"

// ESP32-B: Phone B joins AEROLINK-RX. This node receives UART frames and stores the file.
static const int UART_RX_PIN = 18;
static const int UART_TX_PIN = 17;
static const uint32_t UART_BAUD = 460800;
static const char* RECEIVED_PATH = "/aerolink_received.bin";

HardwareSerial LINK(1);
WebServer server(80);

struct __attribute__((packed)) FrameHeader {
  uint16_t magic;
  uint8_t type;
  uint8_t reserved;
  uint32_t transferId;
  uint32_t sequence;
  uint32_t totalPackets;
  uint16_t length;
};

String receivedName = "aerolink-received.bin";
uint32_t activeTransfer = 0;
uint32_t totalPackets = 0;
uint32_t receivedPackets = 0;
uint32_t receivedBytes = 0;
uint32_t expectedSequence = 0;
bool fileComplete = false;
bool transferStarted = false;

uint16_t crc16(const uint8_t* data, size_t length) {
  uint16_t crc = 0xFFFF;
  for (size_t n = 0; n < length; ++n) {
    crc ^= (uint16_t)data[n] << 8;
    for (uint8_t bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1;
    }
  }
  return crc;
}

bool readExact(uint8_t* target, size_t length, uint32_t timeoutMs = 1000) {
  size_t count = 0;
  const uint32_t started = millis();
  while (count < length && millis() - started < timeoutMs) {
    if (LINK.available()) target[count++] = (uint8_t)LINK.read();
    else delay(1);
  }
  return count == length;
}

void resetReceivedFile() {
  LittleFS.remove(RECEIVED_PATH);
  receivedPackets = 0;
  receivedBytes = 0;
  expectedSequence = 0;
  fileComplete = false;
  transferStarted = true;
}

void safeReceivedName(String& name) {
  name.replace("/", "_");
  name.replace("\\", "_");
  name.replace("\"", "_");
  name.replace(":", "_");
  if (name.length() == 0) name = "aerolink-received.bin";
  if (name.length() > 120) name = name.substring(name.length() - 120);
}

void handleFrame(const FrameHeader& header, uint8_t* payload) {
  if (header.type == 2) {
    activeTransfer = header.transferId;
    totalPackets = header.totalPackets;
    receivedName = "";
    for (uint16_t i = 0; i < header.length; ++i) receivedName += (char)payload[i];
    safeReceivedName(receivedName);
    resetReceivedFile();
    return;
  }

  if (header.type != 1 || !transferStarted || header.transferId != activeTransfer ||
      header.sequence != expectedSequence || header.totalPackets != totalPackets) return;

  File output = LittleFS.open(RECEIVED_PATH, expectedSequence == 0 ? "w" : "a");
  if (!output) return;
  if (header.length) output.write(payload, header.length);
  output.close();

  receivedBytes += header.length;
  receivedPackets++;
  expectedSequence++;
  fileComplete = (receivedPackets == totalPackets);
}

// Scan for the two-byte frame marker, then read a complete frame and validate CRC16.
void processUart() {
  while (LINK.available() >= 2) {
    const int first = LINK.read();
    if (first != 0xE1) continue;
    const int second = LINK.read();
    if (second != 0xA7) {
      if (second == 0xE1) LINK.write((uint8_t)second);
      continue;
    }

    FrameHeader header{};
    header.magic = 0xA7E1;
    uint8_t* rest = reinterpret_cast<uint8_t*>(&header) + 2;
    if (!readExact(rest, sizeof(FrameHeader) - 2, 1000)) return;
    if (header.length > 1024) continue;

    uint8_t payload[1024];
    if (header.length && !readExact(payload, header.length, 1000)) return;
    uint16_t receivedCrc = 0;
    if (!readExact(reinterpret_cast<uint8_t*>(&receivedCrc), 2, 1000)) return;
    if (crc16(payload, header.length) != receivedCrc) continue;

    handleFrame(header, payload);
  }
}

void sendStatus() {
  String json = "{\"role\":\"receiver\",\"clients\":" +
    String(WiFi.softAPgetStationNum()) +
    ",\"uart\":true,\"packets\":" + String(receivedPackets) +
    ",\"total\":" + String(totalPackets) +
    ",\"receiving\":" + String(transferStarted && !fileComplete ? "true" : "false") +
    ",\"complete\":" + String(fileComplete ? "true" : "false") +
    ",\"bytes\":" + String(receivedBytes) +
    ",\"name\":\"" + receivedName + "\"}";
  server.send(200, "application/json", json);
}

void setup() {
  Serial.begin(115200);
  LINK.setRxBufferSize(4096);
  LINK.begin(UART_BAUD, SERIAL_8N1, UART_RX_PIN, UART_TX_PIN);

  if (!LittleFS.begin(true)) {
    Serial.println("LittleFS mount failed");
  }

  WiFi.mode(WIFI_AP);
  WiFi.softAP("AEROLINK-RX", "aerolink123", 6, false, 1);

  server.on("/", HTTP_GET, []() {
    server.send_P(200, "text/html", AEROLINK_WEB_UI);
  });
  server.on("/api/status", HTTP_GET, sendStatus);
  server.on("/api/file", HTTP_GET, []() {
    if (!fileComplete) {
      server.send(409, "application/json", "{\"ok\":false,\"error\":\"file is not complete\"}");
      return;
    }
    File file = LittleFS.open(RECEIVED_PATH, "r");
    if (!file) {
      server.send(404, "text/plain", "received file missing");
      return;
    }
    server.sendHeader("Content-Disposition", "attachment; filename=\"" + receivedName + "\"");
    server.streamFile(file, "application/octet-stream");
    file.close();
  });
  server.on("/api/reset", HTTP_POST, []() {
    resetReceivedFile();
    transferStarted = false;
    activeTransfer = 0;
    totalPackets = 0;
    receivedName = "aerolink-received.bin";
    server.send(200, "application/json", "{\"ok\":true}");
  });
  server.onNotFound([]() {
    server.send(404, "text/plain", "AEROLINK endpoint not found");
  });
  server.begin();
}

void loop() {
  server.handleClient();
  processUart();
}
