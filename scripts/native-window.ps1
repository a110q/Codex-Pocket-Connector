$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms
$pocketRoot = Split-Path -Parent $PSScriptRoot
$pocketBackend = $null
$pocketBackendStarted = $false
$pocketMutex = $null
$pocketDescriptor = $null
$pocketTimer = $null
$pocketVersion = 'unknown'
$pocketLog = $null
$script:pocketLoaded = $false
$script:pocketOrigin = $null
$script:pocketToken = $null

function Show-PocketStartupFailure($stage, $detail, $hint) {
    $logHint = ''
    if ($pocketLog) {
        try {
            # Keep only fixed diagnostic codes; neither the backend's raw
            # stderr nor the authenticated local URL belongs in a log.
            [IO.File]::WriteAllLines($pocketLog, [string[]]@(
                ('version=' + $pocketVersion), ('stage=' + $stage), ('detail=' + $detail)
            ))
            $logHint = "`n诊断文件：$pocketLog"
        } catch { }
    }
    [Windows.MessageBox]::Show(("$hint`n诊断编号：$stage / $detail" + $logHint), 'Codex Pocket') | Out-Null
}

function Get-PocketBackendFailure($stderr) {
    if ($stderr -cmatch '(?m)^POCKET_STARTUP_ERROR:(OPTIONS|LOCAL_CONFIG|PACKAGE|LISTENER|STATE_WRITE|DESCRIPTOR|BACKEND_IMPORT):(EACCES|EPERM|ENOENT|EEXIST|ENOTDIR|EISDIR|ENOSPC|EADDRINUSE|ERR_INVALID_URL|INVALID_JSON|ERR_MODULE_NOT_FOUND|ERR_UNKNOWN_BUILTIN_MODULE|UNKNOWN)\s*$') {
        return @{ Stage = $Matches[1]; Detail = $Matches[2] }
    }
    if ($stderr -cmatch 'ERR_(MODULE_NOT_FOUND|UNKNOWN_BUILTIN_MODULE)|MODULE_NOT_FOUND') {
        return @{ Stage = 'BACKEND_IMPORT'; Detail = $Matches[0] }
    }
    return @{ Stage = 'BACKEND_EXIT'; Detail = 'NO_STARTUP_CODE' }
}

