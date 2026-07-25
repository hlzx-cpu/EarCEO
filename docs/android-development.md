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
earceo.backendUrl=http://192.168.1.100:8787
earceo.apiToken=<development-token>
earceo.projectId=adventurex-demo
```

`local.properties` is ignored by Git. The current AppSecret path is for local competition testing only; a production build must use `ClientToken` or `Signed` credentials issued through a trusted backend.
The backend token is also development-only and is compiled into the debug APK;
production must use an issued short-lived device token.

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
./scripts/test-all.sh
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

## Huawei Mate 60 real-device test

Tested on 2026-07-24 with a Huawei Mate 60 (`BRA-AL00`) reporting Android 12 /
API 31 through its Android compatibility layer:

- macOS ADB connection, APK installation and launch: passed
- microphone, nearby-device and location permissions: passed
- iFLYBUDS Pro 3 SPP, battery and out-of-case state: passed
- PCM live recording: passed without credentials or `text-stream`
- authenticated SDK initialization: passed; platform reported `voice-stream`
  and `text-stream`
- live `text-stream`: start, Partial results, Final results and clean end passed
- simultaneous PCM, WAV and text-stream capture: passed
- captured format: signed 16-bit little-endian PCM, 16 kHz, mono
- WAV finalization and ADB export: passed
- inspected sample: 69.024 seconds, 2,208,812 bytes, mean volume -25.7 dB,
  peak volume -2.1 dB
- text-stream sample: 12.2-second WAV, 391,212 bytes, with multiple Partial and
  Final callbacks and no live-record or text-stream error

This A/B result strongly points to a OnePlus / ColorOS compatibility issue for
the earlier `startLiveRecord timeout`, rather than a general earbud, firmware,
SPP or PCM credential requirement.

### 2026-07-25 recovery-build device evidence

The restart-recovery build was installed on the same Huawei Mate 60:

- authenticated SDK initialization and `text-stream` capability: passed;
- iFLYBUDS Pro 3 SPP and battery query: passed (approximately 96–98% during
  the run);
- simultaneous PCM, background WAV and text-stream Final delivery: passed;
- reviewed command UI: reached with editable text;
- application logs continued to record only result length and state, not
  transcript contents.

The native LAN Gateway acceptance test did **not** pass on the available
institutional Wi-Fi. The Mac (`30.201.208.154`) and phone
(`30.201.209.14`) received addresses in the same `/21`, but neither endpoint
could ping the other. The Mac application firewall was disabled and routing
selected `en0`, while the Android session request timed out. This is consistent
with access-point client isolation rather than an EarCEO HTTP or firewall
failure.

An ADB reverse connection to `127.0.0.1:8787` was then used only as a transport
diagnostic. Through that USB path the following passed:

- session creation, session query, turn submission and ordered SSE progress;
- completion rendering and terminal recovery-state clearing;
- a force-stop/reopen while the turn was `working`;
- persistence of session ID, turn ID, non-sensitive status and the latest event
  cursor before the force-stop;
- startup session reconciliation and a new SSE connection after reopening;
- eventual completion without resubmitting the turn;
- cancellation of a second slow-mock turn, with both the backend query and
  Android UI state reaching `cancelled`;
- recovery preferences returning to only the stable client session ID after
  each terminal result.

The temporary ADB reverse rule was removed after testing. These results validate
the application recovery state machine, but they are not recorded as LAN
acceptance because the phone traffic travelled over USB.

### 2026-07-25 personal-hotspot LAN acceptance

The Mac and Mate 60 were then moved to another phone's personal hotspot:

- Mac: `10.195.186.160/24`;
- Mate 60: `10.195.186.219/24`;
- Mac-to-phone and phone-to-Mac ICMP: 3/3 in both directions;
- ADB reverse list: empty throughout the acceptance run;
- Gateway request source: `10.195.186.219`, proving the app used native LAN
  traffic rather than the USB diagnostic path.

The rebuilt APK connected to iFLYBUDS Pro 3 over SPP and reported both earbuds
out of the case with 71% left/right battery. SDK authentication and
`text-stream` capability also remained available.

The slow-mock loop passed:

1. a reviewed safe command produced session creation, turn submission, SSE
   progress, and a completed result;
2. before a second restart test, Android preferences contained only the
   backend session ID, active turn ID, status and a non-empty event cursor;
3. the app was force-stopped while the turn was still `accepted`;
4. after reopening, Gateway observed `POST session`, `GET session`, then
   `GET events`, with no second `POST turn`;
5. the restored UI continued at 60% and advanced from the saved cursor;
6. cancellation returned HTTP 200, backend session query and Android UI both
   reached `cancelled`, and the number of persisted `active_turn_*` keys
   returned to zero.

Only after that native-LAN mock gate passed, `claude-code` 2.1.118 was tested
through the Gateway with both the project mapping and subprocess repository
allow-list restricted to `/private/tmp/earceo-mock-demo-20260725`. The successful
task created only `CLAUDE_GATE_OK.txt`, containing the requested one-line proof
plus a trailing newline. The Gateway configuration was returned to `mock` after
the test. No EarCEO source file was exposed to the Claude subprocess.

### Android approval-card automated baseline

The Android approval client now:

- treats `waiting_approval` as an active recoverable turn;
- restores public approval metadata from session query or SSE;
- persists a stable decision ID before sending approve/reject;
- allows only the same decision to retry after an uncertain response;
- hides approve for R3 and clears approval state after resolution.

JVM tests cover safe-field persistence, stable decision reuse, conflicting
retry prevention, R3 reject-only behavior, and command/turn recovery state.
`testDebugUnitTest`, `assembleDebug`, and `lintDebug` pass. This is automated
evidence only: a Huawei approval-card physical-device run is the next separate
acceptance step, and the default risk mapping remains `{}` meanwhile.

## Export and inspect a PCM-only recording

After stopping a live recording, EarCEO displays the WAV filename, duration and
size. Tap **导出最近录音 WAV**, choose a user-visible location such as
**下载**, and confirm the save operation.

For development inspection over ADB, list the app cache and pull the selected
file:

```bash
adb shell ls -l /sdcard/Android/data/com.earceo.app/cache
adb pull \
  /sdcard/Android/data/com.earceo.app/cache/earceo-YYYYMMDD-HHMMSS.wav \
  ./earceo-test.wav
```

The exported file should report `pcm_s16le`, 16 kHz and one channel. Listening
to this WAV is the evidence that the received PCM contains intelligible speech;
frame and byte counters alone prove transport but not speech quality.

Real-device procedure:

1. Pair the iFLYBUDS Pro 3 in Android system Bluetooth settings.
2. Enable Developer options and USB debugging, then accept the computer key.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Open EarCEO and allow nearby-device/location access.
5. Take the earbuds out of the case and wear them.
6. Tap **连接 iFLYBUDS** and wait for `SPP 已就绪`.
7. Tap **开始现场录音**, allow microphone access, and speak.
8. Stop recording, export the WAV and listen to it.
9. Confirm the PCM frame/byte counters increase. If `text-stream` is enabled,
   also confirm Partial and Final text appears.
