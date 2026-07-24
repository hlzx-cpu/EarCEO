# EarCEO development plan

This plan covers the product work after the verified headset → PCM/WAV →
text-stream → Android pipeline.

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

## Ownership

| Workstream | EarCEO / Android | coding-vibe / backend | Shared |
| --- | --- | --- | --- |
| Headset, PCM, WAV, ASR | Own | — | Device testing |
| Command aggregation/review | Own | — | UX language |
| HTTP/SSE client | Own | Contract support | Contract |
| CEO Gateway | Client requirements | Own | Contract |
| CEO/agent orchestration | — | Existing owner | Demo task |
| Progress events | Render/reconnect | Produce/persist | Event names |
| Approvals | UI and response | Policy and pause | Risk rules |
| TTS | Own | Return `speak_text` | Voice wording |

EarCEO must not copy `CodingVibeAgent`, MCP delegation state, or OpenOPC into
Android. `coding-vibe` must not take over iFLYBUDS SDK integration.

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

- render an approval card with risk level and concise summary;
- support explicit approve/reject;
- require a screen tap for R2 initially;
- never offer voice-only approval for R3.

Backend tasks:

- pause orchestration at an approval boundary;
- emit `approval.required`;
- enforce expiration and single-use decisions;
- resume or abort after the decision.

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

### P0 — required for the next demo

- [ ] Freeze API v0.1 with the backend owner
- [ ] Aggregate Final sentences into a command draft
- [ ] Add explicit Submit/Discard
- [ ] Implement session and turn POSTs
- [ ] Implement minimal CEO Gateway
- [ ] Show accepted, working, completed and failed
- [ ] Verify idempotent retry

### P1 — required for a credible product demo

- [ ] SSE progress
- [ ] event cursor reconnect
- [ ] approval card
- [ ] Android TTS
- [ ] half-duplex state machine
- [ ] active-task recovery after restart

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

Do these in order:

1. review `backend-contract.md` with the `coding-vibe` owner;
2. agree on `project_id`, gateway URL, development token and event names;
3. implement Android command aggregation and Review state;
4. have the backend owner implement only the three initial operations:
   session creation, turn submission and completion event;
5. run one deterministic demo task before adding TTS or UI polish.

The first integration task should be safe, visible and repeatable—for example,
asking the agent team to add or update a small health endpoint in a disposable
demo repository.
