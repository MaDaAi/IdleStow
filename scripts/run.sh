#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="${1:-$ROOT/target/dev-data}"
[ -f "$ROOT/app/target/idlestow.jar" ] || "$ROOT/scripts/build.sh"
exec java "-Didlestow.python.dir=$ROOT/python" -jar "$ROOT/app/target/idlestow.jar" --data-dir "$DATA"
