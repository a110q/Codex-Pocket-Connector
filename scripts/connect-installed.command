#!/bin/zsh
set -euo pipefail
POCKET_ROOT="${0:A:h:h}"
source "$POCKET_ROOT/scripts/find-codex-node.sh"
export POCKET_STATE_DIR="${POCKET_STATE_DIR:-$HOME/Library/Application Support/Codex Pocket}"
export POCKET_CONFIG="$POCKET_STATE_DIR/companion.json"
export POCKET_MANAGER_PORT="${POCKET_MANAGER_PORT:-43787}"
cd "$POCKET_ROOT"
exec "$POCKET_NODE" "$POCKET_ROOT/scripts/desktop-service.mjs" start --open-manager
