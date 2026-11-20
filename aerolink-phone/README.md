# AEROLINK Android app

Install this same APK on both phones. It has two modes.

## Phone A — sender
1. Connect to Wi-Fi network AEROLINK-TX (password aerolink123).
2. Open AEROLINK and tap SEND.
3. Tap CHOOSE FILE and select a file.
4. The app opens its own front-camera preview.
5. Hold a thumb-index pinch until PINCH LOCKED appears.
6. Open your hand to release/send.
7. The phone sends 1 KiB file chunks to ESP32-A at 192.168.4.1.

## Phone B — receiver
1. Connect to AEROLINK-RX (password aerolink123).
2. Open AEROLINK and tap RECEIVE.
3. Tap TURN ON DISCOVERABLE MODE.
4. The front camera opens.
5. Hold an open palm to perform the catch gesture.
6. The app checks ESP32-B for a complete file.
7. When it arrives, choose where to save it on the phone.

## How camera access works
The app requests normal Android camera permission and uses CameraX to open its own camera preview. MediaPipe Hand Landmarker analyses camera frames locally on the phone. The model is bundled into the APK by the GitHub Actions workflow, so gesture recognition works without internet once the APK is installed.

The app does not bypass Android permission controls or take over the system camera app.

## Build
The workflow at .github/workflows/aerolink-apk.yml downloads the hand-landmarker model and runs gradle assembleDebug. Download the AEROLINK-debug-apk artifact from a completed workflow run.

## Network
The phone must remain connected to its assigned ESP32 Wi-Fi access point. Android may warn that this network has no internet; choose the option to stay connected.

The two ESP32 APs are separate networks, so both can use 192.168.4.1 at the same time.

## Scope
This APK is only the two-phone user interface and gesture controller for the two-ESP32 Wi-Fi plus UART bridge. No optical, ultrasonic, cloud or router path is part of this build.