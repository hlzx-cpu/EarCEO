"""Backend-agnostic task dispatch with a durable lifecycle."""

from __future__ import annotations

import asyncio
import inspect
import logging
import time
import traceback
import uuid
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any

from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult
from receptionist.registry import get_adapter
from receptionist.state import (
    append_checkpoint,
    create_task,
    get_task,
    update_state,
    update_task,
)

logger = logging.getLogger(__name__)


class TaskStatus(str, Enum):
    """Persisted states for every dispatched task."""

    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


TERMINAL_STATUSES = {
    TaskStatus.COMPLETED.value,
    TaskStatus.FAILED.value,
    TaskStatus.CANCELLED.value,
}


class TaskExecutionError(RuntimeError):
    """Raised by ``result`` when a background task crashed unexpectedly."""

    def __init__(self, task_id: str, error: dict[str, Any] | None = None) -> None:
        self.task_id = task_id
        self.error = error or {}
        message = self.error.get("message") or "unknown execution error"
        super().__init__(f"Task {task_id!r} failed: {message}")


class TaskCancelledError(RuntimeError):
    """Raised by ``result`` when the requested task was cancelled."""


@dataclass
class _Runtime:
    adapter: HarnessAdapter | None = None
    handle: str | None = None
    background: asyncio.Task[TaskResult] | None = None
    cancel_requested: bool = False


def _progress_from_kind(kind: str) -> int:
    return {
        "progress": 25,
        "tool": 50,
        "message": 60,
        "done": 100,
        "error": 0,
    }.get(kind, 50)


def _new_task_id() -> str:
    return str(uuid.uuid4())[:8]


def _result_from_record(record: dict[str, Any]) -> TaskResult | None:
    result = record.get("result")
    if not isinstance(result, dict):
        return None
    return TaskResult(
        ok=bool(result.get("ok")),
        summary=str(result.get("summary", "")),
        files_changed=list(result.get("files_changed") or []),
        raw=str(result.get("raw", "")),
    )


