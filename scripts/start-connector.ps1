$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pocketStage = 'STARTUP_SCRIPTS'
try {
    $pocketRoot = Split-Path -Parent $PSScriptRoot
    . (Join-Path $PSScriptRoot 'find-codex-node.ps1')
    . (Join-Path $PSScriptRoot 'windows-state-directory.ps1')
    $env:POCKET_STATE_DIR = Initialize-PocketStateDirectory $env:POCKET_STATE_DIR ([ref]$pocketStage)
    $pocketStage = 'NODE_RUNTIME'
    $pocketNode = Find-CodexNode
    if (-not $env:POCKET_MANAGER_PORT) { $env:POCKET_MANAGER_PORT = '43787' }
    Write-Host '正在打开 Codex Pocket。使用连接器期间，请保持此窗口打开。'
    Push-Location -LiteralPath $pocketRoot
    try {
        & $pocketNode (Join-Path $pocketRoot 'connector.mjs') 2>> (Join-Path $env:POCKET_STATE_DIR 'connector.log')
        $pocketExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($pocketExitCode -ne 0) { throw '连接器启动失败。请查看本地数据目录中的 connector.log，或联系管理员。' }
} catch {
    if ($pocketStage -like 'STATE_*') {
        Write-Host ("$(Get-PocketStateFailureHint $pocketStage)`n诊断编号：$pocketStage / $(Get-PocketStartupHResult $_)") -ForegroundColor Red
    } else { Write-Host $_.Exception.Message -ForegroundColor Red }
    Read-Host '按回车键关闭'
    exit 1
}
