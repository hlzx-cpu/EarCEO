"""Integration tests for the Android-facing Mobile API v1."""

from __future__ import annotations

import asyncio
import json
from typing import AsyncIterator

import pytest

pytest.importorskip("fastapi")

from fastapi.testclient import TestClient

from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult
from receptionist.registry import register_adapter
from receptionist.state import load_state
from web import mobile_api
from web import server as web_server


TOKEN = "mobile-test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class MobileBlockingAdapter(HarnessAdapter):
    name = "test-mobile-blocking"

    async def spawn(self, task, *, repo_path, context=None):
        return "mobile-blocking-handle"

    async def stream_status(self, handle) -> AsyncIterator[StatusEvent]:
        yield StatusEvent(kind="progress", text="waiting")
        await asyncio.Event().wait()

    async def result(self, handle):
        return TaskResult(True, "unexpected completion", [], "")


@pytest.fixture(autouse=True)
def isolated_mobile_backend(tmp_path, monkeypatch):
    state_dir = tmp_path / "state"
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    monkeypatch.setenv("CODING_VIBE_STATE_DIR", str(state_dir))
    monkeypatch.setenv("EARCEO_API_TOKEN", TOKEN)
    monkeypatch.delenv("EARCEO_APPROVAL_RISKS", raising=False)
    monkeypatch.delenv("EARCEO_APPROVAL_TTL_SECONDS", raising=False)
    monkeypatch.setenv(
        "EARCEO_PROJECTS",
        json.dumps({"adventurex-demo": str(project_dir)}),
    )
    monkeypatch.setenv("EARCEO_BACKEND", "mock")
    register_adapter(MobileBlockingAdapter)
    return project_dir


def _create_session(
    client: TestClient, client_session_id: str = "client-session-1"
) -> str:
    response = client.post(
        "/v1/sessions",
        headers=AUTH,
        json={
            "client_session_id": client_session_id,
            "client": {
                "name": "earceo-android",
                "version": "0.2.0",
                "locale": "zh-CN",
            },
            "project_id": "adventurex-demo",
        },
    )
    assert response.status_code == 200
    return response.json()["session_id"]


def _submit_turn(
    client: TestClient,
    session_id: str,
    turn_id: str = "turn-1",
    text: str = "Add a health endpoint",
):
    return client.post(
        f"/v1/sessions/{session_id}/turns",
        headers={**AUTH, "Idempotency-Key": turn_id},
        json={
            "client_turn_id": turn_id,
            "project_id": "adventurex-demo",
            "input": {
                "type": "final_transcript",
                "text": text,
                "locale": "zh-CN",
                "source": "viaim-text-stream",
            },
            "client_context": {
                "headset": "iFLYBUDS Pro 3",
                "wav_captured": True,
            },
        },
    )


def _require_project_approval(monkeypatch, risk_level: str = "R2") -> None:
    monkeypatch.setenv(
        "EARCEO_APPROVAL_RISKS",
        json.dumps({"adventurex-demo": risk_level}),
    )
    monkeypatch.setenv("EARCEO_APPROVAL_TTL_SECONDS", "600")


def _decide_approval(
    client: TestClient,
    session_id: str,
    approval_id: str,
    *,
    decision: str,
    decision_id: str = "decision-1",
):
    return client.post(
        f"/v1/sessions/{session_id}/approvals/{approval_id}",
        headers={**AUTH, "Idempotency-Key": decision_id},
        json={
            "client_decision_id": decision_id,
            "decision": decision,
        },
    )


def test_mobile_api_requires_bearer_token():
    with TestClient(web_server.app) as client:
        response = client.post(
            "/v1/sessions",
            json={
                "client_session_id": "client-session-1",
                "client": {
                    "name": "earceo-android",
                    "version": "0.2.0",
                },
                "project_id": "adventurex-demo",
            },
        )

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_session_creation_is_idempotent():
    with TestClient(web_server.app) as client:
        first = _create_session(client)
        second = _create_session(client)

    assert first == second
    assert len(load_state()["mobile_sessions"]) == 1


