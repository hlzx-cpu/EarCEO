#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

forbidden_tracked_files="$(
  git ls-files |
    grep -E '(^|/)(local\.properties|secrets\.properties|.*\.aar|.*\.apk|.*\.aab)$' ||
    true
)"

if [[ -n "$forbidden_tracked_files" ]]; then
  echo "Refusing public release: tracked local/vendor files detected:"
  echo "$forbidden_tracked_files"
  exit 1
fi

if git grep -n -I -E \
  '(viaim\.appSecret[[:space:]]*=[[:space:]]*[^[:space:]]+|APP_SECRET[[:space:]]*=[[:space:]]*"[^"]+")' \
  -- ':!android/local.properties.example'
then
  echo "Refusing public release: a possible viaim secret assignment is tracked."
  exit 1
fi

echo "Public repository safety checks passed."
