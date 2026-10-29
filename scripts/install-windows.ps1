$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
try {
    $pocketSource = Join-Path (Split-Path -Parent $PSScriptRoot) 'app'
    if (-not (Test-Path -LiteralPath (Join-Path $pocketSource 'connector.mjs') -PathType Leaf)) { throw 'Extract the whole ZIP before starting the installer.' }
    $pocketManifest = Get-Content -LiteralPath (Join-Path $pocketSource 'package.json') -Raw | ConvertFrom-Json
    $pocketVersion = $pocketManifest.pocketVersion
    if (-not $pocketVersion) { $pocketVersion = $pocketManifest.version }
    if ($pocketVersion -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(\.(0|[1-9]\d*))?$') { throw 'Invalid connector package version.' }
    $pocketBase = Join-Path $env:LOCALAPPDATA 'Programs\Codex Pocket Connector'
    $pocketInstall = Join-Path $pocketBase $pocketVersion
    if (Test-Path -LiteralPath $pocketInstall) { throw 'This version is already installed. Open Codex Pocket Connector from the Start menu, or use the extracted portable folder.' }
    New-Item -ItemType Directory -Path $pocketInstall | Out-Null
    foreach ($member in Get-ChildItem -LiteralPath $pocketSource -Force) { Copy-Item -LiteralPath $member.FullName -Destination $pocketInstall -Recurse -Force }
    $pocketShell = New-Object -ComObject WScript.Shell
    $pocketMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Codex Pocket Connector.lnk'
    $pocketShortcut = $pocketShell.CreateShortcut($pocketMenu)
    $pocketShortcut.TargetPath = Join-Path $pocketInstall 'Codex-Pocket.exe'
    $pocketShortcut.WorkingDirectory = $pocketInstall
    $pocketShortcut.IconLocation = Join-Path $pocketInstall 'Pocket.ico'
    $pocketShortcut.Description = 'Connect your phone to your own Codex desktop'
    $pocketShortcut.Save()
    Write-Host ('Installed for this Windows account: ' + $pocketInstall)
    Write-Host 'Open Codex Pocket Connector from the Start menu. No administrator permission was required.'
    Start-Process -FilePath (Join-Path $pocketInstall 'Codex-Pocket.exe')
} catch { Write-Host $_.Exception.Message -ForegroundColor Red; Read-Host 'Press Enter to close'; exit 1 }