def test_turn_retry_creates_only_one_backend_task():
    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        first = _submit_turn(client, session_id)
        second = _submit_turn(client, session_id)

        assert first.status_code == 200
        assert second.status_code == 200

    state = load_state()
    assert list(state["mobile_turns"]) == ["turn-1"]
    assert list(state["tasks"]) == ["turn-1"]
    assert state["mobile_turns"]["turn-1"]["status"] == "completed"


def test_session_status_does_not_echo_transcript_or_client_context():
    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        response = client.get(f"/v1/sessions/{session_id}", headers=AUTH)

    assert response.status_code == 200
    turn = response.json()["turns"][0]
    assert turn["turn_id"] == "turn-1"
    assert "input_text" not in turn
    assert "client_context" not in turn
    assert "command_hash" not in turn


def test_idempotency_key_cannot_be_reused_for_different_text():
    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        conflict = _submit_turn(
            client,
            session_id,
            text="Delete a different thing",
        )

    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "DUPLICATE_TURN"


def test_sse_once_returns_ordered_persisted_events():
    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        response = client.get(
            f"/v1/sessions/{session_id}/events",
            headers=AUTH,
            params={"once": "true"},
        )

    assert response.status_code == 200
    assert "event: turn.accepted" in response.text
    assert "event: task.progress" in response.text
    assert "event: task.completed" in response.text
    events = load_state()["mobile_events"][session_id]
    assert [event["sequence"] for event in events] == list(
        range(1, len(events) + 1)
    )


def test_project_id_must_be_allow_listed():
    with TestClient(web_server.app) as client:
        response = client.post(
            "/v1/sessions",
            headers=AUTH,
            json={
                "client_session_id": "client-session-1",
                "client": {
                    "name": "earceo-android",
                    "version": "0.2.0",
                },
                "project_id": "not-allowed",
            },
        )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PROJECT_NOT_ALLOWED"


def test_r2_project_waits_for_persisted_approval_before_dispatch(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        submitted = _submit_turn(client, session_id)
        session = client.get(f"/v1/sessions/{session_id}", headers=AUTH)

    assert submitted.status_code == 200
    assert submitted.json()["status"] == "waiting_approval"
    state = load_state()
    turn = state["mobile_turns"]["turn-1"]
    approval = state["mobile_approvals"][turn["approval_id"]]
    assert state["tasks"] == {}
    assert approval["risk_level"] == "R2"
    assert approval["status"] == "pending"
    assert approval["choices"] == ["approve", "reject"]
    assert [event["type"] for event in state["mobile_events"][session_id]] == [
        "turn.accepted",
        "approval.required",
    ]
    public = session.json()
    assert public["turns"][0]["status"] == "waiting_approval"
    assert public["approvals"][0]["approval_id"] == approval["approval_id"]
    assert "input_text" not in json.dumps(public)
    assert "command_hash" not in json.dumps(public)


def test_approval_is_single_use_and_duplicate_approve_is_harmless(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        approval_id = load_state()["mobile_turns"]["turn-1"]["approval_id"]

        approved = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="approve",
        )
        duplicate = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="approve",
        )
        conflict = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="reject",
            decision_id="decision-2",
        )

    assert approved.status_code == 200
    assert approved.json()["status"] == "approved"
    assert duplicate.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "APPROVAL_ALREADY_DECIDED"
    state = load_state()
    assert list(state["tasks"]) == ["turn-1"]
    assert state["mobile_turns"]["turn-1"]["status"] == "completed"
    assert sum(
        event["type"] == "approval.resolved"
        for event in state["mobile_events"][session_id]
    ) == 1


def test_rejected_approval_never_dispatches_and_retry_is_idempotent(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        approval_id = load_state()["mobile_turns"]["turn-1"]["approval_id"]

        rejected = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="reject",
        )
        duplicate = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="reject",
        )

    assert rejected.status_code == 200
    assert rejected.json()["status"] == "rejected"
    assert rejected.json()["turn_status"] == "cancelled"
    assert duplicate.status_code == 200
    state = load_state()
    assert state["tasks"] == {}
    assert state["mobile_turns"]["turn-1"]["status"] == "cancelled"
    assert sum(
        event["type"] == "approval.resolved"
        for event in state["mobile_events"][session_id]
    ) == 1


