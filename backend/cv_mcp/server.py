"""
Coding Vibe MCP Server — Customer Service ↔ CEO Bridge.

A lightweight MCP server that acts as the checkpoint/reporting bridge between
a fast customer-service model and a powerful CEO reasoning model.

Architecture:
  User (voice) → Fast CS Model → [checkpoint] → MCP → User notified
                  ↓ (handoff via delegate_to_ceo)
                Delegation file written to ~/.coding-vibe/
                  ↓ (next turn)
                Hook detects pending delegation → injects CEO protocol
                  ↓
                SAME harness, CEO role → picks up delegation → executes
                  ↓ (complete_delegation)
                Hook detects completion → switches back to CS protocol
                  ↓
                CS model delivers results to user

Harness-agnostic: works with any MCP-compatible harness (Claude Code, Codex, Cursor, etc.)
"""

import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# The MCP server is commonly launched as ``python cv_mcp/server.py``, which
# otherwise places only cv_mcp/ on sys.path.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from receptionist.state import (  # noqa: E402
    JsonStateStore,
    append_checkpoint,
    load_state,
    update_state,
)


def _state_dir() -> Path:
    return JsonStateStore().state_dir


def _validate_task_id(task_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", task_id):
        raise ValueError(
            "task_id must contain only letters, digits, '.', '_', or '-'"
        )
    return task_id


def _write_delegation(delegation: dict[str, Any]) -> Path:
    """Atomically mirror one delegation for the legacy hook pickup flow."""
    state_dir = _state_dir()
    state_dir.mkdir(parents=True, exist_ok=True)
    target = state_dir / f"delegation_{delegation['task_id']}.json"
    fd, temporary_path = tempfile.mkstemp(
        dir=state_dir, prefix=f".delegation_{delegation['task_id']}.", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as temporary:
            json.dump(delegation, temporary, indent=2, ensure_ascii=False)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, target)
    finally:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)
    return target


# --- MCP Server ---
server = Server("coding-vibe")


