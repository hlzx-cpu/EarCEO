# EarCEO next-session handoff

Updated: 2026-07-25

## Source of truth

- GitHub repository: `hlzx-cpu/EarCEO`
- Local checkout: `/Users/hanliangzhaoxuan/Documents/Advx26`
- GitHub default branch: `codex/android-sdk-baseline`
- Integrated implementation commit: `024fb27`
- Development continues only in this monorepo.
- The old `/Users/hanliangzhaoxuan/Developer/AdvX26/EarCEO-Backend`
  checkout is historical and must not receive new work.

The default branch name is unusual, but it is currently the repository's real
default. Do not create a second backend repository or re-import the old
checkout.

## Verified baseline

The repository currently passes:

- backend: 81 tests;
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
- transcript, credentials, WAV, AAR, and generated APK exclusion from logs/Git.

## Next objective

Complete restart-safe task recovery, then validate the existing phone–headset–
computer loop on the Huawei Mate 60 over the same LAN.

This is the next engineering milestone because the basic command loop exists,
but `MainActivity` currently keeps the active turn and latest SSE cursor only
in memory. An activity/process restart can therefore lose the live UI even
though the backend task continues correctly.

### Part A — Android active-task recovery

Implement:

1. A small `ActiveTurnStore` backed by `SharedPreferences`.
2. Persist only:
   - backend session ID;
   - active turn ID;
   - non-sensitive task status;
   - latest SSE event ID.
3. Do not persist the full transcript or backend token in this store.
4. Persist the event cursor immediately after every valid SSE event.
5. Add `GET /v1/sessions/{session_id}` support to `EarCeoApiClient`.
6. On app startup:
   - create or resume the client session;
   - query its current turns;
   - restore an accepted/working turn;
   - reconnect SSE with the stored `Last-Event-ID`;
   - render an already-terminal result without resubmission.
7. Add exponential SSE reconnect delays with jitter and a sensible maximum.
8. Clear active-turn recovery state only after a terminal result is durably
   rendered or the user explicitly discards it.
9. Keep the same `client_turn_id` for all retries.

Add unit tests for:

- recovery-state serialization;
- cursor advancement;
- terminal-state clearing;
- stable turn ID after retry/recreation;
- duplicate events not regressing the cursor;
- no transcript stored in recovery preferences.

### Part B — Huawei LAN validation

After automated tests pass:

1. Keep `EARCEO_BACKEND=mock`.
2. Configure an ignored `backend/.env` with:
   - a long development token;
   - `EARCEO_PROJECTS` pointing to a disposable demo repository.
3. Configure the matching ignored `android/local.properties` with:
   - the Mac LAN URL, for example `http://<mac-ip>:8787`;
   - the same token;
   - the same project ID.
4. Start `./scripts/run-backend.sh`.
5. Install the debug APK on Huawei Mate 60.
6. Connect iFLYBUDS Pro 3 and submit one reviewed command.
7. Verify on the phone:
   - accepted;
   - at least one progress update;
   - completed result;
   - cancellation of a second command.
8. While a command is running, temporarily interrupt Wi-Fi or restart the app
   and verify cursor-based recovery without duplicate work.
9. Record redacted evidence in `docs/android-development.md`; never record
   tokens or full transcripts.

### Part C — first real CEO task

Only after the mock LAN loop is reliable:

1. Use a disposable Git repository, not the EarCEO repository.
2. Set `EARCEO_BACKEND=claude-code`.
3. Allow-list only that disposable repository.
4. Submit a safe and visible task such as adding a small health endpoint.
5. Verify files changed, tests, cancellation behavior, and the summary shown
   on Android.

The current Claude Code adapter may launch a high-authority subprocess.
Therefore this first run must stay inside a disposable repository and must not
be exposed beyond the trusted development LAN.

## Acceptance criteria

The next milestone is complete only when:

- all existing automated checks still pass;
- killing and reopening Android restores the active task;
- SSE resumes after the last persisted event without duplicated UI updates;
- retrying a submission does not start a second backend task;
- the Huawei + iFLYBUDS + Mac mock loop passes;
- one disposable-repository real-agent task completes;
- no credentials or full transcript contents appear in Git or logs.

## Explicitly deferred

Do not implement these until the recovery/LAN milestone passes:

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
- `backend/web/mobile_api.py`
- `backend/receptionist/core.py`
- `scripts/test-all.sh`

## Ready-to-copy prompt

> 继续 `/Users/hanliangzhaoxuan/Documents/Advx26` 中
> `hlzx-cpu/EarCEO` 的开发。先阅读
> `docs/next-session-handoff.md`、`README.md`、
> `docs/backend-contract.md` 和 `docs/development-plan.md`。
> 当前手机审核提交、Gateway、可靠任务生命周期、SSE 进度/结果及取消已经实现，
> 自动化基线为后端 81 个测试通过，Android test/assemble/lint 通过。
> 下一阶段先实现 Android 活跃任务与 SSE cursor 的持久化、应用重启恢复、
> 指数退避重连及相应测试；然后指导并执行华为 Mate 60 +
> iFLYBUDS Pro 3 + Mac 的局域网 mock 真机闭环，最后只在一次性测试仓库上验证
> `claude-code`。不要做 HFP、PCM 上传、后端 ASR、TTS、正式部署或 UI 大改。
> 所有代码继续放在 EarCEO 单仓库，完成后运行 `./scripts/test-all.sh`，
> 更新文档并提交到 `codex/` 分支。
