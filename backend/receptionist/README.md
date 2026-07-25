# receptionist — durable harness-agnostic task orchestration

```
receptionist/
├── adapters/
│   ├── base.py        # HarnessAdapter ABC + StatusEvent + TaskResult
│   ├── mock.py        # MockAdapter  (name="mock")
│   ├── claude_code.py # ClaudeCodeAdapter  (name="claude-code")
│   └── openopc.py     # OpenOPCAdapter  (name="openopc")
├── core.py            # lifecycle, dispatch, result, failure, cancellation
├── registry.py        # register_adapter / get_adapter / list_adapters
├── state.py           # locked + atomic ~/.coding-vibe/session.json store
└── tests/
    ├── test_dispatch.py
    ├── test_lifecycle.py
    ├── test_state_store.py
    └── test_entrypoints.py
```

## The Adapter Contract

```python
from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult

class MyAdapter(HarnessAdapter):
    name = "my-harness"          # registry key

    async def spawn(self, task: str, *, repo_path: str, context=None) -> str: ...
    async def stream_status(self, handle: str): ...   # -> AsyncIterator[StatusEvent]
    async def result(self, handle: str) -> TaskResult: ...
    async def cancel(self, handle: str) -> None: ...   # default no-op
```

**`StatusEvent`**
```
kind  ∈ {"progress" | "tool" | "message" | "done" | "error"}
text  : str
```

**`TaskResult`**
```
ok          : bool
summary     : str
files_changed : list[str]
raw         : str   # full harness output
```

## Registering a new adapter

```python
from receptionist.registry import register_adapter
from receptionist.adapters.base import HarnessAdapter, StatusEvent, TaskResult

@register_adapter
class AiderAdapter(HarnessAdapter):
    name = "aider"
    # ... implement spawn / stream_status / result
```

Or place the class in `receptionist/adapters/` and add it to `receptionist/adapters/__init__.py` alongside the existing imports.

## Task lifecycle

```
pending → running → completed
                  ↘ failed
pending/running → cancelled
```

`dispatch()` and `dispatch_async()` call the same private execution path. Each
transition and adapter event is persisted with the canonical task ID. A normal
adapter failure returns `TaskResult(ok=False)`; an unexpected crash is recorded
and later raised as `TaskExecutionError`.

```python
from receptionist import Receptionist

receptionist = Receptionist()
task_id = await receptionist.dispatch_async(
    "Add a health endpoint",
    backend="mock",
    repo_path="/tmp/project",
)

result = await receptionist.result(task_id)
assert result is not None
print(result.summary)

# For a still-running local task:
await receptionist.cancel(task_id)
```

The final result is stored under `state["tasks"][task_id]["result"]`, so another
Receptionist instance can restore it. Only the process that owns a live adapter
can safely cancel it.

## Currently registered adapters

| Key | Backend | Notes |
|-----|---------|-------|
| `mock` | In-memory | All tests use this by default |
| `claude-code` | `claude -p --output-format stream-json` | Needs `claude` on PATH |
| `openopc` | `uv run opc exec … --stream-json` | Needs `opc` + `uv` on PATH |

## Running tests

```bash
# Install dev deps
pip install pytest pytest-asyncio

# Run
pytest -p no:cacheprovider -q
```

## State reuse

`JsonStateStore` combines a process-local reentrant lock, `flock`, and
write-to-temp + `os.replace`. Its `update()` method holds the lock across the
full read—modify—write transaction, preventing lost updates between
Receptionist, Web, and MCP processes. Invalid JSON raises
`StateCorruptionError`; it is never silently replaced with an empty session.

Do **not** import or depend on `cv_mcp` inside receptionist. The state helpers
stand alone.

## Upgrade path

When any harness ships a production A2A adapter, the stdio transport inside
`claude_code.py` / `openopc.py` is the natural seam to swap. The
`HarnessAdapter` interface and the Receptionist dispatch loop remain unchanged.
