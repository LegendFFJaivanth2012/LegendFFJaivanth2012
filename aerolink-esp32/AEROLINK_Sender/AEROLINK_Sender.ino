#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include "web_ui.h"

// ESP32-A: Phone A joins AEROLINK-TX. This node forwards HTTP file chunks to ESP32-B over UART.
static const int UART_RX_PIN = 18;
static const int UART_TX_PIN = 17;
static const uint32_t UART_BAUD = 460800;
static const size_t CHUNK_LIMIT = 1024;

HardwareSerial LINK(1);
WebServer server(80);

struct __attribute__((packed)) FrameHeader {
  uint16_t magic;
  uint8_t type;       // 1=data, 2=filename/start
  uint8_t reserved;
  uint32_t transferId;
  uint32_t sequence;
  uint32_t totalPackets;
  uint16_t length;
};

uint8_t uploadBuffer[CHUNK_LIMIT];
size_t uploadLength = 0;
bool uploadTooLarge = false;
String uploadId, uploadName;
uint32_t uploadSequence = 0, uploadTotal = 0;
uint32_t currentTransferHash = 0;
bool metadataSent = false;
uint32_t forwardedPackets = 0;

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

uint32_t hashTransferId(const String& value) {
  uint32_t hash = 2166136261UL;
  for (size_t i = 0; i < value.length(); ++i) {
    hash ^= (uint8_t)value[i];
    hash *= 16777619UL;
  }
  return hash;
}

void writeFrame(uint8_t type, uint32_t id, uint32_t seq, uint32_t total,
                const uint8_t* payload, uint16_t length) {
  FrameHeader header{0xA7E1, type, 0, id, seq, total, length};
  const uint16_t checksum = crc16(payload, length);
  LINK.write(reinterpret_cast<const uint8_t*>(&header), sizeof(header));
  if (length) LINK.write(payload, length);
  LINK.write(reinterpret_cast<const uint8_t*>(&checksum), sizeof(checksum));
  LINK.flush();
}

String safeName(String name) {
  name.replace("/", "_");
  name.replace("\\", "_");
  name.replace("\"", "_");
  name.replace(":", "_");
  if (name.length() == 0) name = "aerolink-file.bin";
  if (name.length() > 120) name = name.substring(name.length() - 120);
  return name;
}

void handleChunkUpload() {
  HTTPUpload& upload = server.upload();

  if (upload.status == UPLOAD_FILE_START) {
    uploadLength = 0;
    uploadTooLarge = false;
    uploadId = server.arg("id");
    uploadName = safeName(server.arg("name"));
    uploadSequence = (uint32_t)server.arg("seq").toInt();
    uploadTotal = (uint32_t)server.arg("total").toInt();
  } else if (upload.status == UPLOAD_FILE_WRITE) {
    if (uploadLength + upload.currentSize > CHUNK_LIMIT) {
      uploadTooLarge = true;
      return;
    }
    memcpy(uploadBuffer + uploadLength, upload.buf, upload.currentSize);
    uploadLength += upload.currentSize;
  } else if (upload.status == UPLOAD_FILE_END) {
    if (uploadTooLarge || uploadId.length() == 0 || uploadTotal == 0 ||
        uploadSequence >= uploadTotal) return;

    const uint32_t idHash = hashTransferId(uploadId);
    if (!metadataSent || currentTransferHash != idHash || uploadSequence == 0) {
      const uint16_t nameLength = (uint16_t)uploadName.length();
      writeFrame(2, idHash, 0, uploadTotal,
                 reinterpret_cast<const uint8_t*>(uploadName.c_str()), nameLength);
      currentTransferHash = idHash;
      metadataSent = true;
      forwardedPackets = 0;
    }

    writeFrame(1, idHash, uploadSequence, uploadTotal, uploadBuffer,
               (uint16_t)uploadLength);
    forwardedPackets++;
  }
}

void handleChunkComplete() {
  if (uploadTooLarge) {
    server.send(413, "application/json", "{\"ok\":false,\"error\":\"chunk exceeds 1024 bytes\"}");
    return;
  }
  server.send(200, "application/json", "{\"ok\":true}");
}

void sendStatus() {
  String json = "{\\"role\\":\\"sender\\",\\"clients\\":" +
    String(WiFi.softAPgetStationNum()) +
    ",\\"uart\\":true" +
    ",\\"packets\\":" + String(forwardedPackets) +
    ",\\"total\\":" + String(uploadTotal) +
    ",\\"receiving\\":false,\\"complete\\":false,\\"name\\":\\"" + uploadName + "\\"}";
  server.send(200, "application/json", json);
}

void setup() {
  Serial.begin(115200);
  LINK.begin(UART_BAUD, SERIAL_8N1, UART_RX_PIN, UART_TX_PIN);

  WiFi.mode(WIFI_AP);
  WiFi.softAP("AEROLINK-TX", "aerolink123", 1, false, 1);

  server.on("/", HTTP_GET, []() {
    server.send_P(200, "text/html", AEROLINK_WEB_UI);
  });
  server.on("/api/status", HTTP_GET, sendStatus);
  server.on("/api/chunk", HTTP_POST, handleChunkComplete, handleChunkUpload);
  server.onNotFound([]() {
    server.send(404, "text/plain", "AEROLINK endpoint not found");
  });
  server.begin();
}

void loop() {
  server.handleClient();
}
