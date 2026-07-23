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

Set `sdk.dir` to the Android SDK path. For compilation only, the credential fields may remain empty. For local headset testing, add credentials issued for the competition:

```properties
sdk.dir=/Users/your-name/Library/Android/sdk
viaim.appKey=
viaim.appSecret=
```

`local.properties` is ignored by Git. The current AppSecret path is for local competition testing only; a production build must use `ClientToken` or `Signed` credentials issued through a trusted backend.

## Build and validate

```bash
cd android
./gradlew :app:assembleDebug
../scripts/check-public-repo.sh
```

Before a real-device test:

1. Pair the iFLYBUDS Pro 3 in Android system Bluetooth settings.
2. Confirm the viaim console has enabled `text-stream`.
3. Install the debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Start with SDK initialization only; connection, recording and ASR are added in the next milestone.
