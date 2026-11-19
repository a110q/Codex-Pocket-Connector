# Run with Windows PowerShell 5.1 as an ordinary user. Only disposable local
# fixtures are changed; never read or replace the real Pocket data directory.
param([string]$Helper = (Join-Path $PSScriptRoot '..\..\scripts\windows-state-directory.ps1'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. $Helper
$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$user = $id.User
$isElevated = ([Security.Principal.WindowsPrincipal]::new($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$system = New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'
$everyone = New-Object Security.Principal.SecurityIdentifier 'S-1-1-0'
$ownerRights = New-Object Security.Principal.SecurityIdentifier 'S-1-3-4'
$id.Dispose()
$sections = [Security.AccessControl.AccessControlSections]'Access,Owner'
$fixture = Join-Path $env:LOCALAPPDATA ('Pocket-State-Test-' + [Guid]::NewGuid().ToString('N'))
$stage = ''

function Assert($condition, $message) {
    if (-not $condition) { throw $message }
}
function Set-FixtureAcl($path, $extraRules) {
    $acl = New-PocketStateAcl $user $system
    foreach ($rule in $extraRules) { $acl.AddAccessRule($rule) }
    [IO.Directory]::SetAccessControl($path, $acl)
}
function Assert-FailureStage($path, $expected, $expectedHResult) {
    $lastStage = ''
    try { Initialize-PocketStateDirectory $path ([ref]$lastStage) | Out-Null }
    catch {
        Assert ($lastStage -eq $expected) "Expected $expected, got ${lastStage}: $_"
        if ($expectedHResult) { Assert ((Get-PocketStartupHResult $_) -eq $expectedHResult) "Unexpected diagnosis: $_" }
        return
    }
    throw "Expected failure at $expected"
}
try {
    [IO.Directory]::CreateDirectory($fixture, (New-PocketStateAcl $user $system -ForCreation)) | Out-Null
    $fresh = Join-Path $fixture 'fresh'
    Assert ((Initialize-PocketStateDirectory $fresh ([ref]$stage)) -eq $fresh) 'First launch failed'
    $freshAcl = [IO.Directory]::GetAccessControl($fresh, $sections)
    Assert ($freshAcl.GetOwner([Security.Principal.SecurityIdentifier]) -eq $user) 'New directory is not owned by the user'
    Assert (Test-PocketStateAcl $freshAcl $user $system) 'New DACL is not private'
    $freshAcl.SetOwner((New-Object Security.Principal.SecurityIdentifier 'S-1-5-32-544'))
    Assert (-not (Test-PocketStateAcl $freshAcl $user $system)) 'Administrators-owned directory must still be rejected'
    Assert (@(Get-ChildItem -LiteralPath $fresh -Force).Count -eq 0) 'Unexpected write probe residue'
    'PASS first launch'

    $existing = Join-Path $fixture 'existing'
    Initialize-PocketStateDirectory $existing ([ref]$stage) | Out-Null
    $state = Join-Path $existing 'connector-state.json'
    [IO.File]::WriteAllText($state, '{"fixture":true}')
    $denyAclWrite = New-Object Security.AccessControl.FileSystemAccessRule($ownerRights, 'ChangePermissions', 'Deny')
    Set-FixtureAcl $existing @($denyAclWrite)
    $before = [IO.Directory]::GetAccessControl($existing, $sections).Sddl
    # The historical provider failure is specific to an ordinary user token.
    # An elevated Set-Acl can use privileges that the old user did not have.
    if (-not $isElevated) {
        $legacyBlocked = $false
        try { Set-Acl -LiteralPath $existing -AclObject (New-PocketStateAcl $user $system) }
        catch { $legacyBlocked = (Get-PocketStartupHResult $_) -eq '0x80070005' }
        Assert $legacyBlocked 'Legacy Set-Acl should fail with access denied'
        'PASS historical Set-Acl access denial under ordinary token'
    }
    Assert ((Initialize-PocketStateDirectory $existing ([ref]$stage)) -eq $existing) 'Safe directory should still open'
    Assert ([IO.Directory]::GetAccessControl($existing, $sections).Sddl -eq $before) 'Safe DACL was rewritten'
    Assert ([IO.File]::ReadAllText($state) -ceq '{"fixture":true}') 'Existing state was changed'
    'PASS safe rerun and state preservation'

    $unwritable = Join-Path $fixture 'unwritable'
    Initialize-PocketStateDirectory $unwritable ([ref]$stage) | Out-Null
    $denyFileCreate = New-Object Security.AccessControl.FileSystemAccessRule($user, 'CreateFiles', 'Deny')
    Set-FixtureAcl $unwritable @($denyFileCreate)
    Assert-FailureStage $unwritable 'STATE_WRITE_CHECK' '0x80070005'
    'PASS private but unwritable directory'

    $worldRead = New-Object Security.AccessControl.FileSystemAccessRule($everyone, 'ReadAndExecute', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $legacy = Join-Path $fixture 'legacy'
    Initialize-PocketStateDirectory $legacy ([ref]$stage) | Out-Null
    Set-FixtureAcl $legacy @($worldRead)
    $legacyState = Join-Path $legacy 'connector-state.json'
    [IO.File]::WriteAllText($legacyState, '{"fixture":true}')
    $ownerBefore = [IO.Directory]::GetAccessControl($legacy, $sections).GetOwner([Security.Principal.SecurityIdentifier])
    Initialize-PocketStateDirectory $legacy ([ref]$stage) | Out-Null
    $repaired = [IO.Directory]::GetAccessControl($legacy, $sections)
    Assert (Test-PocketStateAcl $repaired $user $system) 'Unsafe DACL was not repaired'
    Assert ($repaired.GetOwner([Security.Principal.SecurityIdentifier]) -eq $ownerBefore) 'Directory owner was changed'
    $childRules = [IO.File]::GetAccessControl($legacyState).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
    Assert (-not ($childRules | Where-Object { $_.IdentityReference -eq $everyone })) 'Unsafe inherited access persisted on existing data'
    Assert ([IO.File]::ReadAllText($legacyState) -ceq '{"fixture":true}') 'Existing state was changed during repair'
    'PASS unsafe legacy DACL repaired without replacing data'

    $blocked = Join-Path $fixture 'unsafe-locked'
    Initialize-PocketStateDirectory $blocked ([ref]$stage) | Out-Null
    Set-FixtureAcl $blocked @($worldRead, $denyAclWrite)
    Assert-FailureStage $blocked 'STATE_ACL_REPAIR' '0x80070005'
    Assert (-not (Get-ChildItem -LiteralPath $blocked -Force)) 'Unsafe directory should not get new files'
    'PASS unsafe DACL that cannot be repaired is blocked'

    $occupied = Join-Path $fixture 'file-not-folder'
    [IO.File]::WriteAllText($occupied, 'fixture')
    Assert-FailureStage $occupied 'STATE_CREATE' $null
    Assert ([IO.File]::ReadAllText($occupied) -ceq 'fixture') 'File in original location was changed'
    'PASS file occupying the state path'

    $junction = Join-Path $fixture 'junction'
    New-Item -ItemType Junction -Path $junction -Target $fresh | Out-Null
    try { Assert-FailureStage $junction 'STATE_PATH' $null }
    finally { [IO.Directory]::Delete($junction) }
    Assert ([IO.Directory]::Exists($fresh)) 'Junction target was changed'
    'PASS directory junction rejected without touching its target'
} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
