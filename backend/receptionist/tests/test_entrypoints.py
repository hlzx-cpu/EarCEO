"""Optional integration tests for the Web and MCP entry points."""

from __future__ import annotations

import json
from typing import AsyncIterator

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("mcp")

from fastapi.testclient import TestClient

from cv_mcp.server import call_tool
from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult
from receptionist.registry import register_adapter
from receptionist.state import load_state
from web import server as web_server


@pytest.fixture(autouse=True)
def isolated_state_dir(tmp_path, monkeypatch):
    state_dir = tmp_path / "state"
    monkeypatch.setenv("CODING_VIBE_STATE_DIR", str(state_dir))
    return state_dir


class WebBlockingAdapter(HarnessAdapter):
    name = "test-web-blocking"

    async def spawn(self, task, *, repo_path, context=None):
        return "web-blocking-handle"

    async def stream_status(self, handle) -> AsyncIterator[StatusEvent]:
        yield StatusEvent(kind="progress", text="waiting")
        import asyncio

        await asyncio.Event().wait()

    async def result(self, handle):
        return TaskResult(True, "unexpected completion", [], "")


def test_web_dispatch_uses_one_id_and_persisted_result():
    with TestClient(web_server.app) as client:
        response = client.post(
            "/api/dispatch",
            json={
                "task": "entrypoint test",
                "backend": "mock",
                "repo_path": "/tmp/cv-demo",
            },
        )
        assert response.status_code == 200
        task_id = response.json()["task_id"]

        detail = client.get(f"/api/task/{task_id}")
        assert detail.status_code == 200
        assert detail.json()["status"] == "completed"
        assert detail.json()["result"]["ok"] is True

    state = load_state()
    assert task_id in state["tasks"]
    assert {
        checkpoint.get("task_id") for checkpoint in state["checkpoints"]
    } == {task_id}


def test_web_cancel_endpoint_updates_lifecycle(monkeypatch):
    register_adapter(WebBlockingAdapter)
    monkeypatch.setattr(
        web_server,
        "_ALLOWED_BACKENDS",
        set(web_server._ALLOWED_BACKENDS) | {WebBlockingAdapter.name},
    )

    with TestClient(web_server.app) as client:
        response = client.post(
            "/api/dispatch",
            json={
                "task": "wait forever",
                "backend": WebBlockingAdapter.name,
                "repo_path": "/tmp/cv-demo",
            },
        )
        task_id = response.json()["task_id"]
        cancelled = client.post(f"/api/task/{task_id}/cancel")
        assert cancelled.status_code == 200

        detail = client.get(f"/api/task/{task_id}")
        assert detail.json()["status"] == "cancelled"


async def test_mcp_delegation_updates_shared_task_record():
    delegated = await call_tool(
        "coding_vibe_delegate_to_ceo",
        {
            "task_id": "mcp-task",
            "description": "MCP integration test",
            "repo_path": "/tmp/cv-demo",
        },
    )
    claimed = await call_tool(
        "coding_vibe_claim_delegation", {"task_id": "mcp-task"}
    )
    completed = await call_tool(
        "coding_vibe_complete_delegation",
        {
            "task_id": "mcp-task",
            "summary": "MCP task complete",
            "files_changed": ["result.py"],
        },
    )

    assert json.loads(delegated[0].text)["status"] == "delegated"
    assert json.loads(claimed[0].text)["status"] == "claimed"
    assert json.loads(completed[0].text)["status"] == "completed"
    task = load_state()["tasks"]["mcp-task"]
    assert task["status"] == "completed"
    assert task["result"]["files_changed"] == ["result.py"]


async def test_mcp_rejects_task_id_path_traversal():
    with pytest.raises(ValueError, match="task_id must contain"):
        await call_tool(
            "coding_vibe_delegate_to_ceo",
            {
                "task_id": "../outside",
                "description": "invalid",
                "repo_path": "/tmp/cv-demo",
            },
        )


async def test_mcp_complete_requires_running_and_is_idempotent():
    await call_tool(
        "coding_vibe_delegate_to_ceo",
        {
            "task_id": "mcp-transition",
            "description": "transition test",
            "repo_path": "/tmp/cv-demo",
        },
    )
    too_early = await call_tool(
        "coding_vibe_complete_delegation",
        {"task_id": "mcp-transition", "summary": "too early"},
    )
    assert json.loads(too_early[0].text)["status"] == "invalid_state"

    await call_tool(
        "coding_vibe_claim_delegation", {"task_id": "mcp-transition"}
    )
    first = await call_tool(
        "coding_vibe_complete_delegation",
        {"task_id": "mcp-transition", "summary": "done"},
    )
    second = await call_tool(
        "coding_vibe_complete_delegation",
        {"task_id": "mcp-transition", "summary": "duplicate"},
    )

    assert json.loads(first[0].text)["status"] == "completed"
    assert json.loads(second[0].text)["status"] == "already_completed"
    completed = [
        checkpoint
        for checkpoint in load_state()["checkpoints"]
        if checkpoint.get("task_id") == "mcp-transition"
        and checkpoint["milestone"] == "task-complete"
    ]
    assert len(completed) == 1
