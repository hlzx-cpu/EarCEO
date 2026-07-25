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
- the diagnostic activity exposes start/end controls and posts an ongoing
  call notification.

This is a development spike, not completed real-phone acceptance. Before that
acceptance, the call owner must move from `MainActivity` into a foreground
service, LiveKit room disconnect/reconnect events must drive the Telecom state,
and Telecom must remain the single owner of endpoint routing.

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
