# AEROLINK — Adaptive Air Transfer

AEROLINK is the web interface for a two-node ESP32-S3 communication prototype.

## Current architecture

Phone / browser
→ ESP32-S3 A
→ optical IR / future 40 kHz ultrasonic / Wi-Fi / BLE
→ ESP32-S3 B
→ receiver

The UI is deliberately transport-agnostic. The physical layer can change without changing the file-transfer interface.

## Included

- Responsive dark technical UI
- File selection and metadata
- Live transfer simulation for demonstrations
- Transport selection: optical, ultrasonic, Wi-Fi and BLE
- Packet / integrity / retry telemetry
- Web Bluetooth connection hook for an AEROLINK BLE service
- PWA manifest
- No room-code workflow

## BLE integration

The UI expects an AEROLINK BLE device name beginning with `AEROLINK` and an optional service UUID:

`0000a100-0000-1000-8000-00805f9b34fb`

The characteristic UUIDs and packet protocol should be finalized together with the ESP32 firmware.

## Important

The current browser-side BLE code is a connection hook, not the completed ESP32 file-transfer protocol. The physical optical and ultrasonic packet layers still need firmware.

ESP32-S3 provides Wi-Fi and Bluetooth LE; it does not provide Bluetooth Classic. See Espressif's documentation for the supported Bluetooth stack.

## Run

Open `airlink/index.html` in a browser for the UI demo.

For Web Bluetooth, use a browser/environment that supports Web Bluetooth and a secure context (normally HTTPS or localhost).

## Next firmware milestones

1. ESP32 A ↔ ESP32 B heartbeat.
2. Optical PHY: IR LED → BPW34 → amplifier/comparator → GPIO.
3. Packet framing + CRC.
4. BLE GATT control/data service.
5. Wi-Fi local transport.
6. Ultrasonic PHY when the 40 kHz transducers arrive.
7. Adaptive channel selection and deduplication.
