#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>

static const int UART_RX = 18;
static const int UART_TX = 17;
HardwareSerial BRIDGE(1);
WebServer server(80);

struct __attribute__((packed)) FrameHeader {
  uint16_t magic; uint8_t type; uint8_t reserved;
  uint32_t transferId; uint32_t seq; uint32_t total; uint16_t len;
};

uint16_t crc16(const uint8_t* p, size_t n) {
  uint16_t crc = 0xFFFF;
  while (n--) {
    crc ^= (uint16_t)(*p++) << 8;
    for (int i=0;i<8;i++) crc=(crc&0x8000)?(crc<<1)^0x1021:crc<<1;
  }
  return crc;
}
uint32_t hashId(const String& s) {
  uint32_t h=2166136261UL;
  for(size_t i=0;i<s.length();i++){h^=(uint8_t)s[i];h*=16777619UL;}
  return h;
}
void writeFrame(uint8_t type,uint32_t id,uint32_t seq,uint32_t total,const uint8_t* data,uint16_t len){
  FrameHeader h{0xA7E1,type,0,id,seq,total,len};
  uint16_t c=crc16(data,len);
  BRIDGE.write((uint8_t*)&h,sizeof(h)); BRIDGE.write(data,len); BRIDGE.write((uint8_t*)&c,2); BRIDGE.flush();
}
void rawChunk(){
  HTTPRaw &raw=server.raw();
  static String id,seqS,totalS,name;
  if(raw.status==RAW_START){
    id=server.header("X-Aero-Transfer"); seqS=server.header("X-Aero-Seq");
    totalS=server.header("X-Aero-Total"); name=server.header("X-Aero-Name");
  } else if(raw.status==RAW_WRITE){
    uint32_t transfer=hashId(id), seq=strtoul(seqS.c_str(),nullptr,10), total=strtoul(totalS.c_str(),nullptr,10);
    writeFrame(1,transfer,seq,total,raw.buf,(uint16_t)raw.currentSize);
    if(seq==0 && name.length()){
      uint16_t n=(uint16_t)min((size_t)240,name.length());
      writeFrame(2,transfer,0,total,(const uint8_t*)name.c_str(),n);
    }
  } else if(raw.status==RAW_END) server.send(200,"text/plain","OK");
}
void setup(){
  Serial.begin(115200); BRIDGE.begin(921600,SERIAL_8N1,UART_RX,UART_TX); LittleFS.begin(true);
  WiFi.mode(WIFI_AP); WiFi.softAP("AEROLINK-TX","aerolink123");
  const char* headers[]={"X-Aero-Transfer","X-Aero-Seq","X-Aero-Total","X-Aero-Name"};
  server.collectHeaders(headers,4);
  server.on("/api/chunk",HTTP_POST,[](){if(!server.hasRaw())server.send(400,"text/plain","raw body required");},rawChunk);
  server.on("/api/status",HTTP_GET,[](){server.send(200,"application/json","{\"role\":\"sender\",\"ready\":true,\"ip\":\"192.168.4.1\"}");});
  server.serveStatic("/",LittleFS,"/www/index.html");
  server.begin();
}
void loop(){server.handleClient();}