@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="coding_vibe_checkpoint",
            description=(
                "Report a progress checkpoint to the user. Call this at EVERY important milestone — "
                "after understanding the user's request, before starting work, when a sub-task completes, "
                "when results are ready. This keeps the user informed without them having to ask."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "milestone": {
                        "type": "string",
                        "description": "Short name of the milestone reached (e.g., 'requirements-gathered', 'code-generated', 'review-complete')",
                    },
                    "message": {
                        "type": "string",
                        "description": "Human-readable progress update to show the user. Be specific about what was done and what's next.",
                    },
                    "progress_pct": {
                        "type": "number",
                        "description": "Estimated completion percentage (0-100). Use 0 for 'just starting', 100 for 'all done'.",
                    },
                    "task_id": {
                        "type": "string",
                        "description": "Optional canonical task ID this checkpoint belongs to.",
                    },
                },
                "required": ["milestone", "message"],
            },
        ),
        Tool(
            name="coding_vibe_delegate_to_ceo",
            description=(
                "Delegate a complex reasoning/coding task to the CEO model. The CEO has access to "
                "a full engineering team (architect, builder, reviewer) via OpenOPC. "
                "Use this when the task requires deep reasoning, multi-file changes, or architectural decisions. "
                "Writes a delegation file that the CEO picks up on the next turn via the hook system."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "Unique identifier for this delegation (e.g., 'add-auth-endpoint', 'fix-database-migration')",
                    },
                    "description": {
                        "type": "string",
                        "description": "Complete task description with requirements, constraints, and expected output. Be specific — the CEO needs full context.",
                    },
                    "repo_path": {
                        "type": "string",
                        "description": "Absolute path to the project repository the CEO should work in.",
                    },
                    "priority": {
                        "type": "string",
                        "enum": ["low", "normal", "high", "urgent"],
                        "description": "Priority level for the task.",
                    },
                    "files_to_modify": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Specific files that need to be changed or created.",
                    },
                },
                "required": ["task_id", "description", "repo_path"],
            },
        ),
        Tool(
            name="coding_vibe_session_state",
            description=(
                "Get the current session state — all checkpoints reported so far, pending delegations, "
                "and active CEO tasks. Use this to understand context before responding to the user."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
            },
        ),
        Tool(
            name="coding_vibe_claim_delegation",
            description=(
                "CEO: Claim a pending delegation and mark it as in-progress. "
                "Call this when you (as CEO) pick up a delegation to work on it. "
                "Returns the full delegation details including task description, repo path, and files."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "The task_id of the pending delegation to claim. Use 'auto' to claim the oldest pending delegation.",
                    },
                },
                "required": ["task_id"],
            },
        ),
        Tool(
            name="coding_vibe_complete_delegation",
            description=(
                "CEO: Mark a delegation as completed. Call this when you finish the delegated task. "
                "This also records a checkpoint so the CS model can pick up your results and deliver them to the user. "
                "Include a summary the CS model can read verbatim to the user."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "The task_id of the delegation you completed.",
                    },
                    "summary": {
                        "type": "string",
                        "description": "A summary of what was done — the CS model will relay this to the user. Include what changed, what was built, and any caveats.",
                    },
                    "files_changed": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of files that were modified or created.",
                    },
                },
                "required": ["task_id", "summary"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    if name == "coding_vibe_checkpoint":
        milestone = arguments["milestone"]
        message = arguments["message"]
        progress_pct = arguments.get("progress_pct", 50)
        append_checkpoint(
            milestone,
            message,
            progress_pct=progress_pct,
            task_id=arguments.get("task_id"),
        )
        state = load_state()

        result = {
            "status": "recorded",
            "checkpoint": milestone,
            "total_checkpoints": len(state["checkpoints"]),
            "message_delivered": f"[Coding Vibe] {message}",
        }
        return [TextContent(type="text", text=json.dumps(result, indent=2, ensure_ascii=False))]

    elif name == "coding_vibe_delegate_to_ceo":
        now = time.time()
        task_id = _validate_task_id(str(arguments["task_id"]))
        delegation = {
            "task_id": task_id,
            "description": arguments["description"],
            "repo_path": arguments["repo_path"],
            "priority": arguments.get("priority", "normal"),
            "files_to_modify": arguments.get("files_to_modify", []),
            "timestamp": now,
            "status": "pending",
        }

        def add_delegation(state: dict[str, Any]) -> bool:
            if any(
                item.get("task_id") == delegation["task_id"]
                for item in state["delegations"]
            ) or delegation["task_id"] in state["tasks"]:
                return False
            state["delegations"].append(delegation)
            state["tasks"].setdefault(
                delegation["task_id"],
                {
                    "task_id": delegation["task_id"],
                    "task": delegation["description"],
                    "backend": "mcp-delegation",
                    "repo_path": delegation["repo_path"],
                    "context": {
                        "task_id": delegation["task_id"],
                        "priority": delegation["priority"],
                        "files_to_modify": delegation["files_to_modify"],
                    },
                    "status": "pending",
                    "created_at": now,
                    "updated_at": now,
                },
            )
            return True

        if not update_state(add_delegation):
            result = {
                "status": "already_exists",
                "task_id": delegation["task_id"],
            }
            return [TextContent(type="text", text=json.dumps(result, indent=2, ensure_ascii=False))]

        # Write delegation file for CEO pickup
        delegation_file = _write_delegation(delegation)

        result = {
            "status": "delegated",
            "task_id": task_id,
            "message": f"Task '{task_id}' delegated to CEO. The CEO model will pick this up on the next turn.",
            "delegation_file": str(delegation_file),
        }
        return [TextContent(type="text", text=json.dumps(result, indent=2, ensure_ascii=False))]

    elif name == "coding_vibe_claim_delegation":
        requested_id = str(arguments["task_id"])
        task_id = (
            requested_id
            if requested_id == "auto"
            else _validate_task_id(requested_id)
        )
        now = time.time()

        def claim(state: dict[str, Any]) -> dict[str, Any]:
            delegation = None
            for item in state["delegations"]:
                if task_id == "auto":
                    if item["status"] == "pending":
                        delegation = item
                        break
                elif item["task_id"] == task_id:
                    delegation = item
                    break
            if delegation is None:
                return {
                    "_outcome": "not_found",
                    "pending_tasks": [
                        item["task_id"]
                        for item in state["delegations"]
                        if item["status"] == "pending"
                    ],
                }
            if delegation["status"] != "pending":
                return {
                    "_outcome": "already_claimed",
                    "delegation": dict(delegation),
                }
            delegation["status"] = "running"
            delegation["claimed_at"] = now
            task_record = state["tasks"].get(delegation["task_id"])
            if task_record is None:
                task_record = {
                    "task_id": delegation["task_id"],
                    "task": delegation.get("description", ""),
                    "backend": "mcp-delegation",
                    "repo_path": delegation.get("repo_path", ""),
                    "context": {"task_id": delegation["task_id"]},
                    "created_at": delegation.get("timestamp", now),
                }
                state["tasks"][delegation["task_id"]] = task_record
            task_record.update(
                {
                    "status": "running",
                    "started_at": now,
                    "updated_at": now,
                }
            )
            return {"_outcome": "claimed", "delegation": dict(delegation)}

        claim_result = update_state(claim)
        delegation = claim_result.get("delegation")

        if not delegation:
            return [TextContent(type="text", text=json.dumps({
                "status": "not_found",
                "task_id": task_id,
                "pending_tasks": claim_result.get("pending_tasks", []),
            }, indent=2, ensure_ascii=False))]

        if claim_result["_outcome"] == "already_claimed":
            return [TextContent(type="text", text=json.dumps({
                "status": "already_claimed",
                "task_id": delegation["task_id"],
                "current_status": delegation["status"],
            }, indent=2, ensure_ascii=False))]

        _write_delegation(delegation)

        return [TextContent(type="text", text=json.dumps({
            "status": "claimed",
            "task_id": delegation["task_id"],
            "description": delegation["description"],
            "repo_path": delegation["repo_path"],
            "priority": delegation["priority"],
            "files_to_modify": delegation["files_to_modify"],
            "message": "You have claimed this task. Execute it now. When done, call coding_vibe_complete_delegation.",
        }, indent=2, ensure_ascii=False))]

    elif name == "coding_vibe_complete_delegation":
        task_id = _validate_task_id(str(arguments["task_id"]))
        summary = arguments["summary"]
        files_changed = arguments.get("files_changed", [])
        now = time.time()

        def complete(state: dict[str, Any]) -> dict[str, Any]:
            delegation = next(
                (
                    item
                    for item in state["delegations"]
                    if item["task_id"] == task_id
                ),
                None,
            )
            if delegation is None:
                return {"_outcome": "not_found"}
            if delegation.get("status") == "completed":
                return {
                    "_outcome": "already_completed",
                    "delegation": dict(delegation),
                }
            if delegation.get("status") != "running":
                return {
                    "_outcome": "invalid_state",
                    "delegation": dict(delegation),
                }
            delegation.update(
                {
                    "status": "completed",
                    "completed_at": now,
                    "summary": summary,
                    "files_changed": files_changed,
                }
            )
            task_record = state["tasks"].get(task_id)
            if task_record is None:
                task_record = {
                    "task_id": task_id,
                    "task": delegation.get("description", ""),
                    "backend": "mcp-delegation",
                    "repo_path": delegation.get("repo_path", ""),
                    "context": {"task_id": task_id},
                    "created_at": delegation.get("timestamp", now),
                }
                state["tasks"][task_id] = task_record
            task_record.update(
                {
                    "status": "completed",
                    "completed_at": now,
                    "updated_at": now,
                    "result": {
                        "ok": True,
                        "summary": summary,
                        "files_changed": files_changed,
                        "raw": "",
                    },
                }
            )
            checkpoint = {
                "milestone": "task-complete",
                "message": summary,
                "progress_pct": 100,
                "task_id": task_id,
                "files_changed": files_changed,
                "timestamp": now,
            }
            state["checkpoints"].append(checkpoint)
            state["last_checkpoint"] = checkpoint
            return {
                "_outcome": "completed",
                "delegation": dict(delegation),
            }

        completion = update_state(complete)
        delegation = completion.get("delegation")

        if completion["_outcome"] == "not_found":
            return [TextContent(type="text", text=json.dumps({
                "status": "not_found",
                "task_id": task_id,
            }, indent=2, ensure_ascii=False))]
        if completion["_outcome"] != "completed":
            return [TextContent(type="text", text=json.dumps({
                "status": completion["_outcome"],
                "task_id": task_id,
                "current_status": delegation.get("status"),
            }, indent=2, ensure_ascii=False))]

        _write_delegation(delegation)

        return [TextContent(type="text", text=json.dumps({
            "status": "completed",
            "task_id": task_id,
            "checkpoint_recorded": True,
            "message": "Task marked complete. The CS model will pick up your results on the next turn and deliver them to the user.",
        }, indent=2, ensure_ascii=False))]

    elif name == "coding_vibe_session_state":
        state = load_state()
        # Also list delegation files on disk (defense in depth)
        pending_files = []
        invalid_files = []
        for path in sorted(_state_dir().glob("delegation_*.json")):
            try:
                delegation = json.loads(path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                invalid_files.append(str(path))
                continue
            if delegation.get("status") in ("pending", None):
                pending_files.append(str(path))
        state["pending_delegation_files"] = pending_files
        state["invalid_delegation_files"] = invalid_files
        return [TextContent(type="text", text=json.dumps(state, indent=2, ensure_ascii=False))]

    else:
        raise ValueError(f"Unknown tool: {name}")


async def main() -> None:
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