class Receptionist:
    """Dispatch tasks through registered adapters and persist their lifecycle.

    A receptionist owns only live asyncio objects. Task metadata, checkpoints,
    errors, and final results live in the shared :class:`JsonStateStore`, so a
    later receptionist instance can still inspect completed work.
    """

    def __init__(self) -> None:
        self._active: dict[str, _Runtime] = {}
        self._background_tasks: dict[str, asyncio.Task[TaskResult]] = {}

    @staticmethod
    def generate_task_id() -> str:
        """Return an ID suitable for passing back into ``dispatch*``."""
        return _new_task_id()

    async def dispatch(
        self,
        task: str,
        *,
        backend: str = "mock",
        repo_path: str,
        context: dict[str, Any] | None = None,
        task_id: str | None = None,
    ) -> TaskResult:
        """Run a task synchronously through the same core as async dispatch."""
        task_id, full_context = self._prepare_task(
            task, backend, repo_path, context, task_id
        )
        runtime = _Runtime(background=asyncio.current_task())
        self._active[task_id] = runtime
        try:
            return await self._execute(
                task_id,
                task,
                backend=backend,
                repo_path=repo_path,
                context=full_context,
                runtime=runtime,
            )
        finally:
            self._active.pop(task_id, None)

    async def dispatch_async(
        self,
        task: str,
        *,
        backend: str = "mock",
        repo_path: str,
        context: dict[str, Any] | None = None,
        task_id: str | None = None,
        on_status: Any = None,
        on_complete: Any = None,
    ) -> str:
        """Schedule a task and return its single canonical ID immediately."""
        task_id, full_context = self._prepare_task(
            task, backend, repo_path, context, task_id
        )
        runtime = _Runtime()
        self._active[task_id] = runtime
        background = asyncio.create_task(
            self._execute(
                task_id,
                task,
                backend=backend,
                repo_path=repo_path,
                context=full_context,
                runtime=runtime,
                on_status=on_status,
                on_complete=on_complete,
            ),
            name=f"receptionist:{task_id}",
        )
        runtime.background = background
        self._background_tasks[task_id] = background
        background.add_done_callback(
            lambda finished, tid=task_id: self._task_finished(tid, finished)
        )
        return task_id

    async def result(self, task_id: str) -> TaskResult | None:
        """Wait for a local task, or restore its terminal result from state.

        Unknown and externally-running tasks return ``None``. Cancelled tasks
        raise :class:`TaskCancelledError`; unexpected adapter crashes raise
        :class:`TaskExecutionError`. A normal adapter failure is returned as a
        ``TaskResult`` with ``ok=False``.
        """
        background = self._background_tasks.get(task_id)
        if background is not None:
            try:
                await asyncio.shield(background)
            except asyncio.CancelledError:
                if not background.cancelled():
                    raise
            except Exception:
                # The durable error below is the stable public representation.
                pass

        record = get_task(task_id)
        if record is None:
            return None
        status = record.get("status")
        if status == TaskStatus.CANCELLED.value:
            raise TaskCancelledError(f"Task {task_id!r} was cancelled")
        result = _result_from_record(record)
        if result is not None:
            return result
        if status == TaskStatus.FAILED.value:
            raise TaskExecutionError(task_id, record.get("error"))
        return None

    async def cancel(self, task_id: str) -> bool:
        """Best-effort cancellation for a task running in this process."""
        record = get_task(task_id)
        if record is None or record.get("status") in TERMINAL_STATUSES:
            return False

        runtime = self._active.get(task_id)
        if runtime is None:
            # We cannot safely claim an external process was stopped.
            return False

        runtime.cancel_requested = True
        if runtime.adapter is not None and runtime.handle is not None:
            try:
                await runtime.adapter.cancel(runtime.handle)
            except Exception:
                logger.exception("Adapter cancellation failed for task %s", task_id)

        background = runtime.background
        if background is not None and background is not asyncio.current_task():
            background.cancel()
            try:
                await background
            except (asyncio.CancelledError, Exception):
                pass

        self._mark_cancelled(task_id)
        final_record = get_task(task_id)
        return bool(
            final_record
            and final_record.get("status") == TaskStatus.CANCELLED.value
        )

    def task(self, task_id: str) -> dict[str, Any] | None:
        """Return the current durable task record."""
        return get_task(task_id)

    def _prepare_task(
        self,
        task: str,
        backend: str,
        repo_path: str,
        context: dict[str, Any] | None,
        task_id: str | None,
    ) -> tuple[str, dict[str, Any]]:
        full_context = dict(context or {})
        contextual_id = full_context.get("task_id")
        if task_id is not None and contextual_id not in (None, task_id):
            raise ValueError("task_id argument conflicts with context['task_id']")
        canonical_id = str(task_id or contextual_id or self.generate_task_id())
        full_context["task_id"] = canonical_id
        now = time.time()
        create_task(
            {
                "task_id": canonical_id,
                "task": task,
                "backend": backend,
                "repo_path": repo_path,
                "context": full_context,
                "status": TaskStatus.PENDING.value,
                "created_at": now,
                "updated_at": now,
            }
        )
        append_checkpoint(
            "task-pending",
            "Task accepted and waiting to start",
            progress_pct=0,
            task_id=canonical_id,
        )
        return canonical_id, full_context

    async def _execute(
        self,
        task_id: str,
        task: str,
        *,
        backend: str,
        repo_path: str,
        context: dict[str, Any],
        runtime: _Runtime,
        on_status: Any = None,
        on_complete: Any = None,
    ) -> TaskResult:
        try:
            if runtime.cancel_requested:
                raise asyncio.CancelledError

            update_task(
                task_id,
                status=TaskStatus.RUNNING.value,
                started_at=time.time(),
            )
            append_checkpoint(
                "task-running",
                f"Task started with backend {backend}",
                progress_pct=0,
                task_id=task_id,
            )

            adapter: HarnessAdapter = get_adapter(backend)()
            runtime.adapter = adapter
            handle = await adapter.spawn(
                task, repo_path=repo_path, context=context
            )
            runtime.handle = handle

            if runtime.cancel_requested:
                raise asyncio.CancelledError

            async for event in adapter.stream_status(handle):
                append_checkpoint(
                    milestone=event.kind,
                    message=event.text,
                    progress_pct=_progress_from_kind(event.kind),
                    task_id=task_id,
                )
                if on_status is not None:
                    await _call_safe(on_status, event)

            result = await adapter.result(handle)
            status = (
                TaskStatus.COMPLETED.value
                if result.ok
                else TaskStatus.FAILED.value
            )
            finished_at = time.time()
            update_task(
                task_id,
                status=status,
                result=asdict(result),
                completed_at=finished_at if result.ok else None,
                failed_at=finished_at if not result.ok else None,
            )
            append_checkpoint(
                milestone="task-complete" if result.ok else "task-failed",
                message=result.summary,
                progress_pct=100 if result.ok else 0,
                task_id=task_id,
                files_changed=result.files_changed,
            )
            if on_complete is not None:
                await _call_safe(on_complete, result)
            return result
        except asyncio.CancelledError:
            self._mark_cancelled(task_id)
            raise
        except Exception as exc:
            error = {
                "type": type(exc).__name__,
                "message": str(exc),
                "traceback": traceback.format_exc(),
            }
            update_task(
                task_id,
                status=TaskStatus.FAILED.value,
                error=error,
                failed_at=time.time(),
            )
            append_checkpoint(
                "task-failed",
                f"{error['type']}: {error['message']}",
                progress_pct=0,
                task_id=task_id,
            )
            raise

    def _mark_cancelled(self, task_id: str) -> None:
        """Persist cancellation exactly once, even across cancellation races."""
        cancelled_at = time.time()

        def mark(state: dict[str, Any]) -> bool:
            record = state["tasks"].get(task_id)
            if record is None or record.get("status") in TERMINAL_STATUSES:
                return False
            record.update(
                {
                    "status": TaskStatus.CANCELLED.value,
                    "cancelled_at": cancelled_at,
                    "updated_at": cancelled_at,
                }
            )
            return True

        if update_state(mark):
            append_checkpoint(
                "task-cancelled",
                "Task cancelled",
                progress_pct=0,
                task_id=task_id,
            )

    def _task_finished(
        self, task_id: str, finished: asyncio.Task[TaskResult]
    ) -> None:
        self._active.pop(task_id, None)
        self._background_tasks.pop(task_id, None)
        try:
            finished.exception()
        except asyncio.CancelledError:
            pass


async def _call_safe(callback: Any, *args: Any) -> None:
    """Run a sync or async callback without letting it break execution."""
    try:
        value = callback(*args) if callable(callback) else None
        if inspect.isawaitable(value):
            await value
    except Exception:
        logger.exception("Task callback raised an exception")
