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

PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
PROJECT_PARENT="$(dirname "$PROJECT_ROOT")"
PROJECT_NAME="$(basename "$PROJECT_ROOT")"
TIMESTAMP="$(date '+%Y-%m-%d-%H-%M-%S')"

ARCHIVE_NAME="${PROJECT_NAME}-${TIMESTAMP}.tar.gz"
ARCHIVE_PATH="${PROJECT_PARENT}/${ARCHIVE_NAME}"

echo "📁 Project root:   $PROJECT_ROOT"
echo "📦 Archive path:   $ARCHIVE_PATH"
echo "🚫 Excluding: build/, .gradle/* (except config.properties)"
echo

cd "$PROJECT_PARENT"

TMP_TAR="${ARCHIVE_PATH%.gz}"

tar \
  --create \
  --file="$TMP_TAR" \
  \
  --exclude="${PROJECT_NAME}/build" \
  --exclude="${PROJECT_NAME}/**/build" \
  \
  --exclude="${PROJECT_NAME}/.gradle/*" \
  --exclude="${PROJECT_NAME}/.gradle/**" \
  \
  "$PROJECT_NAME"

if [[ -f "${PROJECT_NAME}/.gradle/config.properties" ]]; then
  echo "➕ Adding .gradle/config.properties"
  tar --append --file="$TMP_TAR" \
    "${PROJECT_NAME}/.gradle/config.properties"
fi

echo "🗜️ Compressing..."
gzip -9 "$TMP_TAR"

echo
echo "✅ Backup created:"
echo "$ARCHIVE_PATH"
