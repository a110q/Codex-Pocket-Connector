#!/bin/zsh
set -euo pipefail
POCKET_ROOT="${0:A:h:h}"
source "$POCKET_ROOT/scripts/find-codex-node.sh"
cd "$POCKET_ROOT"
exec "$POCKET_NODE" "$POCKET_ROOT/scripts/desktop-service.mjs" start --open-manager
