"""web/server.py — FastAPI server for coding-vibe Web UI.

Endpoints:
  GET  /              → index.html
  GET  /call          → call.html
  POST /api/dispatch  → {task_id}
  GET  /api/board     → kanban JSON
  GET  /api/task/{id} → status + events + result
  POST /api/voice     → transcribe audio blob → dispatch → {transcript, task_id}
  GET  /api/tts       → synthesized audio/mpeg
  WS   /api/call      → realtime voice bridge (call_bridge router)
  WS   /api/events    → realtime signaling (signaling router)
  POST /api/call/ring → trigger incoming-call event (signaling router)
  POST /api/devices/register  → register push device (push_server router)
  GET  /api/devices           → list registered devices
  POST /api/devices/ring      → native push ring
"""

from __future__ import annotations

import asyncio
import hmac
import os
import time
from collections import defaultdict
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles

# ── Project root (parent of web/) ──────────────────────────────────────────────
_THIS_DIR = Path(__file__).parent
_PROJECT_ROOT = _THIS_DIR.parent

# ── Load .env: try worktree root first, then parent (original repo) ─────────────
_env_candidates = [
    _PROJECT_ROOT / ".env",          # worktree root  (/coding-vibe-webui/.env)
    _PROJECT_ROOT.parent / ".env",   # parent repo    (/coding-vibe/.env)
]
for _ef in _env_candidates:
    if _ef.exists():
        load_dotenv(_ef, override=False)
        print(f"[config] Loaded .env from {_ef}")
        break

# ── Import receptionist (project root must be on sys.path) ─────────────────────
import sys

sys.path.insert(0, str(_PROJECT_ROOT))
from receptionist.state import get_task, load_state  # noqa: E402
from web.runtime import receptionist as _receptionist  # noqa: E402

# ── Import routers (mounted below) ─────────────────────────────────────────────
from web.call_bridge import router as call_bridge_router  # noqa: E402
from web.signaling   import router as signaling_router    # noqa: E402
from web.push_server import router as push_server_router  # noqa: E402
from web.mobile_api import install_mobile_api  # noqa: E402

# ── Config ─────────────────────────────────────────────────────────────────────
STATIC_DIR = _THIS_DIR / "static"

# ── Security: network-facing dispatch controls ──────────────────────────────────
# This server is reachable over the network (LAN / tunnel). Real backends such as
# `claude-code` and `openopc` spawn subprocesses (e.g. `claude
# --dangerously-skip-permissions`), so an unauthenticated caller choosing them
# would be remote code execution. Three layers guard the dispatch endpoints:
#   1. backend allowlist (default mock-only)
#   2. bearer token (constant-time) required on state-changing endpoints
#   3. repo_path constrained to resolved, allowlisted roots
_ALLOWED_BACKENDS = {
    b.strip()
    for b in os.environ.get("CV_ALLOWED_BACKENDS", "mock").split(",")
    if b.strip()
}

# Backends that spawn real subprocesses — dispatching these unauthenticated is RCE.
_DANGEROUS_BACKENDS = {"claude-code", "openopc"}

# Shared secret for /api/dispatch and /api/voice. Empty = no auth (safe only while
# the allowlist is mock-only).
CV_API_TOKEN = os.environ.get("CV_API_TOKEN", "")

# repo_path (the subprocess cwd) must resolve to / under one of these roots.
_ALLOWED_REPO_ROOTS = [
    Path(p).expanduser().resolve()
    for p in os.environ.get(
        "CV_ALLOWED_REPO_ROOTS", "/tmp/cv-demo:/tmp/cv-e2e:/tmp/cv-ooc"
    ).split(":")
    if p.strip()
]

