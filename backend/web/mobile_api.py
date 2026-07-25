"""Versioned Android-facing API for EarCEO sessions, turns, and events."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, AsyncIterator

from fastapi import APIRouter, Depends, FastAPI, Header, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from receptionist.core import TaskCancelledError, TaskExecutionError
from receptionist.state import get_task, load_state, update_state
from web.runtime import receptionist


router = APIRouter(prefix="/v1", tags=["mobile-v1"])
_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
_MAX_COMMAND_LENGTH = 20_000


class MobileApiError(Exception):
    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.retryable = retryable
        self.request_id = f"req_{uuid.uuid4().hex[:16]}"


class ClientInfo(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    version: str = Field(min_length=1, max_length=40)
    locale: str = Field(default="zh-CN", max_length=20)


class CreateSessionRequest(BaseModel):
    client_session_id: str
    client: ClientInfo
    project_id: str


class TurnInput(BaseModel):
    type: str = "final_transcript"
    text: str
    locale: str = Field(default="zh-CN", max_length=20)
    source: str = Field(default="viaim-text-stream", max_length=80)


class SubmitTurnRequest(BaseModel):
    client_turn_id: str
    project_id: str
    input: TurnInput
    client_context: dict[str, Any] = Field(default_factory=dict)


def install_mobile_api(app: FastAPI) -> None:
    """Install routes plus the contract-shaped error handler."""

    @app.exception_handler(MobileApiError)
    async def handle_mobile_error(
        _request: Request, error: MobileApiError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=error.status_code,
            content={
                "error": {
                    "code": error.code,
                    "message": error.message,
                    "retryable": error.retryable,
                    "request_id": error.request_id,
                }
            },
        )

    app.include_router(router)


def _utc_now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def _validate_id(value: str, field_name: str) -> str:
    if not _ID_PATTERN.fullmatch(value):
        raise MobileApiError(
            400,
            "INVALID_COMMAND",
            f"{field_name} must contain only letters, digits, '.', '_', or '-'",
        )
    return value


def _require_auth(authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("EARCEO_API_TOKEN") or os.environ.get("CV_API_TOKEN")
    if not expected:
        raise MobileApiError(
            503,
            "AUTH_NOT_CONFIGURED",
            "The mobile API token is not configured on the backend.",
        )
    supplied = ""
    if authorization and authorization.lower().startswith("bearer "):
        supplied = authorization[7:].strip()
    if not supplied or not hmac.compare_digest(supplied, expected):
        raise MobileApiError(401, "UNAUTHORIZED", "Missing or invalid bearer token.")


def _project_map() -> dict[str, Path]:
    raw = os.environ.get("EARCEO_PROJECTS", "")
    try:
        configured = json.loads(raw) if raw else {}
    except json.JSONDecodeError as exc:
        raise MobileApiError(
            503,
            "PROJECT_CONFIG_INVALID",
            "EARCEO_PROJECTS must be a JSON object.",
        ) from exc
    if not isinstance(configured, dict):
        raise MobileApiError(
            503,
            "PROJECT_CONFIG_INVALID",
            "EARCEO_PROJECTS must be a JSON object.",
        )
    return {
        str(project_id): Path(str(path)).expanduser().resolve()
        for project_id, path in configured.items()
    }


def _resolve_project(project_id: str) -> Path:
    project_path = _project_map().get(project_id)
    if project_path is None or not project_path.is_dir():
        raise MobileApiError(
            403,
            "PROJECT_NOT_ALLOWED",
            "The requested project is not available.",
        )
    return project_path


def _session_or_error(session_id: str) -> dict[str, Any]:
    session = load_state()["mobile_sessions"].get(session_id)
    if session is None:
        raise MobileApiError(404, "SESSION_NOT_FOUND", "Session not found.")
    return session


def _append_event_in_state(
    state: dict[str, Any],
    *,
    session_id: str,
    turn_id: str | None,
    event_type: str,
    data: dict[str, Any],
) -> dict[str, Any]:
    session = state["mobile_sessions"][session_id]
    sequence = int(session.get("next_sequence", 1))
    session["next_sequence"] = sequence + 1
    session["updated_at"] = _utc_now()
    event = {
        "event_id": f"evt_{uuid.uuid4().hex[:20]}",
        "session_id": session_id,
        "turn_id": turn_id,
        "sequence": sequence,
        "timestamp": _utc_now(),
        "type": event_type,
        "data": data,
    }
    state["mobile_events"].setdefault(session_id, []).append(event)
    return event


def _append_event(
    session_id: str,
    turn_id: str | None,
    event_type: str,
    data: dict[str, Any],
) -> dict[str, Any]:
    return update_state(
        lambda state: _append_event_in_state(
            state,
            session_id=session_id,
            turn_id=turn_id,
            event_type=event_type,
            data=data,
        )
    )


def _set_turn_status(turn_id: str, status: str, **fields: Any) -> None:
    def update(state: dict[str, Any]) -> None:
        turn = state["mobile_turns"].get(turn_id)
        if turn is None:
            return
        turn.update(fields)
        turn["status"] = status
        turn["updated_at"] = _utc_now()

    update_state(update)


def _events_after(
    session_id: str, last_event_id: str | None
) -> list[dict[str, Any]]:
    events = load_state()["mobile_events"].get(session_id, [])
    if not last_event_id or last_event_id == "0":
        return events
    for index, event in enumerate(events):
        if event["event_id"] == last_event_id:
            return events[index + 1 :]
    return events


def _speak_text(summary: str) -> str:
    compact = " ".join(summary.split())
    if len(compact) <= 100:
        return compact
    return compact[:97] + "…"


def _public_turn(turn: dict[str, Any]) -> dict[str, Any]:
    """Return task metadata without echoing transcript or client context."""

    public_fields = (
        "turn_id",
        "session_id",
        "project_id",
        "status",
        "submitted_at",
        "updated_at",
        "completed_at",
        "failed_at",
        "cancelled_at",
        "summary",
        "files_changed",
    )
    return {field: turn[field] for field in public_fields if field in turn}


async def _monitor_turn(session_id: str, turn_id: str) -> None:
    try:
        result = await receptionist.result(turn_id)
        if result is None:
            raise TaskExecutionError(
                turn_id,
                {"message": "Task result was unavailable after dispatch."},
            )
        if result.ok:
            _set_turn_status(
                turn_id,
                "completed",
                summary=result.summary,
                files_changed=result.files_changed,
                completed_at=_utc_now(),
            )
            _append_event(
                session_id,
                turn_id,
                "task.completed",
                {
                    "status": "completed",
                    "summary": result.summary,
                    "speak_text": _speak_text(result.summary),
                    "files_changed": result.files_changed,
                },
            )
        else:
            _set_turn_status(
                turn_id,
                "failed",
                summary=result.summary,
                failed_at=_utc_now(),
            )
            _append_event(
                session_id,
                turn_id,
                "task.failed",
                {
                    "status": "failed",
                    "code": "CEO_UNAVAILABLE",
                    "message": result.summary,
                    "retryable": True,
                },
            )
    except TaskCancelledError:
        _set_turn_status(turn_id, "cancelled", cancelled_at=_utc_now())
        _append_event(
            session_id,
            turn_id,
            "task.failed",
            {
                "status": "cancelled",
                "code": "TASK_CANCELLED",
                "message": "The task was cancelled.",
                "retryable": False,
            },
        )
    except TaskExecutionError as exc:
        _set_turn_status(
            turn_id,
            "failed",
            error=exc.error,
            failed_at=_utc_now(),
        )
        _append_event(
            session_id,
            turn_id,
            "task.failed",
            {
                "status": "failed",
                "code": "CEO_UNAVAILABLE",
                "message": "The CEO task failed unexpectedly.",
                "retryable": True,
            },
        )
    except Exception as exc:
        _set_turn_status(
            turn_id,
            "failed",
            error={"type": type(exc).__name__, "message": str(exc)},
            failed_at=_utc_now(),
        )
        _append_event(
            session_id,
            turn_id,
            "task.failed",
            {
                "status": "failed",
                "code": "INTERNAL_ERROR",
                "message": "The backend could not finish the task.",
                "retryable": True,
            },
        )


@router.post("/sessions", dependencies=[Depends(_require_auth)])
async def create_session(payload: CreateSessionRequest) -> dict[str, Any]:
    client_session_id = _validate_id(
        payload.client_session_id, "client_session_id"
    )
    _resolve_project(payload.project_id)
    now = _utc_now()

    def create_or_resume(state: dict[str, Any]) -> dict[str, Any]:
        existing_id = state["mobile_session_ids"].get(client_session_id)
        if existing_id is not None:
            existing = state["mobile_sessions"][existing_id]
            if existing["project_id"] != payload.project_id:
                raise MobileApiError(
                    409,
                    "SESSION_PROJECT_CONFLICT",
                    "The client session is already bound to another project.",
                )
            return dict(existing)

        session_id = f"ses_{uuid.uuid4().hex[:20]}"
        session = {
            "session_id": session_id,
            "client_session_id": client_session_id,
            "client": payload.client.model_dump(),
            "project_id": payload.project_id,
            "status": "ready",
            "next_sequence": 1,
            "created_at": now,
            "updated_at": now,
        }
        state["mobile_sessions"][session_id] = session
        state["mobile_session_ids"][client_session_id] = session_id
        state["mobile_events"].setdefault(session_id, [])
        return dict(session)

    session = update_state(create_or_resume)
    return {
        "session_id": session["session_id"],
        "status": session["status"],
        "event_stream": f"/v1/sessions/{session['session_id']}/events",
        "resume_cursor": "0",
    }


@router.get("/sessions/{session_id}", dependencies=[Depends(_require_auth)])
async def get_session(session_id: str) -> dict[str, Any]:
    session = _session_or_error(session_id)
    turns = [
        _public_turn(turn)
        for turn in load_state()["mobile_turns"].values()
        if turn["session_id"] == session_id
    ]
    return {
        "session_id": session_id,
        "status": session["status"],
        "project_id": session["project_id"],
        "turns": turns,
    }


@router.post(
    "/sessions/{session_id}/turns",
    dependencies=[Depends(_require_auth)],
)
async def submit_turn(
    session_id: str,
    payload: SubmitTurnRequest,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> dict[str, Any]:
    session = _session_or_error(session_id)
    turn_id = _validate_id(payload.client_turn_id, "client_turn_id")
    if idempotency_key != turn_id:
        raise MobileApiError(
            400,
            "INVALID_COMMAND",
            "Idempotency-Key must equal client_turn_id.",
        )
    if payload.project_id != session["project_id"]:
        raise MobileApiError(
            409,
            "SESSION_PROJECT_CONFLICT",
            "The turn project does not match its session.",
        )
    project_path = _resolve_project(payload.project_id)
    command_text = payload.input.text.strip()
    if not command_text or len(command_text) > _MAX_COMMAND_LENGTH:
        raise MobileApiError(
            400,
            "INVALID_COMMAND",
            f"Command text must contain 1-{_MAX_COMMAND_LENGTH} characters.",
        )
    command_hash = hashlib.sha256(command_text.encode("utf-8")).hexdigest()
    accepted_at = _utc_now()

    def reserve(state: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        existing = state["mobile_turns"].get(turn_id)
        if existing is not None:
            if (
                existing["session_id"] != session_id
                or existing["command_hash"] != command_hash
            ):
                raise MobileApiError(
                    409,
                    "DUPLICATE_TURN",
                    "The idempotency key was already used for another command.",
                )
            return dict(existing), False

        turn = {
            "turn_id": turn_id,
            "client_turn_id": turn_id,
            "session_id": session_id,
            "project_id": payload.project_id,
            "input_type": payload.input.type,
            "input_text": command_text,
            "input_locale": payload.input.locale,
            "input_source": payload.input.source,
            "command_hash": command_hash,
            "client_context": payload.client_context,
            "status": "accepted",
            "submitted_at": accepted_at,
            "updated_at": accepted_at,
        }
        state["mobile_turns"][turn_id] = turn
        _append_event_in_state(
            state,
            session_id=session_id,
            turn_id=turn_id,
            event_type="turn.accepted",
            data={"status": "accepted"},
        )
        return dict(turn), True

    turn, created = update_state(reserve)
    if created or get_task(turn_id) is None:
        backend = os.environ.get("EARCEO_BACKEND", "mock")

        def on_status(event: Any) -> None:
            _set_turn_status(turn_id, "working")
            _append_event(
                session_id,
                turn_id,
                "task.progress",
                {
                    "milestone": event.kind,
                    "message": event.text,
                    "progress_pct": {
                        "progress": 25,
                        "tool": 50,
                        "message": 60,
                        "done": 100,
                        "error": 0,
                    }.get(event.kind, 50),
                },
            )

        try:
            await receptionist.dispatch_async(
                turn["input_text"],
                backend=backend,
                repo_path=str(project_path),
                context={
                    "task_id": turn_id,
                    "session_id": session_id,
                    "project_id": payload.project_id,
                    "source": "earceo-android",
                },
                task_id=turn_id,
                on_status=on_status,
            )
        except Exception as exc:
            _set_turn_status(
                turn_id,
                "failed",
                error={"type": type(exc).__name__, "message": str(exc)},
                failed_at=_utc_now(),
            )
            _append_event(
                session_id,
                turn_id,
                "task.failed",
                {
                    "status": "failed",
                    "code": "CEO_UNAVAILABLE",
                    "message": "The CEO task could not be dispatched.",
                    "retryable": True,
                },
            )
            raise MobileApiError(
                503,
                "CEO_UNAVAILABLE",
                "The CEO task could not be dispatched.",
                retryable=True,
            ) from exc
        asyncio.create_task(
            _monitor_turn(session_id, turn_id),
            name=f"mobile-monitor:{turn_id}",
        )

    current = load_state()["mobile_turns"][turn_id]
    return {
        "turn_id": turn_id,
        "status": current["status"],
        "submitted_at": current["submitted_at"],
    }


@router.get(
    "/sessions/{session_id}/events",
    dependencies=[Depends(_require_auth)],
)
async def stream_events(
    session_id: str,
    request: Request,
    cursor: str | None = Query(default=None),
    once: bool = Query(default=False),
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
) -> StreamingResponse:
    _session_or_error(session_id)
    starting_cursor = last_event_id or cursor

    async def generate() -> AsyncIterator[str]:
        cursor_value = starting_cursor
        heartbeat_at = time.monotonic()
        while True:
            events = _events_after(session_id, cursor_value)
            for event in events:
                cursor_value = event["event_id"]
                yield (
                    f"id: {event['event_id']}\n"
                    f"event: {event['type']}\n"
                    f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
                )
            if once:
                return
            if await request.is_disconnected():
                return
            if time.monotonic() - heartbeat_at >= 15:
                heartbeat_at = time.monotonic()
                yield ": keep-alive\n\n"
            await asyncio.sleep(0.25)

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post(
    "/sessions/{session_id}/turns/{turn_id}/cancel",
    dependencies=[Depends(_require_auth)],
)
async def cancel_turn(session_id: str, turn_id: str) -> dict[str, Any]:
    _session_or_error(session_id)
    turn = load_state()["mobile_turns"].get(turn_id)
    if turn is None or turn["session_id"] != session_id:
        raise MobileApiError(404, "TURN_NOT_FOUND", "Turn not found.")
    if turn["status"] in {"completed", "failed", "cancelled"}:
        return {"turn_id": turn_id, "status": turn["status"]}
    if not await receptionist.cancel(turn_id):
        raise MobileApiError(
            409,
            "TASK_NOT_OWNED",
            "The task is not cancellable by this backend process.",
        )
    return {"turn_id": turn_id, "status": "cancelled"}
