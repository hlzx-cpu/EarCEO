# EarCEO next-session handoff

Updated: 2026-07-25

## Source of truth

- GitHub repository: `hlzx-cpu/EarCEO`
- Local checkout: `/Users/hanliangzhaoxuan/Documents/Advx26`
- GitHub default branch: `codex/android-sdk-baseline`
- Baseline commit: `d571564`
- Restart-recovery milestone commit: `60572b1`
- Backend approval milestone commit: `f485d80`
- Current development branch: `codex/android-approval-card`
- Development continues only in this monorepo.
- The old `/Users/hanliangzhaoxuan/Developer/AdvX26/EarCEO-Backend`
  checkout is historical and must not receive new work.

The default branch name is unusual, but it is currently the repository's real
default. Do not create a second backend repository or re-import the old
checkout.

## Verified baseline

The repository currently passes:

- backend: 90 tests;
- Android: `testDebugUnitTest`, `assembleDebug`, and `lintDebug`;
- public-repository secret/vendor-artifact safety check;
- localhost Gateway smoke test covering session creation, turn submission,
  ordered SSE progress, and terminal completion.

Run the complete automated baseline with:

```bash
cd /Users/hanliangzhaoxuan/Documents/Advx26
./scripts/test-all.sh
```

JDK 17 is required. On this Mac the verified installation is:

```text
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

## Implemented product path

```text
iFLYBUDS microphone
  → viaim Partial/Final text stream on Android
  → aggregate Final sentences
  → user reviews and explicitly submits one command
  → authenticated CEO Gateway
  → durable Receptionist task
  → persisted SSE progress/result
  → Android status and result UI
