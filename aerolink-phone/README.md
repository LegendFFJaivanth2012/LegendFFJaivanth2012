# AEROLINK Android app — adaptive gesture build

Install this same APK on both Android phones. Each phone keeps its own local gesture profile.

## Before first transfer: train each phone

1. Open AEROLINK and tap **TRAIN MY GESTURES**.
2. Hold a comfortable thumb–index pinch in front of the front camera until the app advances.
3. Spread your fingers into a relaxed open palm and hold it steady.
4. The app saves the learned landmark measurements on that phone.
5. Repeat training after a few practice attempts. Additional examples refine the running averages and thresholds.

The app includes a **Gesture Sensitivity** slider. Higher settings lower the movement/hold requirements and make the pinch/open-palm gates more permissive. Start around 65%; reduce it if gestures trigger accidentally.

## Phone A — sender

1. Connect to Wi-Fi network **AEROLINK-TX** (password `aerolink123`).
2. Open AEROLINK and tap **SEND**.
3. Tap **CHOOSE FILE** and select a file.
4. Hold a thumb-index pinch until **PINCH LOCKED** appears.
5. Move your pinched hand toward the receiver, then open your hand to send.
6. The phone sends 1 KiB file chunks to ESP32-A at `192.168.4.1`.

## Phone B — receiver

1. Connect to **AEROLINK-RX** (password `aerolink123`).
2. Open AEROLINK and tap **RECEIVE**.
3. Tap **TURN ON DISCOVERABLE MODE**.
4. Hold an open palm toward the front camera to perform the catch gesture.
5. When a complete file arrives, choose where to save it.

## What the adaptive detector learns

- Guided pinch samples build a running mean and variance for the thumb-index distance, normalized by palm size.
- Guided open-palm samples build a running mean and variance for finger-extension angles.
- Completed transfers add small positive examples from the accepted gesture, so repeated successful use gradually personalizes the phone's profile.
- Profiles and sensitivity are stored locally in Android SharedPreferences. They are not uploaded or shared between phones.
- A short exponential smoother reduces landmark jitter. Palm-centre movement is used instead of wrist movement to reduce rotation-related false movement.
- The MediaPipe confidence gates are slightly lower than the previous build to help reacquire hands; gesture hold and shape checks still confirm the action.

This is on-device adaptive calibration, not a cloud-trained neural network. It improves thresholds from examples; it does not guarantee perfect recognition. Good lighting, a visible hand, and a consistent camera distance still matter.

## Offline operation

MediaPipe Hand Landmarker runs locally. After the APK is installed, gesture recognition does not need internet. The phone must remain connected to its assigned ESP32 Wi-Fi access point for file transfer.

## Build

The workflow at `.github/workflows/aerolink-apk.yml` downloads the hand-landmarker model and runs `gradle assembleDebug`. Download **AEROLINK-debug-apk** from the latest successful **Build AEROLINK APK** workflow run.

## System architecture

Two Android phones connect to two separate ESP32 access points. ESP32-A forwards the chunks over UART to ESP32-B; the receiver phone downloads the reconstructed file. No cloud, external router, optical transmitter, ultrasonic transducer, or Raspberry Pi is in the transfer path.
