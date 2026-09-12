#!/usr/bin/env bash
# Compatibility entrypoint; each invocation gets a fresh, isolated world.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/dev-server.py bench --seconds "${1:-30}"
