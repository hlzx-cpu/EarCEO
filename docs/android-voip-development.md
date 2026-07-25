# Android VoIP development

This workstream is deliberately separate from the existing text-command path.
It does not add ASR, TTS, SIP, PSTN, HFP control, or raw-audio upload to the CEO
Gateway.

## Target stack

```text
Viaim PCM (preferred) / WebRTC AudioRecord (fallback)
  → Android Core-Telecom (call lifecycle and system endpoints)
  → LiveKit/WebRTC (bidirectional realtime media)
```

Core-Telecom does not carry media. LiveKit does not own the call lifecycle.
Viaim remains a vendor-specific audio source rather than a platform-wide
calling implementation.

## Current implementation stage

The `codex/audio-stream-uplink` branch contains the first Android integration:

- `AudioSourcePolicy` promotes Viaim only after healthy PCM and immediately
  falls back on disconnect, timeout, or failed WebRTC injection;
- `Pcm16FrameBuffer` converts Viaim's 16 kHz mono PCM into the frame rate and
  channel count requested by WebRTC;
- `ViaimAudioBufferCallback` replaces the LiveKit microphone buffer only while
  Viaim is selected and otherwise leaves WebRTC AudioRecord untouched;
- `LiveKitCallTransport` connects, publishes local audio, and plays subscribed
  remote audio;
- `TelecomCallCoordinator` registers one outgoing self-managed audio call;
- the diagnostic activity exposes start/end controls;
- `EarCeoCallService` supplies the microphone foreground-service identity and
  posts the ongoing `CallStyle` notification required by current Android.

This is a development spike, not completed real-phone acceptance. Before that
acceptance, media/session ownership must move from `MainActivity` into the
foreground service, LiveKit room disconnect/reconnect events must drive the
Telecom state, and Telecom must remain the single owner of endpoint routing.

## Local LiveKit configuration

For a disposable development room only:

```properties
livekit.url=wss://<livekit-host>
livekit.devToken=<short-lived-room-token>
```

The debug token is compiled out of release builds. Production must obtain a
short-lived, least-privilege room token from a backend endpoint.

## What can be tested without a physical phone

Pure JVM tests cover source priority, timeout/fallback, PCM conversion, and
call/media ordering. An Android emulator can additionally cover permissions,
basic Core-Telecom callbacks, notification behavior, and a LiveKit test room
using the host microphone.

The emulator cannot reliably pass through macOS Bluetooth or the Viaim SPP
link. A physical Android phone is required for:

- the vendor AAR and real Viaim PCM;
- Huawei Core-Telecom behavior;
- Bluetooth call endpoints and headset buttons;
- concurrent SPP, Telecom, and WebRTC resource behavior;
- latency, echo cancellation, route switching, and disconnect recovery.

ADB is useful for installation, logs, and automation, but does not replace the
physical device.

## OnePlus Open test evidence (2026-07-25)

The debug APK was installed on a physical OnePlus Open (`CPH2551`, Android 16)
and exercised against a disposable LiveKit server on the Mac.

Verified:

- Viaim SDK initialization succeeded and exposed `voice-stream` and
  `text-stream`;
- WebRTC opened Android `AudioRecord` at 48 kHz mono and Android reported an
  active VOIP microphone session;
- Core-Telecom registered an EarCEO self-managed account and advanced the call
  to `ACTIVE`;
- the Android 16 `CallStyle` restriction was reproduced: an Activity-posted
  call notification is rejected unless it belongs to a foreground service,
  user-initiated job, or full-screen intent;
- moving the notification to `EarCeoCallService` resolved that failure, and
  the service ran with microphone foreground-service type;
- ending the call removed the Telecom call, foreground service, notification,
  and audio focus.

Not yet accepted:

- LiveKit signalling reached the local server, but no RTP audio track could be
  accepted over the current personal-hotspot topology. The hotspot isolates
  the phone from the Mac. ADB reverse carries TCP only, while Android WebRTC
  produced UDP ICE candidates, so it cannot stand in for a reachable media
  network.

The next real-media run needs either:

1. phone and Mac on the same reachable LAN or Tailscale tailnet; or
2. a public LiveKit test deployment with valid short-lived credentials.

Do not report the media loop as complete until the server shows one published
audio track and a subscriber or recorder confirms non-zero audio packets.