# Fail closed: never expose an unauthenticated subprocess backend. If a dangerous
# backend is allowlisted but no token is configured, refuse to start.
if (_ALLOWED_BACKENDS & _DANGEROUS_BACKENDS) and not CV_API_TOKEN:
    raise RuntimeError(
        "Refusing to start: CV_ALLOWED_BACKENDS includes a subprocess-spawning "
        f"backend {sorted(_ALLOWED_BACKENDS & _DANGEROUS_BACKENDS)} but CV_API_TOKEN "
        "is unset — that would be an unauthenticated RCE surface. Set CV_API_TOKEN."
    )


def _check_auth(token: str | None) -> None:
    """Enforce the bearer token on state-changing endpoints, if one is configured."""
    if CV_API_TOKEN:
        if not token or not hmac.compare_digest(token, CV_API_TOKEN):
            raise HTTPException(401, "Missing or invalid API token")


def _guard_dispatch(task: str, backend: str, repo_path: str) -> None:
    """Reject network-supplied dispatch params that are unsafe.

    - backend must be on the allowlist (blocks RCE via dangerous adapters)
    - task must not start with '-' (defense-in-depth vs argv flag smuggling;
      adapters also terminate flags with a `--` sentinel)
    - repo_path (the subprocess cwd) must resolve under an allowlisted root
    """
    if backend not in _ALLOWED_BACKENDS:
        raise HTTPException(
            403,
            f"Backend {backend!r} is not permitted over the API. "
            f"Allowed: {sorted(_ALLOWED_BACKENDS)}",
        )
    if task.startswith("-"):
        raise HTTPException(400, "Invalid 'task': must not start with '-'")
    root = Path(repo_path).expanduser().resolve()
    if not any(root == a or a in root.parents for a in _ALLOWED_REPO_ROOTS):
        raise HTTPException(
            400,
            f"repo_path {str(root)!r} is outside the allowed roots "
            f"{[str(r) for r in _ALLOWED_REPO_ROOTS]}",
        )

STEPFUN_API_KEY = os.environ.get("STEPFUN_API_KEY", "")
STEPFUN_BASE_URL = os.environ.get("STEPFUN_BASE_URL", "https://api.stepfun.com/v1")
STEPFUN_ASR_MODEL = "step-asr"
STEPFUN_TTS_MODEL = "step-tts-2"

# ── In-memory event log per task_id ───────────────────────────────────────────
_task_events: dict[str, list[dict[str, Any]]] = defaultdict(list)

# ── Lifespan: warm Receptionist ────────────────────────────────────────────────


@asynccontextmanager
async def lifespan(app: FastAPI):
    # warm registry (no-op if already discovered)
    try:
        from receptionist.registry import _ensure_discovered  # noqa: WPS433
        _ensure_discovered()
    except Exception:
        pass
    yield


app = FastAPI(title="Coding Vibe Web UI", lifespan=lifespan)
install_mobile_api(app)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    # Auth is a bearer token in a header/query param, NOT a cookie — so we do not
    # need (and must not enable) credentialed CORS. Wildcard origin + credentials
    # is a spec violation and a token-leak amplifier; keep credentials off.
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

# ── Mount sub-routers ────────────────────────────────────────────────────────────
# call_bridge  → WS  /api/call           (realtime voice bridge to StepFun)
# signaling    → WS  /api/events         (live event push)
#               → POST /api/call/ring     (trigger incoming-call event)
# push_server  → POST /api/devices/register  (register push device)
#               → GET  /api/devices          (list registered devices)
#               → POST /api/devices/ring     (native push ring)
app.include_router(call_bridge_router)
app.include_router(signaling_router)
app.include_router(push_server_router)


# ── Helpers ───────────────────────────────────────────────────────────────────


def _load_session() -> dict[str, Any]:
    return load_state()


async def _log_event(task_id: str, kind: str, text: str) -> None:
    entry = {"ts": time.time(), "kind": kind, "text": text}
    _task_events[task_id].append(entry)


