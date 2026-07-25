# EarCEO backend core

## Scope

This layer schedules coding tasks and exposes their state. It does not define
an Android protocol, headset connection, HFP/audio path, ASR/TTS pipeline, or
production deployment.

## Components

```text
FastAPI routes ─┐
MCP tools ──────┼── JsonStateStore ── session.json
Receptionist ───┘          │
       │                   └── checkpoints + durable task records
       └── adapter registry
             ├── mock
             ├── claude-code
             └── openopc
```

The Web process imports one Receptionist from `web/runtime.py`. MCP does not
implement its own state reader/writer; it uses `receptionist.state`.

## State schema

Existing `checkpoints` and `delegations` remain compatible. New task records
are keyed by their canonical ID:

```json
{
  "tasks": {
    "a1b2c3d4": {
      "task_id": "a1b2c3d4",
      "task": "Add a health endpoint",
      "backend": "mock",
      "repo_path": "/tmp/project",
      "context": {"task_id": "a1b2c3d4"},
      "status": "completed",
      "created_at": 0,
      "started_at": 1,
      "completed_at": 2,
      "updated_at": 2,
      "result": {
        "ok": true,
        "summary": "done",
        "files_changed": [],
        "raw": ""
      }
    }
  },
  "checkpoints": [],
  "delegations": []
}
```

A crashed adapter stores `error.type`, `error.message`, and `error.traceback`.
A normal adapter response with `ok=false` is still a real `TaskResult`, and the
task status is `failed`.

## Lifecycle invariants

1. A task ID is created once. `context["task_id"]`, checkpoints, Web responses,
   MCP delegations, and the task record use the same value.
2. A task record is persisted as `pending` before an async runner is scheduled.
3. All adapter events include the task ID.
4. `completed`, `failed`, and `cancelled` are terminal.
5. A completed or normal failed result is reconstructable after process
   restart.
6. An unexpected crash is never silently discarded; `result()` raises
   `TaskExecutionError` from the durable error.
7. Cancellation is best effort and only succeeds for a live task owned by the
   current Receptionist process.

## Storage guarantees

`JsonStateStore.update()` holds both a process-local lock and a POSIX file lock
for the complete read—modify—write transaction. It writes a temporary file,
flushes it with `fsync`, and atomically replaces `session.json`.

Malformed JSON raises `StateCorruptionError`. Operators should preserve the
damaged file for diagnosis and repair or restore it explicitly; the service
does not overwrite it with an empty state.

## Public API

```python
task_id = await receptionist.dispatch_async(...)
result = await receptionist.result(task_id)
cancelled = await receptionist.cancel(task_id)
record = receptionist.task(task_id)
```

`result()` returns `None` for an unknown task or a task that is running in
another process. It raises `TaskCancelledError` for cancelled tasks and
`TaskExecutionError` for unexpected execution crashes.

Web endpoints:

```text
POST /api/dispatch
GET  /api/task/{task_id}
POST /api/task/{task_id}/cancel
GET  /api/board
```

## Verification

```bash
PYTHONPYCACHEPREFIX=/tmp/earceo-pycache \
  .venv/bin/pytest -p no:cacheprovider -q
```

The suite covers thread/process state contention, corrupt JSON, canonical IDs,
async and restored results, callback isolation, failure persistence,
cancellation, Web dispatch/cancel, and the MCP delegation lifecycle.
