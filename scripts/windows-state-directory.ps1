function New-PocketStateAcl($user, $system) {
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($user, $system)) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    return $acl
}

function Test-PocketStateAcl($acl, $user, $system) {
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier])
    if ($owner -ne $user -and $owner -ne $system) { return $false }
    $raw = New-Object Security.AccessControl.RawSecurityDescriptor($acl.GetSecurityDescriptorBinaryForm(), 0)
    # A null DACL grants access to everyone. An inheritable allow is needed so
    # that brand-new files also receive a private DACL instead of a default one.
    if ($null -eq $raw.DiscretionaryAcl) { return $false }
    $inheritableUserRule = $false
    $inherit = [Security.AccessControl.AceFlags]'ContainerInherit,ObjectInherit'
    foreach ($ace in $raw.DiscretionaryAcl) {
        switch ($ace.AceType) {
            'AccessDenied' { continue }
            'AccessAllowed' {
                if ($ace.SecurityIdentifier -ne $user -and $ace.SecurityIdentifier -ne $system) { return $false }
                if ($ace.SecurityIdentifier -eq $user -and $ace.AceFlags.HasFlag($inherit) -and
                    -not $ace.AceFlags.HasFlag([Security.AccessControl.AceFlags]::NoPropagateInherit)) {
                    $inheritableUserRule = $true
                }
            }
            default { return $false }
        }
    }
    return $inheritableUserRule
}

function Initialize-PocketStateDirectory([string]$path, [ref]$stage) {
    $stage.Value = 'STATE_PATH'
    if (-not $path) { $path = Join-Path $env:LOCALAPPDATA 'Codex Pocket' }
    $path = [IO.Path]::GetFullPath($path)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try { $user = $identity.User } finally { $identity.Dispose() }
    $system = New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'

    if (-not [IO.Directory]::Exists($path)) {
        $stage.Value = 'STATE_CREATE'
        # Create the directory with its private DACL from the start.
        [IO.Directory]::CreateDirectory($path, (New-PocketStateAcl $user $system)) | Out-Null
    }
    $stage.Value = 'STATE_PATH'
    if ([IO.File]::GetAttributes($path).HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'Pocket 不支持将本地数据目录设为链接。'
    }

    $stage.Value = 'STATE_ACL_READ'
    $sections = [Security.AccessControl.AccessControlSections]'Access,Owner'
    $acl = [IO.Directory]::GetAccessControl($path, $sections)
    if (-not (Test-PocketStateAcl $acl $user $system)) {
        # Keep the original data and ownership. Change only the DACL and only
        # when needed, without the PowerShell provider's Set-Acl owner checks.
        $stage.Value = 'STATE_ACL_REPAIR'
        $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier])
        if ($owner -ne $user -and $owner -ne $system) { throw 'Pocket 本地数据目录由其他账户拥有。' }
        [IO.Directory]::SetAccessControl($path, (New-PocketStateAcl $user $system))
        $stage.Value = 'STATE_ACL_VERIFY'
        if (-not (Test-PocketStateAcl ([IO.Directory]::GetAccessControl($path, $sections)) $user $system)) {
            throw 'Pocket 未能确认本地数据目录仅向当前用户和系统开放。'
        }
    }

    $stage.Value = 'STATE_WRITE_CHECK'
    # A verified ACL is not proof of write access. Use an exclusive, empty
    # temporary file that Windows deletes on close, never existing user data.
    $probe = Join-Path $path ('.pocket-write-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $stream = [IO.FileStream]::new($probe, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
        [IO.FileShare]::None, 4096, [IO.FileOptions]::DeleteOnClose)
    try { $stream.WriteByte(0) } finally { $stream.Dispose() }
    return $path
}

function Get-PocketStateFailureHint($stage) {
    switch ($stage) {
        'STATE_PATH' { 'Pocket 无法使用本地数据目录。请使用原来配对时的 Windows 账户，并确认数据目录是普通文件夹，不是链接。' }
        'STATE_CREATE' { 'Pocket 无法创建本地数据目录。请确认当前 Windows 账户可在本地应用数据目录下创建文件夹。' }
        'STATE_ACL_READ' { 'Pocket 无法读取本地数据目录权限。请使用原来配对时的 Windows 账户，或请管理员检查目录权限。' }
        'STATE_ACL_REPAIR' { 'Pocket 无法保护现有本地数据。请使用原来配对时的 Windows 账户，或请管理员检查目录所有者及权限。请勿删除原目录。' }
        'STATE_ACL_VERIFY' { 'Pocket 未能确认本地数据目录的隐私权限。为保护账号和配对信息，未启动连接服务。' }
        'STATE_WRITE_CHECK' { 'Pocket 本地数据目录不可写。请检查当前 Windows 账户的写入权限及磁盘剩余空间，请勿删除原目录。' }
    }
}

function Get-PocketStartupHResult($record) {
    $exception = $record.Exception
    while ($exception.InnerException) { $exception = $exception.InnerException }
    return '0x' + $exception.HResult.ToString('X8')
}
