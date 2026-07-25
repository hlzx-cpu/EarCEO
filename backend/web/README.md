# EarCEO backend Web API

Text dispatch, task inspection, cancellation, and the existing kanban UI. Voice
routes are retained from upstream but are outside the current backend-core
milestone.

## Run

```bash
cd web
bash run.sh
# → http://127.0.0.1:5091
```

Or with your venv already active:
```bash
uvicorn web.server:app --host 127.0.0.1 --port 5091
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Mobile UI (index.html) |
| `POST` | `/api/dispatch` | Dispatch a text task `{task, backend?}` → `{task_id}` |
| `GET` | `/api/board` | Kanban JSON: `{pending, in_progress, done}` |
| `GET` | `/api/task/:id` | Status, event log, result |
| `POST` | `/api/task/:id/cancel` | Cancel a task owned by this Web process |
| `POST` | `/api/voice` | Upload audio → StepFun ASR → dispatch |
| `GET` | `/api/tts?text=…` | StepFun TTS → `audio/mpeg` |

The server keeps one process-wide `Receptionist`; final task state and results
are durable in `CODING_VIBE_STATE_DIR/session.json`.

## Environment

`STEPFUN_API_KEY` and `STEPFUN_BASE_URL` are read from `web/.env` (or the
parent `.env`).  Voice features degrade gracefully when the key is missing —
the text input always works.

## Dependencies

`uv add -r web/requirements.txt` or `pip install -r web/requirements.txt`.
