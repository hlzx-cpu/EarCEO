# EarCEO Backend

EarCEO's in-repository backend core, based on
[onezion12344/coding-vibe](https://github.com/onezion12344/coding-vibe).

This directory contains the CEO Gateway used by the Android app in the EarCEO
monorepo. Android sends only reviewed text commands; headset PCM/WAV stays on
the phone. HFP, backend audio transport, ASR/TTS, and deployment remain outside
this milestone.

## Backend core

Each task now has one canonical `task_id` and a durable lifecycle:

```text
pending → running → completed
                  ↘ failed
pending/running → cancelled
```

- `Receptionist.dispatch()` and `dispatch_async()` share one execution core.
- `result(task_id)` returns the real `TaskResult`, including after a new
  `Receptionist` instance is created.
- Unexpected exceptions are stored with type, message, and traceback and also
  produce a `task-failed` checkpoint.
- `cancel(task_id)` asks the adapter to stop, cancels the local runner, and
  persists `cancelled`.
- Every progress checkpoint carries the same task ID.
- Web, MCP, and Receptionist use one atomic, locked `session.json` store.
- The Web process owns one shared Receptionist instead of creating one per
  request.

The detailed invariants and state schema are in
[`docs/backend-core.md`](docs/backend-core.md).

## Local development

```bash
cd /path/to/EarCEO/backend

# Create the environment if it does not already exist.
uv venv .venv

# Core tests plus optional Web/MCP integration tests.
uv pip install --python .venv/bin/python \
  -r requirements-dev.txt \
  -r web/requirements.txt \
  -r cv_mcp/requirements.txt

PYTHONPYCACHEPREFIX=/tmp/earceo-pycache \
  .venv/bin/pytest -p no:cacheprovider -q
```

Run the local text API:

```bash
export CODING_VIBE_STATE_DIR=/tmp/earceo-state
export CV_ALLOWED_BACKENDS=mock
export CV_ALLOWED_REPO_ROOTS=/tmp
.venv/bin/uvicorn web.server:app --host 127.0.0.1 --port 5091
```

Dispatch and inspect a task:

```bash
curl -sS http://127.0.0.1:5091/api/dispatch \
  -H 'content-type: application/json' \
  -d '{"task":"Run the backend smoke test","backend":"mock","repo_path":"/tmp/cv-demo"}'

curl -sS http://127.0.0.1:5091/api/task/<task_id>
curl -sS -X POST http://127.0.0.1:5091/api/task/<task_id>/cancel
```

Real subprocess backends remain opt-in. If `claude-code` or `openopc` is added
to `CV_ALLOWED_BACKENDS`, the server requires `CV_API_TOKEN` and restricts
`repo_path` to `CV_ALLOWED_REPO_ROOTS`.

---

# Upstream Coding Vibe context

Voice-first AI coding companion — talk to your codebase while you're on the go.

**AdventureX 2026** | Harness-agnostic | Two-tier CS+CEO architecture

## What it is

Coding Vibe lets you call a phone number and describe a coding task in natural language. A fast, conversational CS model takes your request and delegates it to a powerful CEO reasoning model that implements the work. You get a callback when it's done.

The same Claude Code harness plays both roles — a state-aware hook toggles between CS protocol and CEO protocol automatically based on delegation file state.

## Architecture — The Closed Loop

```
Turn 1 (CS Protocol)          Turn 2 (CEO Protocol)         Turn 3 (CS Protocol)
┌──────────────────┐         ┌──────────────────┐         ┌──────────────────┐
│ User: "Add a     │         │ Hook detects     │         │ User: "How'd it  │
│ /health endpoint"│ ──────▶ │ pending task     │ ──────▶ │ go?"             │
│                  │         │                  │         │                  │
│ CS gathers reqs  │         │ CEO claims task  │         │ CS reads session │
│ → checkpoint     │         │ → implements     │         │ → delivers       │
│ → delegate_to_ceo│         │ → complete_deleg │         │   results        │
└──────────────────┘         └──────────────────┘         └──────────────────┘
         │                            │                           │
  delegation_add-health.json   task marked complete       checkpoints + summary
  written to ~/.coding-vibe/   auto-creates checkpoint     relayed to user
```

**The hook is the switch.** No separate process, no polling, no manual handoff. The hook reads `~/.coding-vibe/` on each turn and injects the correct protocol into the harness's system prompt.

**Principle:** The CS model NEVER writes code. The CEO NEVER talks to the user.

## Quick Start

### 1. Set up the MCP server

```bash
cd ~/Projects/coding-vibe
python3 -m venv .venv && source .venv/bin/activate
pip install mcp
```

Test it (should list 5 tools):

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | python3 cv_mcp/server.py 2>/dev/null
```

### 2. Register in your harness

**Claude Code** — add to `~/.claude/settings.json` under `mcpServers`:

```json
"coding-vibe": {
  "command": "python3",
  "args": ["/Users/<you>/Projects/coding-vibe/cv_mcp/server.py"],
  "env": {"CODING_VIBE_STATE_DIR": "/Users/<you>/.coding-vibe"}
}
```

**Cursor** — add to `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "coding-vibe": {
      "command": "python3",
      "args": ["/path/to/coding-vibe/cv_mcp/server.py"]
    }
  }
}
```

### 3. Set up the hook

Copy `~/.claude/hooks/coding-vibe-inject.py` to your hooks directory. The hook auto-detects delegation state and injects the correct protocol:

- **No pending delegations** → CS protocol (gather requirements)
- **Pending delegation exists** → CEO protocol (pick up and implement)
- **Last checkpoint is "task-complete"** → CS protocol (deliver results)

### 4. Run the demo

```bash
cd ~/Projects/coding-vibe
source .venv/bin/activate
python3 demo.py --scripted
```

This shows the full 3-turn loop: CS gathers → CEO implements → CS delivers.

## MCP Tools (5 total)

| Tool | Role | Purpose |
|------|------|---------|
| `coding_vibe_checkpoint` | Both | Report progress milestone (`milestone`, `message`, `progress_pct`) |
| `coding_vibe_delegate_to_ceo` | CS only | Write delegation file, hand off to CEO |
| `coding_vibe_session_state` | Both | Read full session state + pending delegations |
| `coding_vibe_claim_delegation` | CEO only | Claim a pending task (`"auto"` = oldest pending) |
| `coding_vibe_complete_delegation` | CEO only | Mark task done, auto-creates task-complete checkpoint |

### Tool details

#### `coding_vibe_checkpoint`
Call at EVERY important milestone. Keeps the user informed.
- `milestone` — short name: `"requirements-gathered"`, `"delegating-to-ceo"`, `"task-complete"`, `"blocked"`
- `message` — human-readable summary
- `progress_pct` — 0-100

#### `coding_vibe_delegate_to_ceo`
CS calls this when the user's request is fully understood. Writes a delegation file to `~/.coding-vibe/delegation_<task_id>.json`.
- `task_id` — unique ID, e.g. `"add-health-endpoint"`
- `description` — complete requirements with context
- `repo_path` — absolute path to the project
- `priority` — `"low"` / `"normal"` / `"high"` / `"urgent"`
- `files_to_modify` — optional list of target files

#### `coding_vibe_claim_delegation`
CEO calls this first. Use `task_id="auto"` to claim the oldest pending task. Returns full task details: description, repo_path, files_to_modify.

#### `coding_vibe_complete_delegation`
CEO calls this when done. Auto-creates a `"task-complete"` checkpoint so CS can find results on the next turn.
- `task_id` — the task being completed
- `summary` — what was done (CS reads this verbatim to the user)
- `files_changed` — list of modified/created files

#### `coding_vibe_session_state`
Returns full session: all checkpoints, all delegations with status, and pending files on disk.

## Voice Bridge

`voice_bridge.py` connects voice providers to Coding Vibe's audio channel. Four modes:

| Mode | What it does | Status |
|------|-------------|--------|
| `halfduplex` | stdin text → macOS `say` output | Works (no API key needed) |
| `livekit` | Deepgram STT → DeepSeek LLM → Deepgram TTS | Requires API keys |
| `stepaudio` | StepFun Realtime API (WebSocket) | Stub (needs implementation) |
| `seeduplex` | ByteDance Seeduplex (WebRTC) | Stub (needs implementation) |

```bash
# Text mode (always works)
uv run python3 voice_bridge.py --mode halfduplex

