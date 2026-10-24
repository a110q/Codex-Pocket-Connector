#!/bin/zsh
set -euo pipefail
POCKET_ROOT="${0:A:h:h}"
cd "$POCKET_ROOT"
exec '/Applications/ChatGPT.app/Contents/Resources/cua_node/bin/node' scripts/desktop-service.mjs stop