def _callbacks(task_id: str) -> tuple[Any, Any]:
    """Build callbacks bound to one canonical task ID."""

    def on_status(event: Any) -> None:
        asyncio.create_task(
            _log_event(task_id, event.kind, event.text)
        )

    def on_complete(result: Any) -> None:
        asyncio.create_task(
            _log_event(
                task_id,
                "result",
                result.summary,
            )
        )

    return on_status, on_complete


# ── Routes ────────────────────────────────────────────────────────────────────


@app.get("/")
async def root():
    """Serve the mobile UI."""
    index = STATIC_DIR / "index.html"
    if index.exists():
        return FileResponse(str(index))
    raise HTTPException(500, "index.html not found")


@app.get("/call")
async def call_page():
    """Serve the call UI."""
    call_html = STATIC_DIR / "call.html"
    if call_html.exists():
        return FileResponse(str(call_html))
    raise HTTPException(500, "call.html not found")


@app.post("/api/dispatch")
async def dispatch(body: dict[str, Any], request: Request):
    """Dispatch a text task. Returns {task_id}."""
    task: str = body.get("task", "").strip()
    if not task:
        raise HTTPException(400, "Missing 'task' field")

    backend: str = body.get("backend", "mock")
    repo_path: str = body.get("repo_path") or os.environ.get(
        "CV_DEMO_REPO", "/tmp/cv-demo"
    )

    # Security gate: auth token (if configured), backend allowlist, repo_path root.
    _check_auth(request.headers.get("x-cv-token") or request.query_params.get("token"))
    _guard_dispatch(task, backend, repo_path)

    task_id = _receptionist.generate_task_id()
    on_status, on_complete = _callbacks(task_id)
    await _receptionist.dispatch_async(
        task,
        backend=backend,
        repo_path=repo_path,
        task_id=task_id,
        on_status=on_status,
        on_complete=on_complete,
    )
    await _log_event(task_id, "info", f"Task dispatched: {task[:120]}")
    return {"task_id": task_id}


@app.get("/api/board")
async def board(request: Request):
    """Return kanban-shaped JSON derived from session.json."""
    _check_auth(request.headers.get("x-cv-token") or request.query_params.get("token"))
    session = _load_session()
    tasks = session.get("tasks", {})
    delegations = session.get("delegations", [])
    checkpoints = session.get("checkpoints", [])

    # Build per-task checkpoint index (latest checkpoint per task_id)
    latest_by_task: dict[str, dict[str, Any]] = {}
    for cp in checkpoints:
        tid = cp.get("task_id")
        if tid:
            prev = latest_by_task.get(tid, {})
            if (cp.get("timestamp", 0) or 0) >= (prev.get("timestamp", 0) or 0):
                latest_by_task[tid] = cp

    pending: list[dict[str, Any]] = []
    in_progress: list[dict[str, Any]] = []
    done: list[dict[str, Any]] = []

    represented_ids: set[str] = set()

    for task_id, task in tasks.items():
        represented_ids.add(task_id)
        latest = latest_by_task.get(task_id, {})
        status = task.get("status", "pending")
        result = task.get("result") or {}
        card = {
            "task_id": task_id,
            "description": task.get("task", ""),
            "progress_pct": latest.get(
                "progress_pct", 100 if status == "completed" else 0
            ),
            "latest_message": latest.get(
                "message", result.get("summary", task.get("task", ""))
            ),
            "milestone": latest.get("milestone", ""),
            "created_at": task.get("created_at", ""),
            "status": status,
            "files_changed": result.get("files_changed", []),
            "error": task.get("error"),
        }
        if status == "pending":
            pending.append(card)
        elif status == "running":
            in_progress.append(card)
        else:
            done.append(card)

    # Keep legacy MCP-only delegations visible during schema migration.
    for d in delegations:
        tid = d.get("task_id", "")
        if tid in represented_ids:
            continue
        represented_ids.add(tid)
        latest = latest_by_task.get(tid, {})
        milestone = latest.get("milestone", "")
        progress = latest.get("progress_pct", 0)
        message = latest.get("message", d.get("description", ""))

        card: dict[str, Any] = {
            "task_id": tid,
            "description": d.get("description", ""),
            "progress_pct": progress,
            "latest_message": message,
            "milestone": milestone,
            "created_at": d.get("created_at", ""),
            "status": d.get("status", "pending"),
            "files_changed": latest.get("files_changed", d.get("files_changed", [])),
        }

        if milestone == "task-complete" or d.get("status") == "completed":
            done.append(card)
        elif milestone in ("progress", "tool", "message") or d.get("status") in (
            "running",
            "delegated",
        ):
            in_progress.append(card)
        else:
            pending.append(card)

    # Tasks that are running but have no delegation entry yet (checkpoint-only)
    for tid, cp in latest_by_task.items():
        if tid in represented_ids:
            continue
        milestone = cp.get("milestone", "")
        if milestone == "task-complete":
            done.append(
                {
                    "task_id": tid,
                    "description": cp.get("message", ""),
                    "progress_pct": cp.get("progress_pct", 100),
                    "latest_message": cp.get("message", ""),
                    "milestone": milestone,
                    "created_at": cp.get("timestamp", ""),
                    "status": "completed",
                    "files_changed": cp.get("files_changed", []),
                }
            )
        elif milestone in ("progress", "tool", "message"):
            in_progress.append(
                {
                    "task_id": tid,
                    "description": cp.get("message", ""),
                    "progress_pct": cp.get("progress_pct", 0),
                    "latest_message": cp.get("message", ""),
                    "milestone": milestone,
                    "created_at": cp.get("timestamp", ""),
                    "status": "running",
                    "files_changed": cp.get("files_changed", []),
                }
            )

    return {
        "pending": pending,
        "in_progress": in_progress,
        "done": done,
        "updated_at": time.time(),
    }


