# AEROLINK iOS

Native SwiftUI iPhone/iPad companion for the two-ESP32 AEROLINK system.

## Hardware path

iPhone A -> Wi-Fi -> ESP32 #1 -> UART -> ESP32 #2 -> Wi-Fi -> iPhone B

The iOS app uses:
- SwiftUI for the interface
- AVFoundation for the camera
- Vision Human Hand Pose for local gesture recognition
- URLSession for direct HTTP communication with the ESP32
- UIDocumentPicker for choosing and exporting files

No cloud server or internet connection is required for the transfer.

## Gesture

Sender:
1. Choose a file.
2. Hold a thumb/index pinch until PINCH LOCKED.
3. Move the hand forward.
4. Open the hand.
5. The app uploads the file to ESP32 #1 in 1 KiB chunks.

Receiver:
1. Enable receiver mode.
2. Show an open palm.
3. The app polls ESP32 #2.
4. When the ESP32 reports a complete file, the app downloads it and offers the normal iOS Files export picker.

## ESP32 compatibility

This version targets the existing AEROLINK firmware endpoints:
- Sender: POST /api/chunk?id=...&name=...&seq=...&total=...
- Receiver: GET /api/status
- Receiver: GET /api/file

Connect the iPhone to the appropriate AEROLINK Wi-Fi AP before using the app.

## Xcode

Open `AEROLINK.xcodeproj` in Xcode 16 or newer and select an iPhone simulator/device target.

For a real iPhone, set your own Apple Developer Team in Signing & Capabilities. The project does not require a paid developer account for local testing on a personal device when using the normal Xcode signing flow, subject to Apple's current device-signing limits.

