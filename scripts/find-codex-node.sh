#!/bin/zsh
# Source this file; only try the named desktop bundles, never an arbitrary PATH node.
if [[ -n "${POCKET_NODE:-}" && -x "$POCKET_NODE" ]]; then
  return 0
fi
for POCKET_APP in "/Applications/ChatGPT.app" "/Applications/Codex.app" "$HOME/Applications/ChatGPT.app" "$HOME/Applications/Codex.app"; do
  for POCKET_RUNTIME in "$POCKET_APP/Contents/Resources/cua_node/bin/node" "$POCKET_APP/Contents/Resources/node/bin/node" "$POCKET_APP/Contents/Resources/node"; do
    if [[ -f "$POCKET_RUNTIME" && -x "$POCKET_RUNTIME" ]]; then
      POCKET_NODE="$POCKET_RUNTIME"
      return 0
    fi
  done
done
print -u2 '未找到受支持的 Codex 桌面内置运行环境。请安装桌面应用；自定义位置可用 POCKET_NODE 指定其内置 Node 路径。'
return 1
