# AEROLINK — two ESP32-S3 boards + two phones only

This version intentionally has **no optical transmitter, no ultrasonic transducer, no Raspberry Pi, no laptop, no internet, and no external router**.

## Actual system

Phone A (sender)
→ joins Wi-Fi access point `AEROLINK-TX` on ESP32-A
→ selects a file and uses the front camera for the pinch-and-release gesture
→ sends small HTTP file chunks to ESP32-A

ESP32-A
→ forwards framed packets over a three-wire UART connection

ESP32-B
→ validates each packet with CRC16
→ stores the reconstructed file in its LittleFS flash partition
→ serves receiver status and the completed file over its own Wi-Fi access point

Phone B (receiver)
→ joins `AEROLINK-RX` on ESP32-B
→ opens the same APK and enables discoverable mode
→ performs the open-palm catch gesture
→ waits for a completed file and prompts the user to save it

Both ESP32s host a local webpage at `http://192.168.4.1/`. Each phone is on a different isolated ESP32 Wi-Fi network, so the identical AP address is not a conflict.

## Wi-Fi credentials

| Node | SSID | Password | AP address |
|---|---|---|---|
| ESP32-A | AEROLINK-TX | aerolink123 | 192.168.4.1 |
| ESP32-B | AEROLINK-RX | aerolink123 | 192.168.4.1 |

Join these networks manually in the phone Wi-Fi settings. If Android asks whether to stay connected without internet, choose **stay connected**.

## Wiring the two boards

Only three wires are needed between the ESP32 boards:

| ESP32-A (sender) | ESP32-B (receiver) |
|---|---|
| GPIO 17 / UART TX | GPIO 18 / UART RX |
| GPIO 18 / UART RX | GPIO 17 / UART TX |
| GND | GND |

Power each board separately using its USB connector. **Do not connect the 5 V pins together.** The UART signals use 3.3 V logic.

The sketch uses UART1 at 460800 baud. If GPIO 17 or GPIO 18 is occupied on your exact board variant, change the constants at the top of both sketches to another pair of exposed, free GPIOs.

## Upload firmware

1. Install Arduino IDE and the ESP32 board package.
2. Open `sender/AEROLINK_Sender.ino` and select the correct ESP32-S3 board.
3. Connect ESP32-A by USB and upload the sender sketch.
4. Open `receiver/AEROLINK_Receiver.ino`, connect ESP32-B, and upload the receiver sketch.
5. Wire TX↔RX, RX↔TX and GND↔GND as shown above.
6. Power both boards.
7. Connect Phone A to `AEROLINK-TX` and Phone B to `AEROLINK-RX`.

The sketches embed the webpage in firmware; no LittleFS web-file upload is needed. The receiver uses LittleFS only for the received file.

## Install the phone app

The same Android APK is used on both phones. Build it from `aerolink-phone/` using the GitHub Actions workflow. The APK bundles Google's hand-landmarker model and performs gesture recognition locally, so it does not need internet after installation.

Android's normal camera permission is required. The app opens its own CameraX camera preview after the sender selects a file or the receiver enables discoverable mode. It does not bypass Android camera privacy controls.

## Sender gestures

1. Tap SEND.
2. Choose a file.
3. Hold thumb and index finger together (pinch) until the app says PINCH LOCKED.
4. Open your hand to release/send the file.

## Receiver gesture

1. Tap RECEIVE.
2. Turn on discoverable mode.
3. Hold an open palm toward the front camera to accept/catch.
4. The app checks the ESP32 and prompts to save when the file is complete.

## Packet format

Every UART frame contains a marker, frame type, transfer ID, sequence number, total packet count, payload length, payload and CRC16-CCITT checksum. The filename/start frame is sent before packet zero. The receiver accepts sequential, checksum-valid chunks and marks a file complete only after all chunks arrive.

## File-size note

The receiver stores the file in its flash filesystem. The maximum file size depends on the flash size and the Arduino partition scheme selected for the exact board. Begin with small TXT or JPG files. Choose an ESP32 partition scheme that leaves enough LittleFS space before testing larger media.

## Project scope

This build is the working **Wi-Fi AP + UART cable bridge** version. It is not internet transfer: the file moves locally from one phone, through the two ESP32 boards, to the other phone.