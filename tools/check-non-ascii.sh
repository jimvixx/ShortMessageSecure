#!/usr/bin/env bash

set -euo pipefail

find_project_root() {
  if git rev-parse --show-toplevel >/dev/null 2>&1; then
    git rev-parse --show-toplevel
    return 0
  fi

  local dir
  dir="$(pwd)"

  while [[ "$dir" != "/" ]]; do
    if [[ -e "$dir/.git" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
    dir="$(dirname "$dir")"
  done

  return 1
}

PROJECT_ROOT="$(find_project_root || true)"

if [[ -z "$PROJECT_ROOT" ]]; then
  echo "❌ Could not find project root"
  exit 1
fi

cd "$PROJECT_ROOT"

echo "🔍 Checking Java files for non-ASCII characters..."
echo "📁 Project root: $PROJECT_ROOT"
echo

if grep -rPn --color=always "[^\x00-\x7F]" --include="*.java" .; then
  echo
  echo "❌ Found non-ASCII characters in Java files."
  exit 1
else
  echo "✅ All Java files are clean (ASCII only)"
fi