$pocketStage = 'STARTUP_SCRIPTS'
try {
    . (Join-Path $PSScriptRoot 'find-codex-node.ps1')
    . (Join-Path $PSScriptRoot 'windows-state-directory.ps1')
    $env:POCKET_STATE_DIR = Initialize-PocketStateDirectory $env:POCKET_STATE_DIR ([ref]$pocketStage)
    $pocketStage = 'VERSION'
    $pocketLog = Join-Path $env:POCKET_STATE_DIR 'startup-diagnostic.txt'
    $pocketVersion = (Get-Content -LiteralPath (Join-Path $pocketRoot 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json).pocketVersion
    if ($pocketVersion -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') { $pocketVersion = 'unknown' }
    $pocketStage = 'NODE_RUNTIME'
    # Recover custom Codex locations before starting the embedded window.
    $pocketInstallation = Join-Path $env:POCKET_STATE_DIR 'codex-installation.json'
    if (Test-Path -LiteralPath $pocketInstallation -PathType Leaf) {
        try { $pocketSelected = (Get-Content -LiteralPath $pocketInstallation -Raw -Encoding UTF8 | ConvertFrom-Json).selectedPath; $env:POCKET_CODEX_INSTALL = $pocketSelected } catch { }
    }
    try { $pocketNode = Find-CodexNode } catch {
        $pocketFolder = New-Object Windows.Forms.FolderBrowserDialog
        $pocketFolder.Description = '未找到 Codex 运行环境，请选择 Codex 安装目录。'
        $pocketFolder.ShowNewFolderButton = $false
        if ($pocketFolder.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { return }
        $env:POCKET_CODEX_INSTALL = $pocketFolder.SelectedPath
        $pocketNode = Find-CodexNode
        $pocketSavedInstallation = @{ selectedPath = $pocketFolder.SelectedPath } | ConvertTo-Json
        [IO.File]::WriteAllText($pocketInstallation, $pocketSavedInstallation, (New-Object Text.UTF8Encoding($false)))
    }
    $pocketCreated = $false
    $pocketMutex = New-Object Threading.Mutex($true, 'Local\CodexPocket-Native-Window', [ref]$pocketCreated)
    if (-not $pocketCreated) { [Windows.MessageBox]::Show('Codex Pocket 已经打开，请切回已有窗口。','Codex Pocket') | Out-Null; return }
    $pocketStage = 'WEBVIEW_FILES'
    $pocketSdk = Join-Path $pocketRoot 'native\webview2'
    $pocketLoader = Join-Path $pocketSdk $(if ([Environment]::Is64BitProcess) {'win-x64'} else {'win-x86'})
    $env:PATH = $pocketLoader + ';' + $env:PATH
    # Explorer can propagate the downloaded ZIP's Internet mark to its DLLs.
    # Only unblock this app's bundled WebView2 libraries, before LoadFrom.
    foreach ($pocketLibrary in @((Join-Path $pocketSdk 'Microsoft.Web.WebView2.Core.dll'), (Join-Path $pocketSdk 'Microsoft.Web.WebView2.Wpf.dll'), (Join-Path $pocketLoader 'WebView2Loader.dll'))) {
        try { Unblock-File -LiteralPath $pocketLibrary -ErrorAction Stop }
        catch { throw '无法解除应用组件的下载阻止标记。请将完整安装包解压到当前用户可写的目录后重试，或使用单文件 EXE。' }
    }
    [Reflection.Assembly]::LoadFrom((Join-Path $pocketSdk 'Microsoft.Web.WebView2.Core.dll')) | Out-Null
    [Reflection.Assembly]::LoadFrom((Join-Path $pocketSdk 'Microsoft.Web.WebView2.Wpf.dll')) | Out-Null
    $pocketStage = 'BACKEND_SPAWN'
    $pocketDescriptor = Join-Path $env:POCKET_STATE_DIR ('native-' + [Guid]::NewGuid().ToString('N') + '.json')
    $pocketStart = New-Object Diagnostics.ProcessStartInfo
    $pocketStart.FileName = $pocketNode
    $pocketStart.Arguments = '"' + (Join-Path $pocketRoot 'connector.mjs') + '" --native --descriptor "' + $pocketDescriptor + '"'
    $pocketStart.WorkingDirectory = $pocketRoot
    $pocketStart.UseShellExecute = $false
    $pocketStart.CreateNoWindow = $true
    $pocketStart.RedirectStandardOutput = $true
    $pocketStart.RedirectStandardError = $true
    foreach ($key in @('CODEX_THREAD_ID','CODEX_DESKTOP_CONTEXT_THREAD_ID','CODEX_APP_TOOLS_PIPE_PATH')) { $pocketStart.EnvironmentVariables.Remove($key) }
    $pocketStart.EnvironmentVariables['POCKET_CODEX_ROOTS'] = ConvertTo-Json -InputObject @($script:PocketDesktopRoots) -Compress
    $pocketBackend = New-Object Diagnostics.Process
    $pocketBackend.StartInfo = $pocketStart
    $pocketBackendStarted = $pocketBackend.Start()
    $pocketOutput = $pocketBackend.StandardOutput.ReadToEndAsync()
    $pocketError = $pocketBackend.StandardError.ReadToEndAsync()
    $pocketStage = 'WEBVIEW_WINDOW'
    $pocketWindow = New-Object Windows.Window
    $pocketWindow.Title = 'Codex Pocket'
    $pocketWindow.Width = 1100; $pocketWindow.Height = 820
    $pocketWindow.MinWidth = 740; $pocketWindow.MinHeight = 640
    $pocketWindow.WindowStartupLocation = 'CenterScreen'
    $pocketWeb = New-Object Microsoft.Web.WebView2.Wpf.WebView2
    $pocketCreation = New-Object Microsoft.Web.WebView2.Wpf.CoreWebView2CreationProperties
    $pocketCreation.UserDataFolder = Join-Path $env:POCKET_STATE_DIR 'WebView2'
    $pocketWeb.CreationProperties = $pocketCreation
    $pocketWindow.Content = $pocketWeb
    $script:pocketStartedAt = [DateTime]::UtcNow
    $pocketTimer = New-Object Windows.Threading.DispatcherTimer
    $pocketTimer.Interval = [TimeSpan]::FromMilliseconds(300)
    $pocketTimer.Add_Tick({
        if ($script:pocketLoaded) { return }
        try {
            if ($pocketBackend.HasExited) {
                $pocketTimer.Stop()
                $stderr = ''
                if ($pocketError.Wait(250)) { $stderr = $pocketError.Result }
                $failure = Get-PocketBackendFailure $stderr
                $hint = switch ($failure.Stage) {
                    'OPTIONS' { '连接器的启动参数无效。' }
                    'LOCAL_CONFIG' { 'Pocket 无法读取本地配置。请保留现有配置，先发送诊断编号。' }
                    'PACKAGE' { 'Pocket 无法读取程序文件。若使用 ZIP，请完整解压后再运行。' }
                    'BACKEND_IMPORT' { 'Pocket 缺少必要的程序模块，或当前 Node 环境不兼容。' }
                    'LISTENER' { 'Pocket 无法启动本机连接服务。' }
                    'STATE_WRITE' { 'Pocket 无法写入本地配置，请检查磁盘空间和目录权限。' }
                    'DESCRIPTOR' { 'Pocket 无法写入启动状态，请检查磁盘空间和目录权限。' }
                    default { 'Pocket 连接服务在窗口就绪前退出。' }
                }
                Show-PocketStartupFailure $failure.Stage ($failure.Detail + '; exit=' + $pocketBackend.ExitCode) $hint
                $pocketWindow.Close(); return
            }
            if (Test-Path -LiteralPath $pocketDescriptor -PathType Leaf) {
                $result = [IO.File]::ReadAllText($pocketDescriptor) | ConvertFrom-Json
                $target = [Uri]$result.url
                if ($result.event -ne 'ready' -or $target.Host -ne '127.0.0.1' -or $target.Scheme -ne 'http' -or $target.Fragment.Length -le 1) { throw 'Invalid local connector URL' }
                $script:pocketOrigin = $target.GetLeftPart([UriPartial]::Authority)
                $script:pocketToken = $target.Fragment.Substring(1)
                $pocketWeb.Source = $target
                $script:pocketLoaded = $true
                $pocketTimer.Stop()
                Remove-Item -LiteralPath $pocketDescriptor -ErrorAction SilentlyContinue
            } elseif (([DateTime]::UtcNow - $script:pocketStartedAt).TotalSeconds -gt 20) {
                $pocketTimer.Stop()
                Show-PocketStartupFailure 'BACKEND_TIMEOUT' '20_SECONDS' 'Pocket 本机连接服务启动超时。'
                $pocketWindow.Close()
            }
        } catch {
            $pocketTimer.Stop()
            Show-PocketStartupFailure 'WINDOW_HANDSHAKE' 'INVALID_OR_UNREADABLE' 'Pocket 无法打开本机连接页面。'
            $pocketWindow.Close()
        }
    })
    $pocketWeb.Add_CoreWebView2InitializationCompleted({
        param($sender,$eventArgs)
        if (-not $eventArgs.IsSuccess) {
            $pocketTimer.Stop()
            Show-PocketStartupFailure 'WEBVIEW_INIT' 'FAILED' '无法初始化应用窗口，请确认 Microsoft Edge WebView2 Runtime 可用。'
            $pocketWindow.Close(); return
        }
        $pocketWeb.CoreWebView2.Settings.AreDevToolsEnabled = $false
        $pocketWeb.CoreWebView2.Settings.AreDefaultContextMenusEnabled = $false
        $pocketWeb.CoreWebView2.Add_NavigationStarting({ param($sender,$eventArgs)
            $uri = [Uri]$eventArgs.Uri
            if ($uri.Scheme -eq 'http' -and $uri.GetLeftPart([UriPartial]::Authority) -eq $script:pocketOrigin) { return }
            $eventArgs.Cancel = $true
        })
        $pocketWeb.CoreWebView2.Add_NewWindowRequested({ param($sender,$eventArgs) $eventArgs.Handled = $true })
    })
    $pocketTimer.Start()
    $pocketWindow.ShowDialog() | Out-Null
    $pocketTimer.Stop()
} catch {
    $hint = switch ($pocketStage) {
        'NODE_RUNTIME' { '未找到可运行 Pocket 的 Node 环境。需要 Node 22.13 或更高版本，并支持 node:sqlite。' }
        { $_ -like 'STATE_*' } { Get-PocketStateFailureHint $pocketStage }
        'WEBVIEW_FILES' { '无法加载应用窗口组件。若使用 ZIP，请完整解压到当前用户可写的目录。' }
        'BACKEND_SPAWN' { 'Windows 无法启动 Pocket 的本机连接服务。' }
        default { '无法启动 Codex Pocket，请发送诊断编号。' }
    }
    $detail = if ($pocketStage -like 'STATE_*') { Get-PocketStartupHResult $_ } else { '0x' + $_.Exception.HResult.ToString('X8') }
    Show-PocketStartupFailure $pocketStage $detail $hint
} finally {
    if ($pocketTimer) { $pocketTimer.Stop() }
    if ($pocketBackend -and $pocketBackendStarted -and -not $pocketBackend.HasExited) {
        # Let Node close its TLS listener, WebSocket, databases and Codex child.
        # The EXE's kill-on-close Job Object covers forced termination as well.
        try {
            if ($script:pocketLoaded) { Invoke-WebRequest -UseBasicParsing -Uri ($script:pocketOrigin + '/api/shutdown') -Method Post -Body '{}' -ContentType 'application/json' -Headers @{Authorization = ('Bearer ' + $script:pocketToken)} -TimeoutSec 2 | Out-Null }
        } catch { }
        if (-not $pocketBackend.WaitForExit(3000)) { $pocketBackend.Kill(); $pocketBackend.WaitForExit(2000) | Out-Null }
    }
    if ($pocketDescriptor) { Remove-Item -LiteralPath $pocketDescriptor -ErrorAction SilentlyContinue }
    if ($pocketMutex) { $pocketMutex.Dispose() }
}
