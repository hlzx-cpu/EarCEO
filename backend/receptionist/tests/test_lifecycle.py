"""Task lifecycle, result, failure, and cancellation tests."""

from __future__ import annotations

import asyncio
from typing import AsyncIterator

import pytest

from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult
from receptionist.core import (
    Receptionist,
    TaskCancelledError,
    TaskExecutionError,
)
from receptionist.registry import register_adapter
from receptionist.state import get_task, load_state


@pytest.fixture(autouse=True)
def isolated_state_dir(tmp_path, monkeypatch):
    state_dir = tmp_path / "state"
    monkeypatch.setenv("CODING_VIBE_STATE_DIR", str(state_dir))
    # Registry discovery tests reset global registry state, so re-register
    # these test-only adapters for every lifecycle test.
    register_adapter(ExplodingAdapter)
    register_adapter(FailedResultAdapter)
    register_adapter(BlockingAdapter)
    return state_dir


class ExplodingAdapter(HarnessAdapter):
    name = "test-exploding"

    async def spawn(self, task, *, repo_path, context=None):
        return "exploding-handle"

    async def stream_status(self, handle) -> AsyncIterator[StatusEvent]:
        yield StatusEvent(kind="progress", text="started")
        raise RuntimeError("engine exploded")

    async def result(self, handle):
        raise AssertionError("result must not be reached")


class FailedResultAdapter(HarnessAdapter):
    name = "test-failed-result"

    async def spawn(self, task, *, repo_path, context=None):
        return "failed-handle"

    async def stream_status(self, handle) -> AsyncIterator[StatusEvent]:
        yield StatusEvent(kind="error", text="build failed")

    async def result(self, handle):
        return TaskResult(
            ok=False,
            summary="build failed",
            files_changed=[],
            raw="compiler output",
        )


class BlockingAdapter(HarnessAdapter):
    name = "test-blocking"
    started: asyncio.Event | None = None
    cancel_called = False

    async def spawn(self, task, *, repo_path, context=None):
        return "blocking-handle"

    async def stream_status(self, handle) -> AsyncIterator[StatusEvent]:
        assert self.started is not None
        self.started.set()
        yield StatusEvent(kind="progress", text="waiting")
        await asyncio.Event().wait()

    async def result(self, handle):
        return TaskResult(True, "unexpected completion", [], "")

    async def cancel(self, handle):
        type(self).cancel_called = True


async def test_async_result_returns_real_task_result():
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "work", backend="mock", repo_path="/tmp/repo"
    )

    result = await receptionist.result(task_id)

    assert isinstance(result, TaskResult)
    assert result.ok is True
    assert result.summary == "Mock task completed successfully"


async def test_completed_result_survives_receptionist_instance():
    first = Receptionist()
    task_id = await first.dispatch_async(
        "work", backend="mock", repo_path="/tmp/repo"
    )
    await first.result(task_id)

    restored = await Receptionist().result(task_id)

    assert restored is not None
    assert restored.summary == "Mock task completed successfully"


async def test_context_task_id_is_the_canonical_id_for_every_checkpoint():
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "work",
        backend="mock",
        repo_path="/tmp/repo",
        context={"task_id": "canonical-task"},
    )
    await receptionist.result(task_id)

    checkpoints = load_state()["checkpoints"]
    assert task_id == "canonical-task"
    assert checkpoints
    assert {item.get("task_id") for item in checkpoints} == {"canonical-task"}


async def test_explicit_task_id_conflict_is_rejected():
    with pytest.raises(ValueError, match="conflicts"):
        await Receptionist().dispatch_async(
            "work",
            backend="mock",
            repo_path="/tmp/repo",
            context={"task_id": "one"},
            task_id="two",
        )


async def test_duplicate_task_id_is_rejected():
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "first", backend="mock", repo_path="/tmp/repo", task_id="duplicate"
    )
    await receptionist.result(task_id)

    with pytest.raises(ValueError, match="already exists"):
        await receptionist.dispatch_async(
            "second", backend="mock", repo_path="/tmp/repo", task_id="duplicate"
        )


async def test_unexpected_failure_is_persisted_and_result_raises():
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "explode", backend=ExplodingAdapter.name, repo_path="/tmp/repo"
    )

    with pytest.raises(TaskExecutionError, match="engine exploded"):
        await receptionist.result(task_id)

    record = get_task(task_id)
    assert record is not None
    assert record["status"] == "failed"
    assert record["error"]["type"] == "RuntimeError"
    assert "engine exploded" in record["error"]["traceback"]
    assert load_state()["checkpoints"][-1]["milestone"] == "task-failed"


async def test_sync_failure_uses_same_lifecycle_but_preserves_exception():
    receptionist = Receptionist()

    with pytest.raises(RuntimeError, match="engine exploded"):
        await receptionist.dispatch(
            "explode",
            backend=ExplodingAdapter.name,
            repo_path="/tmp/repo",
            task_id="sync-failure",
        )

    assert get_task("sync-failure")["status"] == "failed"


async def test_normal_failed_result_is_returned_and_marked_failed():
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "fail normally",
        backend=FailedResultAdapter.name,
        repo_path="/tmp/repo",
    )

    result = await receptionist.result(task_id)

    assert result is not None
    assert result.ok is False
    assert result.raw == "compiler output"
    assert get_task(task_id)["status"] == "failed"


async def test_running_task_can_be_cancelled():
    BlockingAdapter.started = asyncio.Event()
    BlockingAdapter.cancel_called = False
    receptionist = Receptionist()
    task_id = await receptionist.dispatch_async(
        "wait", backend=BlockingAdapter.name, repo_path="/tmp/repo"
    )
    await asyncio.wait_for(BlockingAdapter.started.wait(), timeout=1)

    assert await receptionist.cancel(task_id) is True

    with pytest.raises(TaskCancelledError):
        await receptionist.result(task_id)
    assert BlockingAdapter.cancel_called is True
    assert get_task(task_id)["status"] == "cancelled"
    assert load_state()["checkpoints"][-1]["milestone"] == "task-cancelled"


async def test_terminal_and_unknown_tasks_cannot_be_cancelled():
    receptionist = Receptionist()
    assert await receptionist.cancel("missing") is False

    task_id = await receptionist.dispatch_async(
        "work", backend="mock", repo_path="/tmp/repo"
    )
    await receptionist.result(task_id)
    assert await receptionist.cancel(task_id) is False


async def test_concurrent_tasks_keep_results_and_ids_separate():
    receptionist = Receptionist()
    task_ids = await asyncio.gather(
        receptionist.dispatch_async(
            "first", backend="mock", repo_path="/tmp/one", task_id="task-one"
        ),
        receptionist.dispatch_async(
            "second", backend="mock", repo_path="/tmp/two", task_id="task-two"
        ),
    )
    results = await asyncio.gather(
        *(receptionist.result(task_id) for task_id in task_ids)
    )

    assert all(result is not None and result.ok for result in results)
    state = load_state()
    assert set(state["tasks"]) == {"task-one", "task-two"}
    assert {
        checkpoint["task_id"] for checkpoint in state["checkpoints"]
    } == {"task-one", "task-two"}
