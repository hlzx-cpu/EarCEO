"""receptionist/__init__.py — public surface."""

from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult
from receptionist.core import (
    Receptionist,
    TaskCancelledError,
    TaskExecutionError,
    TaskStatus,
)
from receptionist.registry import get_adapter, list_adapters, register_adapter

__all__ = [
    "HarnessAdapter",
    "StatusEvent",
    "TaskResult",
    "Receptionist",
    "TaskStatus",
    "TaskExecutionError",
    "TaskCancelledError",
    "register_adapter",
    "get_adapter",
    "list_adapters",
]