```

Implemented reliability properties:

- one stable client session ID;
- one stable client turn ID across retries;
- idempotent session and turn creation;
- project ID mapped to an allow-listed server path;
- pending/running/completed/failed/cancelled task lifecycle;
- durable results and error checkpoints;
- atomic JSON writes with inter-process locking;
- best-effort task cancellation;
- SSE replay with `Last-Event-ID`;
- Android `SharedPreferences` recovery containing only backend session ID,
  active turn ID, non-sensitive status and latest event ID;
- startup session reconciliation for active and terminal turns;
- uncertain submissions retain the stable turn ID without retaining transcript
  text;
- bounded exponential SSE reconnect with jitter;
- server-owned R2/R3 pre-dispatch approval policy;
- durable, expiring and single-use approval decisions;
- atomic approval-to-dispatch claiming and idempotent duplicate responses;
- Android R2 approve/reject and R3 reject-only rendering;
- restart-safe public approval metadata and stable client decision IDs;
- transcript, credentials, WAV, AAR, and generated APK exclusion from logs/Git.

## Completed milestone

Android restart-safe task recovery, native-LAN real-device validation and the
disposable-repository `claude-code` gate are complete on
`codex/android-restart-recovery`.

### Part A — Android active-task recovery

Implemented files:

- `ActiveTurnStore.kt`: minimal, synchronous recovery persistence;
- `SseReconnectPolicy.kt`: 1-second initial delay, exponential growth,
  ±20% jitter and 30-second maximum;
- `EarCeoApiClient.kt`: session query and SSE open/closed callbacks;
- `MainActivity.kt`: startup reconciliation, cursor-first event handling,
  turn filtering, terminal rendering and retry recovery;
- JVM tests for serialization, privacy, cursor advancement, terminal clearing,
  stable turn IDs and reconnect timing.

`./scripts/test-all.sh` passes with 90 backend tests plus Android unit tests,
APK assembly, lint and public-repository safety checks.

### Part B — Huawei LAN validation

The institutional Wi-Fi attempt failed because clients were isolated despite
being in the same `/21`. ADB reverse was used only for diagnosis and was
removed before native-LAN acceptance.

The accepted run used another phone's personal hotspot:

- Mac `10.195.186.160/24`, Mate 60 `10.195.186.219/24`;
- bidirectional ICMP 3/3 and an empty ADB reverse list;
- Gateway logs showed the Mate 60 source address, not loopback;
- iFLYBUDS Pro 3 SPP ready with both earbuds out of the case;
- reviewed slow-mock submission, progress and completion;
- force-stop while active with session ID, turn ID, status and event cursor
  persisted;
- restart produced session resume, session query and event-stream GET without
  a duplicate turn POST;
- restored UI advanced to 60%;
- cancellation reached backend and Android terminal state and cleared all
  `active_turn_*` preference keys.

### Part C — disposable `claude-code` gate

Claude Code 2.1.118 was invoked only after Part B passed. Both the EarCEO
project mapping and subprocess repository allow-list were restricted to:

```text
/private/tmp/earceo-mock-demo-20260725
```

The successful task created only `CLAUDE_GATE_OK.txt` with the requested proof
line and trailing newline. The initial Gateway process could not find `claude`
on PATH and failed before launching a subprocess; restarting it with
`/opt/homebrew/bin` in PATH resolved the environment issue. The ignored Gateway
configuration was returned to `mock` after validation.

### Part D — backend approval contract

The first approval phase is implemented on `codex/approval-state-contract`:

- optional `EARCEO_APPROVAL_RISKS` is a server-owned project-to-risk mapping;
- R2/R3 turns stop at durable `waiting_approval` before a task or subprocess is
  created;
- approval records and `approval.required` events are persisted atomically;
- R2 approve/reject decisions are idempotent and single-use;
- expiry, conflicting decisions, cross-session IDs and reused decision IDs are
  rejected safely;
- R3 exposes only reject and cannot be approved through the mobile API;
- approval resolution, cancellation and expiration are queryable and emitted
  through SSE;
- default `{}` policy preserves the existing Android path.

This is deliberately a pre-dispatch gate. Action-specific mid-task adapter
pauses remain a separate phase.

### Part E — Android approval card and recovery

The minimal Android approval phase is implemented on
`codex/android-approval-card`:

- `waiting_approval` is an active, restart-recoverable command state;
- session query and `approval.required` SSE restore only public approval
  metadata;
- the diagnostic UI shows risk, title, summary, expiry and server-allowed
  choices without a broad redesign;
- R2 exposes explicit approve/reject taps while R3 never exposes approve;
- the client decision ID is synchronously persisted before the network call,
  reused after request uncertainty or process restart, and cannot switch to the
  opposite decision;
- `approval.resolved`, terminal query and task events clear approval recovery
  state safely.

Automated Android unit/build/lint validation is complete. Physical-device
approval scenarios have not yet been claimed; the tracked/default
`EARCEO_APPROVAL_RISKS={}` policy remains unchanged.

## Acceptance criteria

All criteria for this milestone passed:

- [x] all automated checks;
- [x] active-task restoration after force-stop;
- [x] SSE resume from the persisted cursor without a duplicate turn;
- [x] stable turn ID and idempotent retry behavior;
- [x] Huawei + iFLYBUDS + Mac native-LAN mock loop;
- [x] one disposable-repository real-agent task;
- [x] no credentials or full transcript contents in Git or application logs.

## Next objective

Validate the completed approval path on Huawei Mate 60 without widening the
implementation scope:

1. temporarily enable an R2 policy only in ignored local configuration;
2. verify approve dispatches exactly once and reject never dispatches;
3. force-stop/reopen while `waiting_approval` and while one decision response
   is uncertain, confirming the same approval and decision ID are reused;
4. verify expiry becomes terminal and R3 renders reject only;
5. return ignored local policy to `{}` after the mock-device test;
6. if a real-agent gate is needed, use only a new disposable allow-listed
   repository.

## Explicitly deferred

These remain deferred by the current scope even though the recovery/LAN
milestone has passed:

- HFP or a custom headset transport;
- PCM/WAV upload to the backend;
- backend ASR;
- Android TTS and half-duplex playback;
- public Internet deployment;
- production authentication;
- polished Compose UI.

The headset-to-phone path is already working through the vendor SDK. The next
task is reliability of the phone-to-computer command loop, not replacing that
device connection.

## Files to inspect first

- `README.md`
- `docs/backend-contract.md`
- `docs/development-plan.md`
- `android/app/src/main/java/com/earceo/app/MainActivity.kt`
- `android/app/src/main/java/com/earceo/app/CommandDraft.kt`
- `android/app/src/main/java/com/earceo/app/EarCeoApiClient.kt`
- `android/app/src/main/java/com/earceo/app/ActiveTurnStore.kt`
- `android/app/src/main/java/com/earceo/app/SseReconnectPolicy.kt`
- `backend/web/mobile_api.py`
- `backend/receptionist/state.py`
- `backend/receptionist/tests/test_mobile_api.py`
- `backend/receptionist/core.py`
- `scripts/test-all.sh`

## Ready-to-copy prompt

> 继续 `/Users/hanliangzhaoxuan/Documents/Advx26` 中
> `hlzx-cpu/EarCEO` 的开发。先阅读
> `docs/next-session-handoff.md`、`README.md`、
> `docs/backend-contract.md` 和 `docs/development-plan.md`。
> Android 活跃任务与 SSE cursor 持久化、启动恢复、session 查询和指数退避
> 重连已经实现；华为 Mate 60 + iFLYBUDS Pro 3 + Mac 的个人热点原生 LAN
> slow-mock 闭环已经覆盖完成、取消、杀应用恢复和 cursor 续传；随后
> `claude-code` 也只在 allow-list 的一次性仓库中验证通过。后端已经实现默认
> 关闭的服务端 R2/R3 预派发审批策略、持久化审批、过期、单次幂等决策、
> `approval.required/resolved` SSE 和批准后原子派发。自动化基线为后端
> 90 个测试通过，Android test/assemble/lint 通过。Android 现已实现最小审批
> 卡片、R2 显式批准/拒绝、R3 拒绝-only、仅公开元数据持久化、稳定 decision
> ID 和 `waiting_approval` 重启恢复。下一阶段只在 Huawei Mate 60 + iFLYBUDS
> Pro 3 + Mac 的个人热点局域网 mock 上验证批准、拒绝、过期、R3 与杀应用
> 恢复；临时风险策略只写入 ignored 本地配置，结束后恢复 `{}`。如需真实
> agent gate，只用新的 allow-list 一次性仓库。不要同时做离线队列、HFP、
> PCM/WAV 上传、后端 ASR、Android TTS、正式部署或 UI 大改。
