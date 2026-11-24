#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: generate-fixtures.sh <empty-dir> [mixed|benchmark] [files]}"
PYTHONPATH="$ROOT/python" exec python3 -m idlestow.fixtures --out "$OUT" --preset "${2:-mixed}" --files "${3:-1000}"