def test_expired_approval_cannot_resume_task(monkeypatch):
    _require_project_approval(monkeypatch)
    now = 1_000.0
    monkeypatch.setattr(mobile_api, "_epoch_now", lambda: now)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        approval_id = load_state()["mobile_turns"]["turn-1"]["approval_id"]
        now = 1_601.0
        expired = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="approve",
        )
        repeated = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="approve",
        )

    assert expired.status_code == 409
    assert expired.json()["error"]["code"] == "APPROVAL_EXPIRED"
    assert repeated.status_code == 409
    state = load_state()
    assert state["tasks"] == {}
    assert state["mobile_approvals"][approval_id]["status"] == "expired"
    assert state["mobile_turns"]["turn-1"]["status"] == "failed"
    assert sum(
        event["data"].get("code") == "APPROVAL_EXPIRED"
        for event in state["mobile_events"][session_id]
    ) == 1


def test_r3_can_only_be_rejected_from_mobile_client(monkeypatch):
    _require_project_approval(monkeypatch, risk_level="R3")

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        approval_id = load_state()["mobile_turns"]["turn-1"]["approval_id"]
        forbidden = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="approve",
        )
        rejected = _decide_approval(
            client,
            session_id,
            approval_id,
            decision="reject",
        )

    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "APPROVAL_NOT_ALLOWED"
    assert rejected.status_code == 200
    assert load_state()["tasks"] == {}


def test_decision_id_cannot_be_reused_for_another_approval(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id, turn_id="turn-1").status_code == 200
        assert _submit_turn(client, session_id, turn_id="turn-2").status_code == 200
        state = load_state()
        first_approval = state["mobile_turns"]["turn-1"]["approval_id"]
        second_approval = state["mobile_turns"]["turn-2"]["approval_id"]
        assert _decide_approval(
            client,
            session_id,
            first_approval,
            decision="reject",
            decision_id="decision-shared",
        ).status_code == 200
        conflict = _decide_approval(
            client,
            session_id,
            second_approval,
            decision="reject",
            decision_id="decision-shared",
        )

    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "DUPLICATE_DECISION"
    assert load_state()["mobile_approvals"][second_approval]["status"] == "pending"


def test_approval_cannot_be_decided_through_another_session(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        first_session = _create_session(client, "client-session-1")
        second_session = _create_session(client, "client-session-2")
        assert _submit_turn(client, first_session).status_code == 200
        approval_id = load_state()["mobile_turns"]["turn-1"]["approval_id"]
        response = _decide_approval(
            client,
            second_session,
            approval_id,
            decision="reject",
        )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "APPROVAL_NOT_FOUND"
    assert load_state()["mobile_approvals"][approval_id]["status"] == "pending"


def test_waiting_approval_can_be_cancelled_without_dispatch(monkeypatch):
    _require_project_approval(monkeypatch)

    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        assert _submit_turn(client, session_id).status_code == 200
        response = client.post(
            f"/v1/sessions/{session_id}/turns/turn-1/cancel",
            headers=AUTH,
        )

    assert response.status_code == 200
    state = load_state()
    turn = state["mobile_turns"]["turn-1"]
    assert turn["status"] == "cancelled"
    assert state["mobile_approvals"][turn["approval_id"]]["status"] == "rejected"
    assert state["tasks"] == {}


def test_mobile_turn_can_be_cancelled(monkeypatch):
    monkeypatch.setenv("EARCEO_BACKEND", MobileBlockingAdapter.name)
    with TestClient(web_server.app) as client:
        session_id = _create_session(client)
        submitted = _submit_turn(client, session_id, turn_id="turn-cancel")
        assert submitted.status_code == 200

        cancelled = client.post(
            f"/v1/sessions/{session_id}/turns/turn-cancel/cancel",
            headers=AUTH,
        )
        assert cancelled.status_code == 200

    assert load_state()["mobile_turns"]["turn-cancel"]["status"] == "cancelled"
