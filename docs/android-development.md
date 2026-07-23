# Android development setup

## Supported host

Windows is not required. The viaim delivery is a standard Android AAR and can be built on macOS.

This project is pinned to the vendor demo's compatible toolchain:

| Component | Version |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 8.7.2 |
| Gradle | 8.9 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| SDK Build Tools | 34.0.0 or newer compatible revision |

## Install the command-line toolchain on macOS

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
```

Use JDK 17 for Android commands without replacing another system JDK:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:$PATH"
```

Install the minimum Android SDK packages:

```bash
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;34.0.0"
```

## Add the vendor SDK locally

Obtain `VisionHeadsetOpen-v1.0.0.aar` from the official competition delivery and copy it to:

```text
android/app/libs/VisionHeadsetOpen-v1.0.0.aar
```

The expected SHA-256 for the delivery inspected on 2026-07-23 is:

```text
d6525adfc96ae304fec458608afc8922ecfbd2d0e8eef94175077018575ef08a
```

The AAR is ignored by Git because the public repository does not establish redistribution rights for the vendor binary.

## Configure local properties

Copy the example:

```bash
cp android/local.properties.example android/local.properties
```

Set `sdk.dir` to the Android SDK path. For compilation, SPP connection and PCM-only testing, the credential fields may remain empty. To test realtime transcription, add credentials issued for the competition:

```properties
sdk.dir=/Users/your-name/Library/Android/sdk
viaim.appKey=
viaim.appSecret=
```

`local.properties` is ignored by Git. The current AppSecret path is for local competition testing only; a production build must use `ClientToken` or `Signed` credentials issued through a trusted backend.

## Check `text-stream` authorization

Look in the viaim AI Open application console for the competition app's enabled
services or abilities. Search for these names because console wording may vary:

- `text-stream`
- realtime text stream / 实时文本流
- ASR
- enabled service IDs / 已开通服务

If the competition console does not expose this list, send the AppKey (never the
AppSecret) to the viaim competition support contact and ask whether
`text-stream` is enabled.

The runtime callback is authoritative. EarCEO displays:

- **已开通** when SDK initialization succeeds and `hasTextStream` is true;
- **凭证有效但未报告该能力** when initialization succeeds without it;
- **PCM-only** when no credentials are configured or authorization fails.

Missing `text-stream` affects only partial/final ASR callbacks. It does not block
Bluetooth pairing, SPP connection, battery queries, or raw 16 kHz mono PCM
testing. Therefore most hardware debugging can continue before the competition
permission is confirmed.

## Build and validate

```bash
cd android
./gradlew :app:assembleDebug :app:lintDebug
../scripts/check-public-repo.sh
```

## OnePlus / ColorOS real-device test

Tested on 2026-07-24 with a OnePlus Ace 3 Pro (PJX110) running ColorOS 16.0.5:

- macOS ADB connection: passed
- APK installation and launch: passed
- nearby-device/location permission flow: passed
- iFLYBUDS Pro 3 SPP connection: passed
- left/right battery query: passed
- PCM start: two attempts returned `9999 startLiveRecord timeout`; the second
  attempt confirmed both earbuds were out of the charging case and all Android
  permissions were granted, so the remaining investigation is the vendor
  SDK/earbud-firmware live-record handshake (not macOS, ADB, ColorOS permission,
  SPP, or `text-stream`)

Real-device procedure:

1. Pair the iFLYBUDS Pro 3 in Android system Bluetooth settings.
2. Enable Developer options and USB debugging, then accept the computer key.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Open EarCEO and allow nearby-device/location access.
5. Take the earbuds out of the case and wear them.
6. Tap **连接 iFLYBUDS** and wait for `SPP 已就绪`.
7. Tap **开始现场录音**, allow microphone access, and speak.
8. Confirm the PCM frame/byte counters increase. If `text-stream` is enabled,
   also confirm Partial and Final text appears.
