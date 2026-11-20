# AEROLINK iOS

Native SwiftUI + AVFoundation + Vision client for AEROLINK.

## Build without a Mac

GitHub Actions can build the iOS project on a hosted macOS runner.

1. Open the repository's **Actions** tab.
2. Select **AEROLINK iOS Build**.
3. Press **Run workflow**.
4. Download the **AEROLINK-unsigned-ipa** artifact.

### Important

The generated IPA is **unsigned**. iOS will not install an unsigned IPA through the normal Files app. Installation requires signing with an Apple Developer identity using a supported sideloading/build workflow.

For a school demo, the simplest route is to use an Apple ID with a signing-capable sideloading tool or a Mac/cloud Mac. The source project itself is ready for Xcode.
