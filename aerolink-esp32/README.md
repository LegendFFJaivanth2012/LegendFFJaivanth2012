# AEROLINK — exactly two ESP32-S3 boards + two phones

## Topology
Phone A → Wi-Fi → ESP32-A (AEROLINK-TX) → wired UART → ESP32-B (AEROLINK-RX) → Wi-Fi → Phone B

No router and no internet are required.

Each ESP32 creates its own Wi-Fi access point. Both APs use 192.168.4.1 because each phone is attached to a different isolated AP.

## UART wiring
A TX → B RX
A RX → B TX
GND → GND

Use 3.3 V logic only. The prototype uses GPIO 17/18 for UART1. Change the constants if those pins conflict with your board.

## Phone workflow
Sender: join AEROLINK-TX → open APK → select file → camera starts → hold pinch for ~0.45 s → move hand → release pinch → file is sent in 4 KiB chunks.

Receiver: join AEROLINK-RX → open APK → choose RECEIVE → turn on discoverable mode → camera starts → show open/release hand → receiver checks its ESP32.

The gesture detector is local to the phone. The APK bundles the MediaPipe hand model during the GitHub Actions build, so the demo does not require internet after installation.

## ESP32 web UI
The ESP32 serves a local status/debug page at http://192.168.4.1/. The APK is the gesture interface.

For the web files, upload the data/ directory to LittleFS. Arduino IDE 2.x can use the LittleFS uploader through the Command Palette.

## Current scope
This revision is the phone-to-phone wired ESP32 transport layer. Optical IR and 40 kHz ultrasound are not yet connected to the packet engine. They can later replace the wired bridge while keeping the phone protocol unchanged.
