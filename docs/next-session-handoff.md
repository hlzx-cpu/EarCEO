# EarCEO next-session handoff

Updated: 2026-07-25

## Source of truth

- GitHub repository: `hlzx-cpu/EarCEO`
- Local checkout: `/Users/hanliangzhaoxuan/Documents/Advx26`
- GitHub default branch: `codex/android-sdk-baseline`
- Baseline commit: `d571564`
- Current development branch: `codex/android-restart-recovery`
- Development continues only in this monorepo.
- The old `/Users/hanliangzhaoxuan/Developer/AdvX26/EarCEO-Backend`
  checkout is historical and must not receive new work.

The default branch name is unusual, but it is currently the repository's real
default. Do not create a second backend repository or re-import the old
checkout.

## Verified baseline

The repository currently passes:

- backend: 82 tests;
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

`./scripts/test-all.sh` passes with 82 backend tests plus Android unit tests,
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

No further product feature was started. Ask for a newly agreed scope before
implementing approval cards, an offline queue, TTS, foreground service or
product UI. The recommended next product slice is approval-state/response
semantics with backend tests, followed by a minimal Android approval card.

## Explicitly deferred

These remain deferred by the current scope even though the recovery/LAN
milestone has passed:

- HFP or a custom headset transport;
- PCM/WAV upload to the backend;
- backend ASR;
- Android TTS and half-duplex playback;
- approval cards;
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
> `claude-code` 也只在 allow-list 的一次性仓库中验证通过。自动化基线为后端
> 82 个测试通过，Android test/assemble/lint 通过。下一步尚未开始；请先确认
> 新的产品范围。建议从审批状态/响应契约和后端测试开始。不要擅自做 HFP、
> PCM/WAV 上传、后端 ASR、Android TTS、正式部署或 UI 大改。所有代码继续
> 放在 EarCEO 单仓库。
