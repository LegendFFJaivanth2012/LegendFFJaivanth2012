#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>

static const int UART_RX=18, UART_TX=17;
HardwareSerial BRIDGE(1); WebServer server(80);
struct __attribute__((packed)) FrameHeader{
  uint16_t magic; uint8_t type; uint8_t reserved;
  uint32_t transferId; uint32_t seq; uint32_t total; uint16_t len;
};
uint16_t crc16(const uint8_t* p,size_t n){
  uint16_t crc=0xFFFF; while(n--){crc^=(uint16_t)(*p++)<<8;for(int i=0;i<8;i++)crc=(crc&0x8000)?(crc<<1)^0x1021:crc<<1;}return crc;
}
String filename="received.bin"; uint32_t activeId=0,totalPackets=0,receivedPackets=0,expectedSeq=0; bool complete=false;
bool readExact(uint8_t* dst,size_t n,uint32_t timeoutMs=3000){
  size_t got=0; uint32_t start=millis();
  while(got<n && millis()-start<timeoutMs){if(BRIDGE.available())dst[got++]=BRIDGE.read();else delay(1);} return got==n;
}
void resetTransfer(){LittleFS.remove("/received.bin");receivedPackets=0;expectedSeq=0;complete=false;}
void processFrame(){
  if(BRIDGE.available()<(int)sizeof(FrameHeader))return;
  FrameHeader h; if(!readExact((uint8_t*)&h,sizeof(h)))return;
  if(h.magic!=0xA7E1||h.len>4096)return;
  uint8_t* payload=(uint8_t*)malloc(h.len); if(!payload)return;
  uint16_t gotCrc=0;
  if(!readExact(payload,h.len)||!readExact((uint8_t*)&gotCrc,2)){free(payload);return;}
  if(crc16(payload,h.len)!=gotCrc){free(payload);return;}
  if(h.type==2){
    filename="";for(uint16_t i=0;i<h.len;i++)filename+=(char)payload[i];
    if(!filename.length())filename="received.bin";activeId=h.transferId;totalPackets=h.total;resetTransfer();
  } else if(h.type==1){
    if(h.transferId!=activeId||h.seq!=expectedSeq){free(payload);return;}
    File f=LittleFS.open("/received.bin",expectedSeq==0?FILE_WRITE:FILE_APPEND);
    if(f){f.write(payload,h.len);f.close();}
    totalPackets=h.total;receivedPackets++;expectedSeq++;complete=receivedPackets>=totalPackets;
  }
  free(payload);
}
void setup(){
  Serial.begin(115200);BRIDGE.begin(921600,SERIAL_8N1,UART_RX,UART_TX);LittleFS.begin(true);
  WiFi.mode(WIFI_AP);WiFi.softAP("AEROLINK-RX","aerolink123");
  server.on("/api/status",HTTP_GET,[](){
    String j="{\"role\":\"receiver\",\"complete\":"+String(complete?"true":"false")+
      ",\"packets\":"+String(receivedPackets)+",\"total\":"+String(totalPackets)+
      ",\"name\":\""+filename+"\"}";server.send(200,"application/json",j);
  });
  server.on("/api/file",HTTP_GET,[](){
    if(!complete){server.send(409,"text/plain","not complete");return;}
    File f=LittleFS.open("/received.bin","r");if(!f){server.send(404,"text/plain","file missing");return;}
    server.sendHeader("Content-Disposition","attachment; filename=\""+filename+"\"");server.streamFile(f,"application/octet-stream");f.close();
  });
  server.serveStatic("/",LittleFS,"/www/index.html");server.begin();
}
void loop(){server.handleClient();processFrame();}