# LiveKit voice mode
uv run python3 voice_bridge.py --mode livekit
```

The LiveKit mode uses `CodingVibeAgent` (in `agent.py`) — a LiveKit Agent that receives voice input via Deepgram STT, runs a DeepSeek Chat LLM as the Boss, and delegates coding tasks to OpenOPC's Architect→Builder→Reviewer chain via the `delegate_coding` function tool.

### LiveKit setup

Copy `.env.example` to `.env` and fill in:

```bash
DEEPSEEK_API_KEY=sk-...
DEEPGRAM_API_KEY=...
LIVEKIT_URL=ws://localhost:7880
LIVEKIT_API_KEY=devkey
LIVEKIT_API_SECRET=secret
```

## Project Structure

```
coding-vibe/
├── cv_mcp/
│   └── server.py          # MCP server (5 tools, stdio transport)
├── voice_bridge.py        # Voice bridge (halfduplex/livekit/stepaudio/seeduplex)
├── agent.py               # LiveKit CodingVibeAgent (Boss + delegate_coding tool)
├── demo.py                # Full-loop demo (--scripted, --interactive, --task)
├── requirements.txt        # Python deps (livekit-agents, python-dotenv)
├── .env.example            # Environment template
├── docs/
│   └── success-narrative-vs-mechanism.html  # Pitch deck / narrative doc
└── ~/.claude/
    ├── hooks/coding-vibe-inject.py  # State-aware hook (CS ↔ CEO protocol)
    └── skills/coding-vibe/SKILL.md  # Skill documentation
```

## How it works with OpenOPC

Coding Vibe integrates with [OpenOPC](https://github.com/onezion12344/OpenOPC) — an "AI-native company" framework. The CEO model can optionally delegate work through OpenOPC's Architect→Builder→Reviewer chain:

```
User voice → CS Model → CEO Model → OpenOPC chain
                                      ├── Architect (plans approach)
                                      ├── Builder (writes code)
                                      └── Reviewer (checks quality)
```

The `CodingVibeAgent` in `agent.py` uses `opc exec --mode org --org coding-vibe` to run this chain. The MCP server works standalone — OpenOPC is optional.

## Demo Modes

```bash
# Full scripted demo (non-interactive)
python3 demo.py --scripted

# Interactive demo (enter prompts at each step)
python3 demo.py

# Single task
python3 demo.py --task "Add rate limiting to the API"
```

## The Checkpoint Flow

| Milestone | Who sets it | Meaning |
|-----------|------------|---------|
| `requirements-gathered` | CS | User's request is fully understood |
| `delegating-to-ceo` | CS | Task handed off to CEO |
| (CEO claims + works) | CEO | claim → implement → complete |
| `task-complete` | CEO (auto) | Work done, ready for delivery |
| `progress-update` | CS | User asked for status |
| `blocked` | Either | Needs user input |

## License

MIT

## Built at AdventureX 2026

Harry Huang · [@onezion12344](https://github.com/onezion12344)
