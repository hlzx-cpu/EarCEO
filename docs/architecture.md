# EarCEO architecture

EarCEO uses two AI layers under one human decision layer:

1. **Human chairperson** — defines the goal, constraints and risk boundary, then approves consequential actions.
2. **CEO agent** — compiles voice intent into constrained commands, delegates work, tracks state, applies policy and compresses results.
3. **Worker agents** — perform research, implementation, testing and review through a common task interface.

The target voice path is:

```text
iFLYBUDS Pro 3 microphone
  -> viaim live recording and final transcript
  -> CEO orchestrator
  -> worker agents
  -> decision packet
  -> Android TextToSpeech
  -> headset playback
```

The MVP uses half-duplex audio. Recording stops before TTS playback and resumes after playback finishes, preventing the app from interpreting its own voice as a new command.

## Risk boundary

| Level | Example | Default policy |
| --- | --- | --- |
| R0 | Read files, search, summarize | Automatic |
| R1 | Generate a draft, run tests | Repeat intent, allow voice cancellation |
| R2 | Send a message, write an online document, deploy a test build | Explicit confirmation |
| R3 | Delete, pay, change permissions, production deploy | Never complete by voice alone |
