#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_root="$repo_root/backend"

cd "$backend_root"

if [[ ! -x ".venv/bin/uvicorn" ]]; then
    echo "Backend environment missing. Run:"
    echo "  uv venv backend/.venv"
    echo "  uv pip install --python backend/.venv/bin/python \\"
    echo "    -r backend/requirements.txt -r backend/requirements-dev.txt"
    exit 1
fi

export PYTHONPATH="$backend_root${PYTHONPATH:+:$PYTHONPATH}"
exec ".venv/bin/uvicorn" web.server:app \
    --host "${EARCEO_HOST:-0.0.0.0}" \
    --port "${EARCEO_PORT:-8787}"
