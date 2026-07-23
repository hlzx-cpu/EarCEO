# EarCEO

EarCEO is a voice-first control plane for an AI agent team. A human gives goals and approvals through an iFLYBUDS Pro 3 headset, a CEO agent coordinates work, and specialist agents execute it.

The current milestone is the Android SDK baseline:

- macOS command-line build with JDK 17 and Android API 35
- local integration with the viaim `VisionHeadsetOpen-v1.0.0.aar`
- credentials injected from an untracked `local.properties`
- a minimal screen that verifies SDK initialization without exposing secrets

## Repository safety

This is a public repository. It intentionally does **not** contain:

- viaim AppKey or AppSecret values
- the vendor AAR
- the original vendor demo source
- generated APKs or local Android SDK paths

See [Android development setup](docs/android-development.md) for local build instructions.

## Project layout

```text
android/                 Android app and Gradle wrapper
docs/                    Architecture and development notes
scripts/                 Local validation helpers
```

## Build

After completing the local-only setup in `docs/android-development.md`:

```bash
cd android
./gradlew :app:assembleDebug
```

The APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
