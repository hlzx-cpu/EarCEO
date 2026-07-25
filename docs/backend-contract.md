# Android ↔ CEO API contract

Status: **core MVP v1 implemented** (sessions, turns, SSE and cancellation).
Approval events remain a planned extension.

This contract connects EarCEO Android to the in-repository `backend/` CEO
Gateway. The Gateway reuses the durable Receptionist task core and adapter
boundary derived from `coding-vibe`.

## Design decisions

1. Android sends text, not PCM or WAV.
2. Only a reviewed command draft is submitted; Partial results never leave the
   phone.
3. Multiple viaim Final sentences from one recording session are aggregated
   into one command.
4. REST handles commands and decisions; SSE handles asynchronous events.
5. Every write has a client-generated idempotency key.
6. Android refers to an allow-listed `project_id`; it never sends an arbitrary
   backend filesystem path.
7. The backend returns short `speak_text` separately from detailed screen text.

## Transport

Development:

```text
http://<backend-lan-ip>:8787
Authorization: Bearer <development-token>
Content-Type: application/json
```

Production must use HTTPS and a short-lived client token. The backend must not
expose DeepSeek, Deepgram, LiveKit, OpenOPC or repository credentials to
Android.

## Session lifecycle

### Create or resume a session

```http
POST /v1/sessions
```

Request:

```json
{
  "client_session_id": "01J...ULID",
  "client": {
    "name": "earceo-android",
    "version": "0.2.0",
    "locale": "zh-CN"
  },
  "project_id": "adventurex-demo"
}
```

Response:

```json
{
  "session_id": "ses_01J...",
  "status": "ready",
  "event_stream": "/v1/sessions/ses_01J.../events",
  "resume_cursor": "0"
}
```

`client_session_id` makes application restart/resume idempotent.

## Submit a command

```http
POST /v1/sessions/{session_id}/turns
Idempotency-Key: <client_turn_id>
```

Request:

```json
{
  "client_turn_id": "turn_01J...",
  "project_id": "adventurex-demo",
  "input": {
    "type": "final_transcript",
    "text": "请让团队检查项目并为 API 增加 health endpoint。",
    "locale": "zh-CN",
    "source": "viaim-text-stream"
  },
  "client_context": {
    "headset": "iFLYBUDS Pro 3",
    "wav_captured": true
  }
}
```

Accepted response:

```json
{
  "turn_id": "turn_01J...",
  "status": "accepted",
  "submitted_at": "2026-07-25T10:00:00Z"
}
```

The response only confirms durable acceptance. CEO reasoning and agent work are
reported through the event stream.

## Cancel a command

```http
POST /v1/sessions/{session_id}/turns/{turn_id}/cancel
```

Cancellation is best-effort and succeeds only while the current backend
process owns the live adapter.

## Event stream

```http
GET /v1/sessions/{session_id}/events
Accept: text/event-stream
Last-Event-ID: <resume_cursor>
```

All event payloads share this envelope:

```json
{
  "event_id": "evt_01J...",
  "session_id": "ses_01J...",
  "turn_id": "turn_01J...",
  "sequence": 4,
  "timestamp": "2026-07-25T10:00:03Z",
  "type": "task.progress",
  "data": {}
}
```

Required MVP events:

| Type | Purpose |
| --- | --- |
| `turn.accepted` | Durable acknowledgement |
| `assistant.message` | Short CEO response for screen/TTS |
| `task.progress` | Checkpoint from `coding-vibe` |
| `approval.required` | Human decision is required |
| `task.completed` | Final result |
| `task.failed` | Terminal failure |
| `session.warning` | Recoverable connection or backend warning |

Progress example:

```json
{
  "type": "task.progress",
  "data": {
    "milestone": "delegating-to-ceo",
    "message": "需求已整理，正在交给工程团队。",
    "progress_pct": 20
  }
}
```

Completion example:

```json
{
  "type": "task.completed",
  "data": {
    "status": "completed",
    "summary": "已增加 health endpoint，并完成测试。",
    "speak_text": "任务完成，健康检查接口已经加入并通过测试。",
    "files_changed": [
      "src/api/health.py",
      "tests/test_health.py"
    ]
  }
}
```

For voice output, Android reads `speak_text`. Detailed fields remain visible on
screen but are not read aloud automatically.

## Approval flow

Example event:

```json
{
  "type": "approval.required",
  "data": {
    "approval_id": "apr_01J...",
    "risk_level": "R2",
    "title": "Deploy test build?",
    "summary": "The agent is ready to deploy to the shared test environment.",
    "choices": ["approve", "reject"],
    "expires_at": "2026-07-25T10:10:00Z"
  }
}
```

Decision:

```http
POST /v1/sessions/{session_id}/approvals/{approval_id}
Idempotency-Key: <client_decision_id>
```

```json
{
  "client_decision_id": "decision_01J...",
  "decision": "approve"
}
```

R3 operations must not offer an `approve` action through voice alone.

## Backend adapter boundary

The CEO Gateway lives at `backend/web/mobile_api.py`. Its responsibilities are:

1. authenticate the Android client;
2. map `project_id` to a server-side allow-listed repository;
3. persist sessions, turns, cursors and idempotency keys;
4. turn a submitted command into a `coding-vibe` CEO/delegation request;
5. translate MCP checkpoints into SSE events;
6. translate CEO results into `task.completed`;
7. pause on approval boundaries.

The first implementation may adapt the existing MCP flow:

```text
Android command
  → CEO Gateway
  → coding_vibe_checkpoint(requirements-gathered)
  → coding_vibe_delegate_to_ceo(...)
  → claim / execute / complete
  → checkpoint events
  → SSE
```

Alternatively, `CodingVibeAgent.delegate_coding` can be refactored into a
transport-independent service and called by both LiveKit and the CEO Gateway.
The gateway should not instantiate a second copy of the agent team.

## Android state mapping

| Android state | Entry condition | Exit |
| --- | --- | --- |
| `Idle` | No active command | Start recording |
| `Listening` | Headset recording active | Stop recording |
| `Review` | Final sentences aggregated | Submit or discard |
| `Sending` | Command POST in flight | Accepted or error |
| `Working` | Accepted/progress event | Complete/approval/error |
| `Approval` | `approval.required` | Approve/reject |
| `Result` | Completed/failed | New command |
| `Speaking` | TTS active | Playback complete |
| `OfflineQueued` | No network after review | Retry/cancel |

## Error model

HTTP errors use:

```json
{
  "error": {
    "code": "PROJECT_NOT_ALLOWED",
    "message": "The requested project is not available.",
    "retryable": false,
    "request_id": "req_01J..."
  }
}
```

Minimum codes:

- `UNAUTHORIZED`
- `INVALID_COMMAND`
- `PROJECT_NOT_ALLOWED`
- `DUPLICATE_TURN`
- `CEO_UNAVAILABLE`
- `TASK_TIMEOUT`
- `APPROVAL_EXPIRED`
- `INTERNAL_ERROR`

Android retries only when `retryable` is true and always reuses the original
idempotency key.

## Logging and privacy

Android logs:

- may log IDs, states, latency and error codes;
- must not log credentials or full transcripts;
- must not upload WAV automatically.

Backend logs:

- may log request IDs, project IDs and state transitions;
- should redact command text by default;
- must never return backend/provider secrets to Android.

## Compatibility

Every request uses the `/v1` prefix. Additive response fields are allowed.
Removing or changing a field requires a new API version.
