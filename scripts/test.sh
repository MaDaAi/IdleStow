#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/python" && python3 -m ruff check . && python3 -m pytest -q)
cd "$ROOT" && ./mvnw -B verify "-Dsurefire.excludedGroups=${EXCLUDED_GROUPS:-gui,benchmark}"