@app.get("/api/task/{task_id}")
async def task_detail(task_id: str, request: Request):
    """Get status, event log, and final result for a task."""
    _check_auth(request.headers.get("x-cv-token") or request.query_params.get("token"))
    events = _task_events.get(task_id, [])
    record = get_task(task_id)
    session = _load_session()
    cp_entries = [
        cp
        for cp in session.get("checkpoints", [])
        if cp.get("task_id") == task_id
    ]
    all_events = events + [
        {
            "ts": c.get("timestamp", 0),
            "kind": c.get("milestone", ""),
            "text": c.get("message", ""),
            "progress_pct": c.get("progress_pct"),
        }
        for c in cp_entries
    ]
    all_events.sort(key=lambda e: e.get("ts", 0))

    if record is None and not cp_entries and not events:
        raise HTTPException(404, f"Unknown task {task_id!r}")

    latest_cp = cp_entries[-1] if cp_entries else None
    status = (record or {}).get("status", "running")

    return {
        "task_id": task_id,
        "status": status,
        "progress_pct": (latest_cp or {}).get("progress_pct", 0),
        "latest_message": (latest_cp or {}).get("message", ""),
        "events": all_events,
        "result": (record or {}).get("result"),
        "error": (record or {}).get("error"),
    }


@app.post("/api/task/{task_id}/cancel")
async def cancel_task(task_id: str, request: Request):
    """Cancel a task owned by this server process."""
    _check_auth(request.headers.get("x-cv-token") or request.query_params.get("token"))
    if get_task(task_id) is None:
        raise HTTPException(404, f"Unknown task {task_id!r}")
    if not await _receptionist.cancel(task_id):
        raise HTTPException(409, "Task is already terminal or is not owned by this process")
    return {"task_id": task_id, "status": "cancelled"}


# ── Voice: ASR ────────────────────────────────────────────────────────────────


