Set-StrictMode -Version Latest

function Find-CodexNode {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    foreach ($name in @('POCKET_NODE', 'CODEX_MCP_NODE_PATH', 'CODEX_TASK_WORKSPACE_NODE')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [String]::IsNullOrWhiteSpace($value)) { $candidates.Add($value) }
    }

    $roots = New-Object 'System.Collections.Generic.List[string]'
    if ($env:POCKET_CODEX_INSTALL) {
        $selectedRoot = $env:POCKET_CODEX_INSTALL
        if (Test-Path -LiteralPath $selectedRoot -PathType Leaf) { $selectedRoot = Split-Path -Parent $selectedRoot }
        for ($i = 0; $i -lt 4; $i++) {
            if ($selectedRoot -and [IO.Path]::IsPathRooted($selectedRoot)) { $roots.Add($selectedRoot); $selectedRoot = Split-Path -Parent $selectedRoot }
        }
    }
    if ($env:LOCALAPPDATA) {
        foreach ($relative in @('Programs\Codex', 'Programs\ChatGPT', 'OpenAI\Codex')) {
            $roots.Add((Join-Path $env:LOCALAPPDATA $relative))
        }
        # Current desktop versions can relocate the Microsoft Store runtime to
        # this exact per-user subtree. Do not search arbitrary Node installs.
        $runtimeRoot = Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\runtimes\cua_node'
        if (Test-Path -LiteralPath $runtimeRoot -PathType Container) {
            $runtimes = @(Get-ChildItem -LiteralPath $runtimeRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^[0-9a-f]{16}$' } |
                Sort-Object LastWriteTimeUtc -Descending)
            foreach ($runtime in $runtimes) { $candidates.Add((Join-Path $runtime.FullName 'bin\node.exe')) }
        }
    }
    if ($env:ProgramFiles) {
        $roots.Add((Join-Path $env:ProgramFiles 'Codex'))
        $roots.Add((Join-Path $env:ProgramFiles 'ChatGPT'))
    }
    if (Get-Command Get-AppxPackage -ErrorAction SilentlyContinue) {
        foreach ($packageName in @('OpenAI.Codex', 'OpenAI.ChatGPT', 'OpenAI.ChatGPT-Desktop')) {
            foreach ($package in @(Get-AppxPackage -Name $packageName -ErrorAction SilentlyContinue)) {
                if ($package.InstallLocation) { $roots.Add($package.InstallLocation) }
            }
        }
    }
    $script:PocketDesktopRoots = @($roots | Select-Object -Unique)
    foreach ($root in $roots) {
        foreach ($relative in @('resources\cua_node\bin\node.exe', 'app\resources\cua_node\bin\node.exe', 'cua_node\bin\node.exe', 'resources\node\bin\node.exe', 'app\resources\node\bin\node.exe', 'bin\node.exe')) {
            $candidates.Add((Join-Path $root $relative))
        }
    }
    foreach ($candidate in @($candidates | Select-Object -Unique)) {
        if (-not [IO.Path]::IsPathRooted($candidate) -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        if ([IO.Path]::GetFileName($candidate) -ine 'node.exe') { continue }
        try {
            $version = & $candidate -p "process.versions.node" 2>$null
            if ($LASTEXITCODE -ne 0 -or $version -notmatch '^(\d+)\.(\d+)\.(\d+)$') { continue }
            if ([int]$Matches[1] -gt 22 -or ([int]$Matches[1] -eq 22 -and [int]$Matches[2] -ge 13)) {
                return [IO.Path]::GetFullPath($candidate)
            }
        } catch { continue }
    }
    throw 'Codex bundled Node 22.13+ was not found. Install and open the official Codex Windows app, sign in, then reopen Codex Pocket. Official download: https://chatgpt.com/codex . A custom desktop location can use POCKET_NODE to point to its bundled node.exe.'
}
