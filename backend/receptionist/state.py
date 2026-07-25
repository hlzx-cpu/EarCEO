"""Reliable JSON state storage shared by the receptionist and MCP bridge.

The store is intentionally small, but it still needs database-like write
semantics: several async tasks (and sometimes the MCP process) can update the
same session at once.  Writes therefore use an inter-process lock and an atomic
``os.replace`` so a crash cannot leave a partially-written ``session.json``.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from contextlib import contextmanager
from copy import deepcopy
from pathlib import Path
from typing import Any, Callable, Iterator

import fcntl


State = dict[str, Any]
StateMutator = Callable[[State], Any]
_THREAD_LOCKS: dict[str, threading.RLock] = {}
_THREAD_LOCKS_GUARD = threading.Lock()


class StateCorruptionError(RuntimeError):
    """Raised when an existing state file is not valid JSON."""


def _get_state_dir() -> Path:
    """Read CODING_VIBE_STATE_DIR at call time so monkeypatch works."""
    return Path(os.environ.get("CODING_VIBE_STATE_DIR", Path.home() / ".coding-vibe"))


def _get_state_file() -> Path:
    return _get_state_dir() / "session.json"


def _new_state() -> State:
    return {
        "checkpoints": [],
        "delegations": [],
        "tasks": {},
        "mobile_sessions": {},
        "mobile_session_ids": {},
        "mobile_turns": {},
        "mobile_approvals": {},
        "mobile_decision_ids": {},
        "mobile_events": {},
        "created_at": time.time(),
    }


def _normalise_state(state: State) -> State:
    """Fill required collection fields without discarding future fields."""
    expected_collections = {
        "checkpoints": list,
        "delegations": list,
        "tasks": dict,
        "mobile_sessions": dict,
        "mobile_session_ids": dict,
        "mobile_turns": dict,
        "mobile_approvals": dict,
        "mobile_decision_ids": dict,
        "mobile_events": dict,
    }
    for field, expected_type in expected_collections.items():
        state.setdefault(field, expected_type())
        if not isinstance(state[field], expected_type):
            raise StateCorruptionError(
                f"State field {field!r} must be a {expected_type.__name__}"
            )
    state.setdefault("created_at", time.time())
    return state


class JsonStateStore:
    """Atomic, cross-process-safe storage for one ``session.json`` file."""

    def __init__(self, state_dir: str | Path | None = None) -> None:
        self.state_dir = Path(state_dir) if state_dir is not None else _get_state_dir()
        self.state_file = self.state_dir / "session.json"
        self.lock_file = self.state_dir / ".session.lock"
        lock_key = str(self.lock_file.resolve())
        with _THREAD_LOCKS_GUARD:
            self._thread_lock = _THREAD_LOCKS.setdefault(lock_key, threading.RLock())

    @contextmanager
    def _locked(self) -> Iterator[None]:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        with self._thread_lock:
            with self.lock_file.open("a+") as lock:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def _load_unlocked(self) -> State:
        if not self.state_file.exists():
            return _new_state()
        try:
            loaded = json.loads(self.state_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise StateCorruptionError(
                f"Invalid JSON in state file {self.state_file}: {exc}"
            ) from exc
        if not isinstance(loaded, dict):
            raise StateCorruptionError(
                f"State file {self.state_file} must contain a JSON object"
            )
        return _normalise_state(loaded)

    def _save_unlocked(self, state: State) -> None:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        fd, temporary_path = tempfile.mkstemp(
            dir=self.state_dir,
            prefix=".session.",
            suffix=".tmp",
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as temporary:
                json.dump(state, temporary, indent=2, ensure_ascii=False)
                temporary.write("\n")
                temporary.flush()
                os.fsync(temporary.fileno())
            os.replace(temporary_path, self.state_file)
        finally:
            if os.path.exists(temporary_path):
                os.unlink(temporary_path)

    def load(self) -> State:
        with self._locked():
            return deepcopy(self._load_unlocked())

    def save(self, state: State) -> None:
        with self._locked():
            self._save_unlocked(_normalise_state(deepcopy(state)))

    def update(self, mutator: StateMutator) -> Any:
        """Apply *mutator* inside one read-modify-write transaction."""
        with self._locked():
            state = self._load_unlocked()
            value = mutator(state)
            self._save_unlocked(state)
            return value

    def reset(self) -> None:
        with self._locked():
            self.state_file.unlink(missing_ok=True)


def _store() -> JsonStateStore:
    """Resolve the environment on every call so test monkeypatching works."""
    return JsonStateStore()


def load_state() -> State:
    """Return the current session dict, or a fresh skeleton."""
    return _store().load()


def save_state(state: State) -> None:
    """Persist session dict to disk."""
    _store().save(state)


def update_state(mutator: StateMutator) -> Any:
    """Atomically apply a read-modify-write operation to session state."""
    return _store().update(mutator)


def create_task(task: State) -> State:
    """Persist a new task record, rejecting duplicate task IDs."""
    task_id = str(task["task_id"])
    record = deepcopy(task)

    def add(state: State) -> State:
        if task_id in state["tasks"]:
            raise ValueError(f"Task {task_id!r} already exists")
        state["tasks"][task_id] = record
        return deepcopy(record)

    return update_state(add)


def get_task(task_id: str) -> State | None:
    """Return one persisted task record, if present."""
    task = load_state()["tasks"].get(task_id)
    return deepcopy(task) if task is not None else None


def update_task(task_id: str, **changes: Any) -> State:
    """Atomically update a task record and return the updated snapshot."""
    updates = deepcopy(changes)

    def apply(state: State) -> State:
        try:
            task = state["tasks"][task_id]
        except KeyError as exc:
            raise KeyError(f"Unknown task: {task_id!r}") from exc
        task.update(updates)
        task["updated_at"] = time.time()
        return deepcopy(task)

    return update_state(apply)


def append_checkpoint(
    milestone: str,
    message: str,
    *,
    progress_pct: int = 50,
    task_id: str | None = None,
    files_changed: list[str] | None = None,
) -> dict[str, Any]:
    """Load state, append a checkpoint, save, and return the checkpoint dict.

    Mirrors the checkpoint shape written by ``coding_vibe_checkpoint`` and
    ``coding_vibe_complete_delegation`` in ``cv_mcp/server.py``.
    """
    checkpoint: dict[str, Any] = {
        "milestone": milestone,
        "message": message,
        "progress_pct": progress_pct,
        "timestamp": time.time(),
    }
    if task_id is not None:
        checkpoint["task_id"] = task_id
    if files_changed is not None:
        checkpoint["files_changed"] = files_changed

    def add(state: State) -> None:
        state["checkpoints"].append(checkpoint)
        state["last_checkpoint"] = checkpoint

    update_state(add)
    return checkpoint


def reset_state() -> None:
    """Delete the current session.json — useful in tests."""
    _store().reset()
