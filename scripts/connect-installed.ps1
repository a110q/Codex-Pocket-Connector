param([ValidateSet('start', 'stop', 'status', 'manager')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
try {
    $pocketRoot = Split-Path -Parent $PSScriptRoot
    . (Join-Path $PSScriptRoot 'find-codex-node.ps1')
    $pocketNode = Find-CodexNode
    if (-not $env:POCKET_STATE_DIR) { $env:POCKET_STATE_DIR = Join-Path $env:LOCALAPPDATA 'Codex Pocket' }
    $env:POCKET_CONFIG = Join-Path $env:POCKET_STATE_DIR 'companion.json'
    if (-not $env:POCKET_MANAGER_PORT) { $env:POCKET_MANAGER_PORT = '43787' }
    if ($Action -eq 'start' -and -not $env:CODEX_APP_TOOLS_PIPE_PATH) {
        throw 'Open the official Codex app and run this command in its built-in PowerShell terminal. The desktop tools connection is not available from a normal Windows or WSL terminal.'
    }
    Push-Location -LiteralPath $pocketRoot
    try { & $pocketNode (Join-Path $PSScriptRoot 'desktop-service.mjs') $Action --open-manager; $pocketExitCode = $LASTEXITCODE }
    finally { Pop-Location }
    exit $pocketExitCode
} catch { Write-Error $_.Exception.Message; exit 1 }
