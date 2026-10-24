$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
try {
    $pocketRoot = Split-Path -Parent $PSScriptRoot
    . (Join-Path $PSScriptRoot 'find-codex-node.ps1')
    $pocketNode = Find-CodexNode
    if (-not $env:POCKET_STATE_DIR) { $env:POCKET_STATE_DIR = Join-Path $env:LOCALAPPDATA 'Codex Pocket' }
    if (-not $env:POCKET_MANAGER_PORT) { $env:POCKET_MANAGER_PORT = '43787' }
    if (-not (Test-Path -LiteralPath $env:POCKET_STATE_DIR -PathType Container)) {
        New-Item -ItemType Directory -Path $env:POCKET_STATE_DIR -Force | Out-Null
    }
    # Inherited ACLs normally limit LocalAppData to this Windows account. Set an
    # explicit private ACL before writing credentials, rather than relying on
    # POSIX file modes (which do not protect files on Windows).
    $pocketAcl = New-Object Security.AccessControl.DirectorySecurity
    $pocketAcl.SetAccessRuleProtection($true, $false)
    $pocketUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($sid in @($pocketUser, (New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
        $pocketRule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $pocketAcl.AddAccessRule($pocketRule)
    }
    Set-Acl -LiteralPath $env:POCKET_STATE_DIR -AclObject $pocketAcl
    Write-Host 'Opening Codex Pocket. Keep this window open while using the connector.'
    Push-Location -LiteralPath $pocketRoot
    try {
        & $pocketNode (Join-Path $pocketRoot 'connector.mjs') 2>> (Join-Path $env:POCKET_STATE_DIR 'connector.log')
        $pocketExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($pocketExitCode -ne 0) { throw 'Connector failed. Check %LOCALAPPDATA%\Codex Pocket\connector.log or contact your administrator.' }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}
