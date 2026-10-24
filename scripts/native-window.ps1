$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms
$pocketRoot = Split-Path -Parent $PSScriptRoot
$pocketBackend = $null
$pocketMutex = $null
try {
    . (Join-Path $PSScriptRoot 'find-codex-node.ps1')
    if (-not $env:POCKET_STATE_DIR) { $env:POCKET_STATE_DIR = Join-Path $env:LOCALAPPDATA 'Codex Pocket' }
    [IO.Directory]::CreateDirectory($env:POCKET_STATE_DIR) | Out-Null
    # POSIX modes do not restrict Windows files. Keep saved account and pairing
    # credentials private to the current user and SYSTEM before starting Node.
    $pocketAcl = New-Object Security.AccessControl.DirectorySecurity
    $pocketAcl.SetAccessRuleProtection($true, $false)
    $pocketUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($sid in @($pocketUser, (New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
        $pocketRule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $pocketAcl.AddAccessRule($pocketRule)
    }
    Set-Acl -LiteralPath $env:POCKET_STATE_DIR -AclObject $pocketAcl
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
    $pocketSdk = Join-Path $pocketRoot 'native\webview2'
    $pocketLoader = Join-Path $pocketSdk $(if ([Environment]::Is64BitProcess) {'win-x64'} else {'win-x86'})
    $env:PATH = $pocketLoader + ';' + $env:PATH
    [Reflection.Assembly]::LoadFrom((Join-Path $pocketSdk 'Microsoft.Web.WebView2.Core.dll')) | Out-Null
    [Reflection.Assembly]::LoadFrom((Join-Path $pocketSdk 'Microsoft.Web.WebView2.Wpf.dll')) | Out-Null
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
    $pocketBackend.Start() | Out-Null
    $pocketOutput = $pocketBackend.StandardOutput.ReadToEndAsync()
    $pocketError = $pocketBackend.StandardError.ReadToEndAsync()
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
    $script:pocketLoaded = $false
    $script:pocketStartedAt = [DateTime]::UtcNow
    $pocketTimer = New-Object Windows.Threading.DispatcherTimer
    $pocketTimer.Interval = [TimeSpan]::FromMilliseconds(300)
    $pocketTimer.Add_Tick({
        if ($script:pocketLoaded) { return }
        if ($pocketBackend.HasExited) {
            $pocketTimer.Stop()
            [Windows.MessageBox]::Show('连接服务启动失败。请确认 Codex 已安装并登录，再重新打开软件。','Codex Pocket') | Out-Null
            $pocketWindow.Close(); return
        }
        if (Test-Path -LiteralPath $pocketDescriptor -PathType Leaf) {
            $result = [IO.File]::ReadAllText($pocketDescriptor) | ConvertFrom-Json
            $target = [Uri]$result.url
            if ($target.Host -ne '127.0.0.1' -or $target.Scheme -ne 'http') { throw 'Invalid local connector URL' }
            $script:pocketOrigin = $target.GetLeftPart([UriPartial]::Authority)
            $script:pocketToken = $target.Fragment.Substring(1)
            $pocketWeb.Source = $target
            $script:pocketLoaded = $true
            $pocketTimer.Stop()
            Remove-Item -LiteralPath $pocketDescriptor
        } elseif (([DateTime]::UtcNow - $script:pocketStartedAt).TotalSeconds -gt 20) {
            $pocketTimer.Stop()
            [Windows.MessageBox]::Show('启动超时，请确认本机 Codex 已正确安装。','Codex Pocket') | Out-Null
            $pocketWindow.Close()
        }
    })
    $pocketWeb.Add_CoreWebView2InitializationCompleted({
        param($sender,$eventArgs)
        if (-not $eventArgs.IsSuccess) {
            [Windows.MessageBox]::Show('无法启动应用窗口。请安装 Microsoft Edge WebView2 Runtime 后重试。','Codex Pocket') | Out-Null
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
    [Windows.MessageBox]::Show(('无法启动 Codex Pocket：' + $_.Exception.Message + "`n请先安装并登录 Codex；窗口需要 Microsoft Edge WebView2 Runtime。"),'Codex Pocket') | Out-Null
} finally {
    if ($pocketBackend -and -not $pocketBackend.HasExited) {
        # Let Node close its TLS listener, WebSocket, databases and Codex child.
        # The EXE's kill-on-close Job Object covers forced termination as well.
        try {
            if ($script:pocketLoaded) { Invoke-WebRequest -UseBasicParsing -Uri ($script:pocketOrigin + '/api/shutdown') -Method Post -Body '{}' -ContentType 'application/json' -Headers @{Authorization = ('Bearer ' + $script:pocketToken)} -TimeoutSec 2 | Out-Null }
        } catch { }
        if (-not $pocketBackend.WaitForExit(3000)) { $pocketBackend.Kill(); $pocketBackend.WaitForExit(2000) | Out-Null }
    }
    if ($pocketMutex) { $pocketMutex.Dispose() }
}