@app.post("/api/voice")
async def voice(request: Request, audio: bytes = None, file: Any = None):
    """Transcribe an audio blob via StepFun ASR, then dispatch the transcript.

    Accepts either raw bytes in the request body OR a multipart 'file' field.
    Returns {transcript, task_id}.
    """
    _check_auth(request.headers.get("x-cv-token") or request.query_params.get("token"))
    # Accept both raw body and multipart
    raw_audio: bytes | None = None
    if file is not None:
        raw_audio = await file.read()
    elif audio is not None:
        raw_audio = audio
    else:
        raise HTTPException(400, "No audio provided. Send raw bytes or multipart 'file'.")

    if not STEPFUN_API_KEY:
        raise HTTPException(
            500,
            "STEPFUN_API_KEY not configured on server. "
            "Add it to web/.env or the environment.",
        )

    if len(raw_audio) < 100:
        raise HTTPException(400, "Audio blob is too short (< 100 bytes).")

    transcript = await _stepfun_asr(raw_audio)
    if transcript is None:
        raise HTTPException(
            502,
            "StepFun ASR failed. Use the text input fallback in the browser.",
        )

    task_text = transcript.strip()
    if not task_text:
        raise HTTPException(400, "Empty transcript — speak again.")

    # Dispatch as a mock task
    repo_path = os.environ.get("CV_DEMO_REPO", "/tmp/cv-demo")
    task_id = _receptionist.generate_task_id()
    on_status, on_complete = _callbacks(task_id)
    await _receptionist.dispatch_async(
        task_text,
        backend="mock",
        repo_path=repo_path,
        task_id=task_id,
        on_status=on_status,
        on_complete=on_complete,
    )
    await _log_event(task_id, "info", f"Voice task: {task_text[:120]}")
    return {"transcript": task_text, "task_id": task_id}


async def _stepfun_asr(audio_bytes: bytes, content_type: str = "audio/webm") -> str | None:
    """Call StepFun /v1/audio/transcriptions. Returns transcript or None on error."""
    url = f"{STEPFUN_BASE_URL}/audio/transcriptions"
    headers = {"Authorization": f"Bearer {STEPFUN_API_KEY}"}
    files = {
        "file": ("audio.webm", audio_bytes, content_type),
        "model": (None, STEPFUN_ASR_MODEL),
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(url, headers=headers, files=files)
            if resp.status_code != 200:
                print(f"[ASR] StepFun returned {resp.status_code}: {resp.text[:200]}")
                return None
            data = resp.json()
            return data.get("text", "") or data.get("transcript", "")
    except Exception as exc:
        print(f"[ASR] Error: {exc}")
        return None


# ── Voice: TTS ────────────────────────────────────────────────────────────────


@app.get("/api/tts")
async def tts(text: str = ""):
    """Synthesize *text* via StepFun TTS. Returns audio/mpeg."""
    if not text.strip():
        raise HTTPException(400, "Missing 'text' query parameter.")

    if not STEPFUN_API_KEY:
        raise HTTPException(
            500,
            "STEPFUN_API_KEY not configured on server.",
        )

    audio_bytes = await _stepfun_tts(text)
    if audio_bytes is None:
        raise HTTPException(502, "StepFun TTS failed.")

    return Response(content=audio_bytes, media_type="audio/mpeg")


async def _stepfun_tts(text: str) -> bytes | None:
    """Call StepFun /v1/audio/speech. Returns raw audio bytes or None."""
    url = f"{STEPFUN_BASE_URL}/audio/speech"
    headers = {"Authorization": f"Bearer {STEPFUN_API_KEY}"}
    payload = {
        "model": STEPFUN_TTS_MODEL,
        "input": text,
        "voice": "cixingnansheng",  # male voice; swap for female
        "response_format": "mp3",
    }
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(url, headers=headers, json=payload)
            if resp.status_code != 200:
                print(f"[TTS] StepFun returned {resp.status_code}: {resp.text[:200]}")
                return None
            return resp.content
    except Exception as exc:
        print(f"[TTS] Error: {exc}")
        return None
