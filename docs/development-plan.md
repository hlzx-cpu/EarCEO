# EarCEO development plan

This plan covers the unified EarCEO monorepo after the verified headset →
PCM/WAV → text-stream pipeline and the first Android/backend implementation.

## Immediate objective

Build the smallest complete command loop:

```text
iFLYBUDS command
  → Android review
  → CEO Gateway
  → coding-vibe delegation
  → progress
  → final result on Android
```

TTS, background operation and polished UI follow after this loop is reliable.

An independent experimental VoIP workstream is documented in
[`android-voip-development.md`](android-voip-development.md). It uses Viaim PCM
when healthy, WebRTC AudioRecord as fallback, Core-Telecom for the Android call
lifecycle, and LiveKit for bidirectional media. It does not change the CEO
Gateway text-command contract.

## Module ownership

| Workstream | `android/` | `backend/` | Shared |
| --- | --- | --- | --- |
| Headset, PCM, WAV, ASR | Own | — | Device testing |
| Command aggregation/review | Own | — | UX language |
| HTTP/SSE client | Own | Contract support | Contract |
| CEO Gateway | Client requirements | Own | Contract |
| CEO/agent orchestration | — | Existing owner | Demo task |
| Progress events | Render/reconnect | Produce/persist | Event names |
| Approvals | UI and response | Policy and pause | Risk rules |
| TTS | Own | Return `speak_text` | Voice wording |

Backend orchestration stays out of the APK, while vendor headset code stays out
of the backend process. Both modules share only the versioned API contract.

## Milestone 1 — Contract and command draft

Goal: turn sentence-level Final callbacks into one deliberate CEO command.

Android tasks:

- introduce a command-session model separate from the transcript display;
- append each Final sentence to the active command draft;
- move to `Review` when the user stops recording;
- add Submit, Edit/Retry and Discard actions;
- generate stable `client_session_id` and `client_turn_id`;
- keep Partial results local and transient.

Backend tasks:

- review and freeze [`backend-contract.md`](backend-contract.md);
- define the allow-listed `project_id` used for the demo;
- choose development authentication and LAN port.

Acceptance:

- one recording creates exactly one reviewable command;
- no network request occurs before explicit Submit;
- repeating a Final callback does not duplicate text;
- transcript contents remain absent from logs.

## Milestone 2 — Text-only Android-to-CEO loop

Goal: submit one command and receive one final response.

Android tasks:

- add a small API client with connect/read/write timeouts;
- create/resume a backend session;
- POST the reviewed command with an idempotency key;
- show `Sending`, `Working`, `Result` and retry states;
- save only non-sensitive session/turn IDs needed for recovery.

Backend tasks:

- add the CEO Gateway service alongside `coding-vibe`;
- implement session creation and command submission;
- map `project_id` to a server-side repository path;
- invoke the existing CEO/delegation flow;
- return one `task.completed` or `task.failed` event.

Acceptance:

- acknowledgement under two seconds on the same LAN;
- one command reaches `coding-vibe`;
- one result is visible on Android;
- resending the same turn ID does not duplicate work;
- phone never receives backend/provider credentials.

## Milestone 3 — Progress, reconnect and queueing

Goal: support long-running agent work without losing state.

Android tasks:

- consume SSE events;
- persist the latest event cursor;
- reconnect with `Last-Event-ID`;
- display milestone, summary and progress percentage;
- queue a reviewed command while temporarily offline;
- allow cancellation before backend acceptance.

Backend tasks:

- persist ordered events and cursor positions;
- translate MCP checkpoints to `task.progress`;
- resume an event stream after disconnection;
- make terminal task state queryable after app restart.

Acceptance:

- a forced Wi-Fi interruption does not lose the task;
- progress events are ordered and not duplicated;
- reopening Android restores the active task;
- the user can distinguish queued, accepted, working and completed states.

## Milestone 4 — Human approval

Goal: prevent the CEO from treating voice as unrestricted authorization.

Android tasks:

- [x] render an approval card with risk level and concise summary;
- [x] support explicit approve/reject with a stable decision ID;
- [x] require a screen tap for R2 initially;
- [x] recover `waiting_approval` and safe public metadata after restart;
- [x] never offer approve for R3 or any voice-only approval.

Backend tasks:

- [x] pause before dispatch using a server-owned project risk policy;
- [x] emit durable `approval.required` and `approval.resolved` events;
- [x] enforce expiration and single-use, idempotent decisions;
- [x] dispatch or abort after an R2 decision and block R3 approval;
- [ ] accept action-specific, mid-task approval boundaries from an adapter.

