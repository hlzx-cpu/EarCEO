"""Concurrency and corruption tests for the shared JSON state store."""

from __future__ import annotations

import json
import multiprocessing
import os
from concurrent.futures import ThreadPoolExecutor

import pytest

from receptionist.state import (
    JsonStateStore,
    StateCorruptionError,
    append_checkpoint,
    load_state,
)


def _append_in_process(state_dir: str, prefix: str, count: int) -> None:
    os.environ["CODING_VIBE_STATE_DIR"] = state_dir
    for index in range(count):
        append_checkpoint(f"{prefix}-{index}", "process checkpoint")


@pytest.fixture(autouse=True)
def isolated_state_dir(tmp_path, monkeypatch):
    state_dir = tmp_path / "state"
    monkeypatch.setenv("CODING_VIBE_STATE_DIR", str(state_dir))
    return state_dir


def test_invalid_json_is_reported(isolated_state_dir):
    state_file = isolated_state_dir / "session.json"
    isolated_state_dir.mkdir()
    state_file.write_text('{"checkpoints": [', encoding="utf-8")

    with pytest.raises(StateCorruptionError, match="Invalid JSON"):
        load_state()


def test_non_object_json_is_reported(isolated_state_dir):
    state_file = isolated_state_dir / "session.json"
    isolated_state_dir.mkdir()
    state_file.write_text("[]", encoding="utf-8")

    with pytest.raises(StateCorruptionError, match="JSON object"):
        load_state()


def test_invalid_schema_collection_is_reported(isolated_state_dir):
    state_file = isolated_state_dir / "session.json"
    isolated_state_dir.mkdir()
    state_file.write_text('{"tasks": []}', encoding="utf-8")

    with pytest.raises(StateCorruptionError, match="'tasks' must be a dict"):
        load_state()


def test_missing_schema_fields_are_normalised(isolated_state_dir):
    isolated_state_dir.mkdir()
    (isolated_state_dir / "session.json").write_text(
        json.dumps({"custom": True}), encoding="utf-8"
    )

    state = load_state()

    assert state["checkpoints"] == []
    assert state["delegations"] == []
    assert state["tasks"] == {}
    assert state["mobile_approvals"] == {}
    assert state["mobile_decision_ids"] == {}
    assert state["custom"] is True


def test_threaded_updates_do_not_lose_checkpoints(isolated_state_dir):
    count = 80

    def append(index: int) -> None:
        append_checkpoint(f"thread-{index}", "thread checkpoint")

    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(append, range(count)))

    state = load_state()
    assert len(state["checkpoints"]) == count
    assert len({item["milestone"] for item in state["checkpoints"]}) == count


def test_process_updates_do_not_lose_checkpoints(isolated_state_dir):
    process_count = 3
    checkpoints_per_process = 12
    context = multiprocessing.get_context("spawn")
    processes = [
        context.Process(
            target=_append_in_process,
            args=(str(isolated_state_dir), f"p{index}", checkpoints_per_process),
        )
        for index in range(process_count)
    ]

    for process in processes:
        process.start()
    for process in processes:
        process.join(timeout=15)
        assert process.exitcode == 0

    state = load_state()
    assert len(state["checkpoints"]) == process_count * checkpoints_per_process


def test_atomic_save_leaves_no_temporary_file(isolated_state_dir):
    store = JsonStateStore(isolated_state_dir)
    store.save({"checkpoints": [], "delegations": [], "tasks": {}})

    assert store.state_file.exists()
    assert list(isolated_state_dir.glob(".session.*.tmp")) == []
