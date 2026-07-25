#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_python="$repo_root/backend/.venv/bin/python"
jdk17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

if [[ ! -x "$backend_python" ]]; then
    echo "Missing backend/.venv; follow README.md local setup first."
    exit 1
fi

PYTHONPYCACHEPREFIX=/tmp/earceo-pycache \
    "$backend_python" -m pytest -p no:cacheprovider -q "$repo_root/backend"

JAVA_HOME="$jdk17" \
PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    "$repo_root/android/gradlew" \
    -p "$repo_root/android" \
    :app:testDebugUnitTest \
    :app:assembleDebug \
    :app:lintDebug

"$repo_root/scripts/check-public-repo.sh"