Acceptance:

- R2 action cannot continue without a recorded decision;
- duplicate decisions are harmless;
- expired approvals cannot resume a task;
- R3 remains blocked.

## Milestone 5 — TTS and half-duplex voice

Goal: return concise CEO feedback through the headset.

Android tasks:

- use Android TextToSpeech for `speak_text`;
- stop recording before playback;
- resume listening only after playback completes;
- support mute/replay/interrupt;
- avoid speaking file lists or long technical details.

Backend tasks:

- include concise, voice-safe `speak_text`;
- keep full details in `summary` and structured fields.

Acceptance:

- EarCEO does not transcribe its own TTS;
- the user can interrupt playback;
- spoken result is understandable in under roughly 20 seconds;
- detailed results remain available on screen.

## Milestone 6 — Product hardening

- Android foreground service for long tasks
- session list and task history
- exponential reconnect with jitter
- bounded offline queue
- TLS and short-lived client tokens
- crash-safe WAV finalization
- accessibility and Chinese/English resources
- battery and thermal testing
- formal privacy retention settings
- product UI replacing the diagnostic probe

## Prioritized backlog

### P0 — implemented and accepted on the primary device

- [x] Freeze API v1 in the monorepo
- [x] Aggregate Final sentences into a command draft
- [x] Add explicit Submit/Discard/Cancel
- [x] Implement session and turn POSTs
- [x] Implement minimal CEO Gateway
- [x] Show accepted, working, completed and failed
- [x] Verify idempotent retry in automated tests
- [x] Verify the full loop on Huawei Mate 60 over LAN
- [x] Gate `claude-code` in one allow-listed disposable repository

### P1 — required for a credible product demo

- [x] SSE progress
- [x] in-process event cursor reconnect
- [x] persist the active turn and event cursor across app restart
- [x] reconcile accepted/working/terminal turns through session query
- [x] bounded reconnect with exponential backoff and jitter
- [x] backend approval persistence and decision contract
- [x] Android approval card and restart recovery
- [ ] Android TTS
- [ ] half-duplex state machine
- [ ] one-command offline queue

### P2 — post-demo hardening

- [ ] foreground service
- [ ] polished Compose UI
- [ ] project selector
- [ ] task history
- [ ] analytics without transcript content
- [ ] production authentication and TLS

## Test matrix

| Area | Test | Evidence |
| --- | --- | --- |
| Command assembly | Multiple Final sentences, one submission | Review screen + one turn ID |
| Idempotency | Repeat POST after timeout | One backend task |
| Reconnect | Disable Wi-Fi during work | Event resume without gaps |
| Restart | Kill and reopen app | Active task restored |
| Privacy | Inspect Android/backend logs | No secrets/full transcripts |
| Approval | Duplicate/expired response | Backend refuses invalid transition |
| TTS | Play result near active headset | No self-transcription |

Continue testing the headset path on Huawei Mate 60 because it is the known-good
device. Keep the OnePlus timeout as a compatibility regression test, not as the
primary integration device.

## Main risks

| Risk | Mitigation |
| --- | --- |
| A sentence-level Final becomes a premature task | Aggregate until Stop + explicit Submit |
| Long CEO task exceeds one HTTP request | Immediate acceptance + SSE events |
| Network retry creates duplicate agent work | Stable idempotency key and durable turn record |
| Phone sends arbitrary repository paths | Backend allow-listed `project_id` |
| Agent requests consequential action | Risk levels and approval pause |
| TTS is captured as a new command | Half-duplex recording/playback state machine |
| Provider credentials leak into APK | Backend owns all provider secrets |

## Next working session

The restart-recovery/LAN milestone and automated Android approval-card phase
are complete. Preserve the verified baseline and do not begin HFP, PCM/WAV
upload, backend ASR, Android TTS, production deployment or a UI overhaul
without a newly agreed scope.

When a follow-up product milestone is selected, the recommended order is:

1. validate R2 approve/reject, R3 reject-only, expiry and force-stop recovery
   on Huawei Mate 60 over the existing peer-capable hotspot;
2. keep real-agent validation constrained to a disposable repository;
3. a bounded one-command offline queue only as a separate phase;
4. foreground-service and product-UI work only after the interaction contract
   is stable.

The detailed continuation brief and ready-to-copy prompt are in
[`next-session-handoff.md`](next-session-handoff.md).
