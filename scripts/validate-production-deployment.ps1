[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot '..\deploy\docker-compose.production.example.yml'),
    [string]$EvidencePath,
    [string]$ProjectName = 'pms3-production',
    [switch]$ConfigOnly,
    [switch]$SkipSecretFileContentChecks,
    [switch]$VerifyRunningContainers,
    [string[]]$AllowedWindowsPrincipalSids = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$requiredTrivyImage = 'aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
$manifestDigestPattern = '^[^@\s]+@sha256:[0-9a-f]{64}$'
$imageIdPattern = '^sha256:[0-9a-f]{64}$'
$platformPattern = '^[a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*(?:/[a-z0-9][a-z0-9_.-]*)?$'
$runningOnWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pathComparison = if ($runningOnWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$repositoryRoot = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))
. (Join-Path $PSScriptRoot 'scan-container-images.ps1')

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory)] [object]$Object,
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Context
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "Required property is missing: $Context.$Name"
    }
    return ,$property.Value
}

function Get-OptionalProperty {
    param(
        [Parameter(Mandatory)] [object]$Object,
        [Parameter(Mandatory)] [string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return ,$property.Value
}

function Get-NormalizedFullPath {
    param([Parameter(Mandatory)] [string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'A required path is empty.' }
    return [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Path))
}

function Test-PathWithin {
    param(
        [Parameter(Mandatory)] [string]$Candidate,
        [Parameter(Mandatory)] [string]$Parent
    )

    $normalizedCandidate = Get-NormalizedFullPath $Candidate
    $normalizedParent = Get-NormalizedFullPath $Parent
    if ([string]::Equals($normalizedCandidate, $normalizedParent, $pathComparison)) { return $true }
    $parentPrefix = $normalizedParent + [IO.Path]::DirectorySeparatorChar
    return $normalizedCandidate.StartsWith($parentPrefix, $pathComparison)
}

function Test-IsInteger {
    param([object]$Value)

    return (
        $Value -is [sbyte] -or $Value -is [byte] -or
        $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64]
    )
}

function Get-RequiredTimestamp {
    param(
        [Parameter(Mandatory)] [object]$Object,
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Context
    )

    $rawValue = Get-RequiredProperty $Object $Name $Context
    if ($rawValue -is [DateTimeOffset]) {
        return $rawValue.ToUniversalTime()
    }
    if ($rawValue -is [DateTime]) {
        return ([DateTimeOffset]$rawValue).ToUniversalTime()
    }
    if ($rawValue -isnot [string] -or [string]::IsNullOrWhiteSpace($rawValue)) {
        throw "$Context.$Name must be an ISO-8601 timestamp."
    }
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($rawValue, [ref]$parsed)) {
        throw "$Context.$Name is not a valid timestamp: $rawValue"
    }
    return $parsed.ToUniversalTime()
}

function Assert-NoReparsePoint {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    $item = Get-Item -Force -LiteralPath $Path
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Context must not be a symbolic link or junction: $Path"
    }
}

function Assert-RestrictedWindowsAcl {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    $allowedSids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    [void]$allowedSids.Add('S-1-5-18')
    [void]$allowedSids.Add('S-1-5-32-544')
    [void]$allowedSids.Add([Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
    foreach ($allowedSid in $AllowedWindowsPrincipalSids) {
        try {
            $normalizedSid = [Security.Principal.SecurityIdentifier]::new($allowedSid).Value
        } catch {
            throw "AllowedWindowsPrincipalSids contains an invalid SID: $allowedSid"
        }
        [void]$allowedSids.Add($normalizedSid)
    }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) {
        throw "$Context must disable inherited Windows ACL entries: $Path"
    }
    $forbiddenRights =
        [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in @($acl.Access)) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            throw "$Context contains an Allow ACE whose principal cannot be translated to a SID ('$($rule.IdentityReference)'): $Path"
        }
        if (-not $allowedSids.Contains($sid)) {
            throw "$Context grants access to an unapproved Windows principal ($sid): $Path"
        }
        if (($rule.FileSystemRights -band $forbiddenRights) -ne 0) {
            throw "$Context grants write, delete, or ACL-changing rights to '$($rule.IdentityReference)': $Path"
        }
    }
}

function Assert-StrictUnixDirectoryMode {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    $mode = [int][IO.File]::GetUnixFileMode($Path)
    if ($mode -ne 448) {
        throw "$Context must have exact Unix mode 0700: $Path"
    }
}

function Assert-ReadOnlyUnixSecretMode {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    $mode = [int][IO.File]::GetUnixFileMode($Path)
    if ($mode -ne 292) {
        throw "$Context must have exact Unix mode 0444 so bind-mounted non-root service UIDs can read it without write or execute permission: $Path"
    }
}

function Assert-ReadOnlyWindowsSecret {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    $item = Get-Item -Force -LiteralPath $Path
    if (($item.Attributes -band [IO.FileAttributes]::ReadOnly) -eq 0) {
        throw "$Context must have the Windows read-only attribute: $Path"
    }

    $forbiddenRights =
        [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership -bor
        [Security.AccessControl.FileSystemRights]::ExecuteFile
    $acl = Get-Acl -LiteralPath $Path
    foreach ($rule in @($acl.Access)) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        if (($rule.FileSystemRights -band $forbiddenRights) -ne 0) {
            throw "$Context grants write, execute, delete, or ACL-changing rights to '$($rule.IdentityReference)': $Path"
        }
    }
}

function Assert-ServiceSecretMounts {
    param(
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [hashtable]$Expected
    )

    $mounts = Get-OptionalProperty $Service 'secrets'
    if ($Expected.Count -eq 0) {
        if ($null -ne $mounts -and @($mounts).Count -gt 0) {
            throw "services.$ServiceName must not mount unapproved secrets."
        }
        return
    }
    if ($null -eq $mounts) {
        throw "Required property is missing: services.$ServiceName.secrets"
    }
    if ($mounts -isnot [System.Array]) {
        throw "services.$ServiceName.secrets must be an array."
    }
    if ($mounts.Count -ne $Expected.Count) {
        throw "services.$ServiceName must mount exactly $($Expected.Count) approved secrets."
    }
    $actual = @{}
    foreach ($mount in $mounts) {
        $source = Get-RequiredProperty $mount 'source' "services.$ServiceName.secrets[]"
        $target = Get-RequiredProperty $mount 'target' "services.$ServiceName.secrets[]"
        if ($source -isnot [string] -or $target -isnot [string]) {
            throw "services.$ServiceName secret source and target must be strings."
        }
        if ($actual.ContainsKey($source)) { throw "services.$ServiceName mounts secret '$source' more than once." }
        $actual[$source] = $target
    }
    foreach ($source in $Expected.Keys) {
        if (-not $actual.ContainsKey($source) -or $actual[$source] -cne $Expected[$source]) {
            throw "services.$ServiceName must mount '$source' at '$($Expected[$source])'."
        }
    }
}

function Assert-ServicePrivilegeBoundary {
    param(
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName
    )

    $privileged = Get-OptionalProperty $Service 'privileged'
    if ($null -ne $privileged -and ($privileged -isnot [bool] -or $privileged)) {
        throw "services.$ServiceName.privileged must be absent or false."
    }
    $useApiSocket = Get-OptionalProperty $Service 'use_api_socket'
    if ($null -ne $useApiSocket -and ($useApiSocket -isnot [bool] -or $useApiSocket)) {
        throw "services.$ServiceName.use_api_socket must be absent or false; the container engine API socket is forbidden."
    }

    foreach ($propertyName in @(
        'cap_add', 'configs', 'devices', 'device_cgroup_rules', 'gpus',
        'group_add', 'post_start', 'pre_stop', 'security_opt', 'storage_opt',
        'sysctls', 'volumes_from'
    )) {
        $value = Get-OptionalProperty $Service $propertyName
        if ($null -ne $value -and @($value).Count -gt 0) {
            throw "services.$ServiceName.$propertyName is forbidden by the production privilege boundary."
        }
    }

    foreach ($propertyName in @(
        'cgroup', 'credential_spec', 'ipc', 'isolation', 'network_mode', 'pid',
        'runtime', 'user', 'userns_mode', 'uts'
    )) {
        $value = Get-OptionalProperty $Service $propertyName
        if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
            throw "services.$ServiceName.$propertyName overrides are forbidden by the production privilege boundary."
        }
    }
}

function Assert-ServiceVolumes {
    param(
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [hashtable]$Expected
    )

    $volumeValue = Get-OptionalProperty $Service 'volumes'
    $mounts = @()
    if ($null -ne $volumeValue) {
        $mounts = @($volumeValue)
    }
    if ($mounts.Count -ne $Expected.Count) {
        throw "services.$ServiceName must mount exactly $($Expected.Count) approved named volumes; arbitrary host bind mounts and Docker socket mounts are forbidden."
    }

    $actual = @{}
    foreach ($mount in $mounts) {
        $type = Get-RequiredProperty $mount 'type' "services.$ServiceName.volumes[]"
        $source = Get-RequiredProperty $mount 'source' "services.$ServiceName.volumes[]"
        $target = Get-RequiredProperty $mount 'target' "services.$ServiceName.volumes[]"
        if ($type -isnot [string] -or $type -cne 'volume') {
            throw "services.$ServiceName may use only approved named volumes; host bind mounts, including /var/run/docker.sock, are forbidden."
        }
        if ($source -isnot [string] -or $target -isnot [string] -or
            [string]::IsNullOrWhiteSpace($source) -or [string]::IsNullOrWhiteSpace($target)) {
            throw "services.$ServiceName named volume source and target must be non-empty strings."
        }
        if ($actual.ContainsKey($source)) {
            throw "services.$ServiceName mounts named volume '$source' more than once."
        }
        $readOnly = Get-OptionalProperty $mount 'read_only'
        if ($null -ne $readOnly -and ($readOnly -isnot [bool] -or $readOnly)) {
            throw "services.$ServiceName approved data volume '$source' must be writable."
        }
        $volumeOptions = Get-OptionalProperty $mount 'volume'
        if ($null -ne $volumeOptions -and @($volumeOptions.PSObject.Properties).Count -gt 0) {
            throw "services.$ServiceName named volume '$source' must not override volume mount options."
        }
        $actual[$source] = $target
    }

    foreach ($source in $Expected.Keys) {
        if (-not $actual.ContainsKey($source) -or $actual[$source] -cne $Expected[$source]) {
            throw "services.$ServiceName must mount approved named volume '$source' at '$($Expected[$source])'."
        }
    }
}

function Get-ApprovedVolumeNames {
    param(
        [Parameter(Mandatory)] [object]$VolumeDefinitions,
        [Parameter(Mandatory)] [string[]]$ExpectedNames
    )

    Assert-ExactStringSet @($VolumeDefinitions.PSObject.Properties.Name) $ExpectedNames 'compose.volumes'
    $resolvedNames = @{}
    foreach ($logicalName in $ExpectedNames) {
        $definition = Get-RequiredProperty $VolumeDefinitions $logicalName 'volumes'
        $external = Get-OptionalProperty $definition 'external'
        if ($null -ne $external -and ($external -isnot [bool] -or $external)) {
            throw "volumes.$logicalName must be a project-owned local volume, not external."
        }
        $driver = Get-OptionalProperty $definition 'driver'
        if ($null -ne $driver -and ($driver -isnot [string] -or $driver -cne 'local')) {
            throw "volumes.$logicalName may use only the Docker local volume driver."
        }
        $driverOptions = Get-OptionalProperty $definition 'driver_opts'
        if ($null -ne $driverOptions -and @($driverOptions.PSObject.Properties).Count -gt 0) {
            throw "volumes.$logicalName.driver_opts is forbidden because it can disguise a host bind mount."
        }
        $resolvedName = Get-RequiredProperty $definition 'name' "volumes.$logicalName"
        if ($resolvedName -isnot [string] -or [string]::IsNullOrWhiteSpace($resolvedName)) {
            throw "volumes.$logicalName.name must resolve to a non-empty Docker volume name."
        }
        $resolvedNames[$logicalName] = $resolvedName
    }
    return $resolvedNames
}

function Assert-RunningContainerPrivilegeBoundary {
    param(
        [Parameter(Mandatory)] [object]$Container,
        [Parameter(Mandatory)] [string]$ServiceName
    )

    $hostConfig = Get-RequiredProperty $Container 'HostConfig' "container.$ServiceName"
    $privileged = Get-RequiredProperty $hostConfig 'Privileged' "container.$ServiceName.HostConfig"
    if ($privileged -isnot [bool] -or $privileged) {
        throw "Running '$ServiceName' container must not be privileged."
    }
    foreach ($propertyName in @('CapAdd', 'DeviceRequests', 'Devices', 'GroupAdd', 'SecurityOpt')) {
        $value = Get-OptionalProperty $hostConfig $propertyName
        if ($null -ne $value -and @($value).Count -gt 0) {
            throw "Running '$ServiceName' container has forbidden HostConfig.$propertyName entries."
        }
    }

    $safeModes = @{
        PidMode = @('')
        IpcMode = @('', 'private')
        CgroupnsMode = @('', 'private')
        UsernsMode = @('')
        UTSMode = @('')
    }
    foreach ($propertyName in $safeModes.Keys) {
        $value = Get-OptionalProperty $hostConfig $propertyName
        if ($null -ne $value -and [string]$value -cnotin @($safeModes[$propertyName])) {
            throw "Running '$ServiceName' container has forbidden HostConfig.$propertyName '$value'."
        }
    }
}

function Assert-RunningContainerMountBoundary {
    param(
        [Parameter(Mandatory)] [object]$Container,
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [hashtable]$ExpectedPersistentVolumes,
        [Parameter(Mandatory)] [hashtable]$ConfiguredVolumeNames,
        [Parameter(Mandatory)] [hashtable]$ExpectedSecrets,
        [Parameter(Mandatory)] [hashtable]$ConfiguredSecretPaths
    )

    $expectedByTarget = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($logicalName in $ExpectedPersistentVolumes.Keys) {
        if (-not $ConfiguredVolumeNames.ContainsKey($logicalName)) {
            throw "Missing configured Docker volume name for '$logicalName'."
        }
        $target = [string]$ExpectedPersistentVolumes[$logicalName]
        $expectedByTarget.Add($target, [pscustomobject]@{
            Type = 'volume'
            Source = [string]$ConfiguredVolumeNames[$logicalName]
            ReadWrite = $true
        })
    }
    foreach ($secretName in $ExpectedSecrets.Keys) {
        if (-not $ConfiguredSecretPaths.ContainsKey($secretName)) {
            throw "Missing configured secret path for '$secretName'."
        }
        $target = "/run/secrets/$($ExpectedSecrets[$secretName])"
        $expectedByTarget.Add($target, [pscustomobject]@{
            Type = 'bind'
            Source = Get-NormalizedFullPath ([string]$ConfiguredSecretPaths[$secretName])
            ReadWrite = $false
        })
    }

    $mountProperty = $Container.PSObject.Properties['Mounts']
    if ($null -eq $mountProperty -or $null -eq $mountProperty.Value) {
        throw "Required property is missing: container.$ServiceName.Mounts"
    }
    $mounts = @($mountProperty.Value)
    if ($mounts.Count -ne $expectedByTarget.Count) {
        throw "Running '$ServiceName' container has an unexpected mount count; only approved data volumes and read-only Secret binds are allowed."
    }
    foreach ($mount in $mounts) {
        $target = Get-RequiredProperty $mount 'Destination' "container.$ServiceName.Mounts[]"
        if ($target -isnot [string] -or -not $expectedByTarget.ContainsKey($target)) {
            throw "Running '$ServiceName' container has an unapproved mount target '$target'; Docker socket and arbitrary host binds are forbidden."
        }
        $expected = $expectedByTarget[$target]
        $type = Get-RequiredProperty $mount 'Type' "container.$ServiceName.Mounts[]"
        $readWrite = Get-RequiredProperty $mount 'RW' "container.$ServiceName.Mounts[]"
        if ($type -isnot [string] -or $type -cne $expected.Type -or
            $readWrite -isnot [bool] -or $readWrite -ne $expected.ReadWrite) {
            throw "Running '$ServiceName' mount '$target' does not match its approved type/read-only contract."
        }
        if ($expected.Type -ceq 'volume') {
            $name = Get-RequiredProperty $mount 'Name' "container.$ServiceName.Mounts[]"
            if ($name -isnot [string] -or $name -cne $expected.Source) {
                throw "Running '$ServiceName' mount '$target' does not use the approved named volume."
            }
        } else {
            $source = Get-RequiredProperty $mount 'Source' "container.$ServiceName.Mounts[]"
            if ($source -isnot [string] -or
                -not [string]::Equals((Get-NormalizedFullPath $source), $expected.Source, $pathComparison)) {
                throw "Running '$ServiceName' Secret mount '$target' does not use the approved host file."
            }
        }
        [void]$expectedByTarget.Remove($target)
    }
}

function Assert-ExactStringSet {
    param(
        [Parameter(Mandatory)] [object[]]$Actual,
        [Parameter(Mandatory)] [object[]]$Expected,
        [Parameter(Mandatory)] [string]$Context
    )

    $actualValues = @($Actual | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $expectedValues = @($Expected | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    if (($actualValues -join "`n") -cne ($expectedValues -join "`n")) {
        throw "$Context must be exactly [$($expectedValues -join ', ')]; found [$($actualValues -join ', ')]."
    }
}

function Assert-ExactStringSequence {
    param(
        [AllowNull()] [object]$Actual,
        [AllowNull()] [object]$Expected,
        [Parameter(Mandatory)] [string]$Context
    )

    [string[]]$actualValues = @()
    [string[]]$expectedValues = @()
    if ($null -ne $Actual) {
        $actualValues = @($Actual | ForEach-Object { [string]$_ })
    }
    if ($null -ne $Expected) {
        $expectedValues = @($Expected | ForEach-Object { [string]$_ })
    }
    if ($actualValues.Count -ne $expectedValues.Count) {
        throw "$Context must contain exactly $($expectedValues.Count) ordered value(s); found $($actualValues.Count)."
    }
    for ($index = 0; $index -lt $expectedValues.Count; $index++) {
        if ($actualValues[$index] -cne $expectedValues[$index]) {
            throw "$Context differs at index $index; startup and healthcheck overrides are locked."
        }
    }
}

function Get-TextSha256 {
    param([Parameter(Mandatory)] [string]$Text)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Assert-ServiceHealthcheckContract {
    param(
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [string[]]$ExpectedTest,
        [Parameter(Mandatory)] [string]$ExpectedInterval,
        [Parameter(Mandatory)] [string]$ExpectedTimeout,
        [Parameter(Mandatory)] [int]$ExpectedRetries
    )

    $healthcheck = Get-RequiredProperty $Service 'healthcheck' "services.$ServiceName"
    Assert-ExactStringSet @($healthcheck.PSObject.Properties.Name) @('test', 'interval', 'timeout', 'retries') "services.$ServiceName.healthcheck properties"
    Assert-ExactStringSequence (Get-RequiredProperty $healthcheck 'test' "services.$ServiceName.healthcheck") $ExpectedTest "services.$ServiceName.healthcheck.test"
    if ((Get-RequiredProperty $healthcheck 'interval' "services.$ServiceName.healthcheck") -cne $ExpectedInterval -or
        (Get-RequiredProperty $healthcheck 'timeout' "services.$ServiceName.healthcheck") -cne $ExpectedTimeout) {
        throw "services.$ServiceName.healthcheck interval/timeout must remain locked to $ExpectedInterval/$ExpectedTimeout."
    }
    $retries = Get-RequiredProperty $healthcheck 'retries' "services.$ServiceName.healthcheck"
    if (-not (Test-IsInteger $retries) -or [int64]$retries -ne $ExpectedRetries) {
        throw "services.$ServiceName.healthcheck.retries must remain locked to $ExpectedRetries."
    }
}

function Convert-ComposeDurationToNanoseconds {
    param(
        [Parameter(Mandatory)] [string]$Duration,
        [Parameter(Mandatory)] [string]$Context
    )

    $match = [regex]::Match($Duration, '^(?<seconds>[1-9][0-9]*)s$')
    if (-not $match.Success) { throw "$Context must be a positive whole-second duration." }
    return [int64]$match.Groups['seconds'].Value * 1000000000L
}

function Convert-EnvironmentSequenceToMap {
    param(
        [AllowNull()] [object]$Environment,
        [Parameter(Mandatory)] [string]$Context
    )

    $result = @{}
    foreach ($entryValue in @($Environment)) {
        if ($entryValue -isnot [string]) {
            throw "$Context must contain only KEY=value strings."
        }
        $separator = $entryValue.IndexOf('=')
        if ($separator -le 0) {
            throw "$Context contains an invalid environment entry."
        }
        $name = $entryValue.Substring(0, $separator)
        if ($name -cnotmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $result.ContainsKey($name)) {
            throw "$Context contains an invalid or duplicate environment key '$name'."
        }
        $result[$name] = $entryValue.Substring($separator + 1)
    }
    return $result
}

function Assert-RunningEnvironmentContract {
    param(
        [Parameter(Mandatory)] [object]$ContainerConfig,
        [Parameter(Mandatory)] [object]$ImageConfig,
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName
    )

    $expected = Convert-EnvironmentSequenceToMap (Get-OptionalProperty $ImageConfig 'Env') "image.$ServiceName.Config.Env"
    $serviceEnvironment = Get-OptionalProperty $Service 'environment'
    if ($null -ne $serviceEnvironment) {
        foreach ($property in $serviceEnvironment.PSObject.Properties) {
            if ($null -eq $property.Value) {
                throw "services.$ServiceName.environment.$($property.Name) must resolve to a concrete value."
            }
            $expected[$property.Name] = [string]$property.Value
        }
    }

    $actual = Convert-EnvironmentSequenceToMap (Get-OptionalProperty $ContainerConfig 'Env') "container.$ServiceName.Config.Env"
    Assert-ExactStringSet @($actual.Keys) @($expected.Keys) "container.$ServiceName.Config.Env keys"
    foreach ($name in $expected.Keys) {
        if ($actual[$name] -cne $expected[$name]) {
            throw "Running '$ServiceName' environment value for '$name' differs from the image and validated Compose definition."
        }
    }
}

function Assert-RunningProcessContract {
    param(
        [Parameter(Mandatory)] [object]$ContainerConfig,
        [Parameter(Mandatory)] [object]$ImageConfig,
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName
    )

    $composeEntrypoint = Get-OptionalProperty $Service 'entrypoint'
    $expectedEntrypoint = if ($null -ne $composeEntrypoint) { $composeEntrypoint } else { Get-OptionalProperty $ImageConfig 'Entrypoint' }
    $composeCommand = Get-OptionalProperty $Service 'command'
    $expectedCommand = if ($null -ne $composeCommand) {
        $composeCommand
    } elseif ($null -ne $composeEntrypoint) {
        # Compose intentionally discards the image CMD when a non-null
        # entrypoint is supplied and no command override is declared.
        $null
    } else {
        Get-OptionalProperty $ImageConfig 'Cmd'
    }
    Assert-ExactStringSequence (Get-OptionalProperty $ContainerConfig 'Entrypoint') $expectedEntrypoint "container.$ServiceName.Config.Entrypoint"
    Assert-ExactStringSequence (Get-OptionalProperty $ContainerConfig 'Cmd') $expectedCommand "container.$ServiceName.Config.Cmd"

    $renderedHealthcheck = Get-RequiredProperty $Service 'healthcheck' "services.$ServiceName"
    $runningHealthcheck = Get-RequiredProperty $ContainerConfig 'Healthcheck' "container.$ServiceName.Config"
    Assert-ExactStringSet @($runningHealthcheck.PSObject.Properties.Name) @('Test', 'Interval', 'Timeout', 'Retries') "container.$ServiceName.Config.Healthcheck properties"
    Assert-ExactStringSequence (Get-RequiredProperty $runningHealthcheck 'Test' "container.$ServiceName.Config.Healthcheck") (Get-RequiredProperty $renderedHealthcheck 'test' "services.$ServiceName.healthcheck") "container.$ServiceName.Config.Healthcheck.Test"
    $expectedInterval = Convert-ComposeDurationToNanoseconds ([string](Get-RequiredProperty $renderedHealthcheck 'interval' "services.$ServiceName.healthcheck")) "services.$ServiceName.healthcheck.interval"
    $expectedTimeout = Convert-ComposeDurationToNanoseconds ([string](Get-RequiredProperty $renderedHealthcheck 'timeout' "services.$ServiceName.healthcheck")) "services.$ServiceName.healthcheck.timeout"
    if ([int64](Get-RequiredProperty $runningHealthcheck 'Interval' "container.$ServiceName.Config.Healthcheck") -ne $expectedInterval -or
        [int64](Get-RequiredProperty $runningHealthcheck 'Timeout' "container.$ServiceName.Config.Healthcheck") -ne $expectedTimeout -or
        [int64](Get-RequiredProperty $runningHealthcheck 'Retries' "container.$ServiceName.Config.Healthcheck") -ne [int64](Get-RequiredProperty $renderedHealthcheck 'retries' "services.$ServiceName.healthcheck")) {
        throw "Running '$ServiceName' healthcheck timing/retry contract differs from the validated Compose definition."
    }
}

function Assert-ServiceNetworks {
    param(
        [Parameter(Mandatory)] [object]$Service,
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [string[]]$Expected
    )

    $serviceNetworks = Get-RequiredProperty $Service 'networks' "services.$ServiceName"
    $actualNames = @($serviceNetworks.PSObject.Properties.Name)
    Assert-ExactStringSet $actualNames $Expected "services.$ServiceName.networks"
}

function Get-DockerImageInspection {
    param(
        [Parameter(Mandatory)] [string]$Image,
        [Parameter(Mandatory)] [string]$Context
    )

    $inspectOutput = (& docker image inspect --format '{{json .}}' $Image) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($inspectOutput)) {
        throw "Unable to inspect $Context image: $Image"
    }
    try {
        $inspection = $inspectOutput | ConvertFrom-Json
    } catch {
        throw "Docker returned invalid image inspection JSON for ${Context}: $($_.Exception.Message)"
    }
    $imageId = Get-RequiredProperty $inspection 'Id' $Context
    $os = Get-RequiredProperty $inspection 'Os' $Context
    $architecture = Get-RequiredProperty $inspection 'Architecture' $Context
    $variant = Get-OptionalProperty $inspection 'Variant'
    if ($imageId -isnot [string] -or $imageId -cnotmatch $imageIdPattern) {
        throw "$Context image ID is invalid."
    }
    $platform = "$os/$architecture"
    if ($variant -is [string] -and -not [string]::IsNullOrWhiteSpace($variant)) {
        $platform += "/$variant"
    }
    if ($platform -cnotmatch $platformPattern) {
        throw "$Context image platform is invalid: $platform"
    }
    return [pscustomobject]@{
        ImageId = $imageId
        Platform = $platform
        Config = Get-RequiredProperty $inspection 'Config' $Context
    }
}

function Get-StrictUtf8SecretText {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Context
    )

    try {
        $strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
        return [IO.File]::ReadAllText($Path, $strictUtf8)
    } catch [Text.DecoderFallbackException] {
        throw "$Context must contain valid UTF-8 text."
    }
}

function Assert-StrongBootstrapPassword {
    param(
        [Parameter(Mandatory)] [string]$Username,
        [Parameter(Mandatory)] [string]$Password
    )

    if ($Password.Length -lt 12 -or $Password.Length -gt 200) {
        throw 'PMS_BOOTSTRAP_ADMIN_PASSWORD must contain 12-200 characters.'
    }

    $hasUpper = $false
    $hasLower = $false
    $hasDigit = $false
    $hasSymbol = $false
    foreach ($character in $Password.ToCharArray()) {
        if ([char]::IsUpper($character)) { $hasUpper = $true }
        if ([char]::IsLower($character)) { $hasLower = $true }
        if ([char]::IsDigit($character)) { $hasDigit = $true }
        if (-not [char]::IsLetterOrDigit($character)) { $hasSymbol = $true }
    }
    if (-not ($hasUpper -and $hasLower -and $hasDigit -and $hasSymbol)) {
        throw 'PMS_BOOTSTRAP_ADMIN_PASSWORD must contain upper-case, lower-case, digit, and symbol characters.'
    }

    $normalizedUsername = $Username.Trim().ToLowerInvariant()
    if ($normalizedUsername.Length -ge 3 -and
        $Password.ToLowerInvariant().Contains($normalizedUsername, [StringComparison]::Ordinal)) {
        throw 'PMS_BOOTSTRAP_ADMIN_PASSWORD must not contain the complete bootstrap username.'
    }
}

if ($MyInvocation.InvocationName -eq '.') {
    return
}

if ($SkipSecretFileContentChecks -and -not $ConfigOnly) {
    throw '-SkipSecretFileContentChecks is allowed only with -ConfigOnly; deployment validation must inspect secret files and permissions.'
}
if ($VerifyRunningContainers -and $ConfigOnly) {
    throw '-VerifyRunningContainers cannot be combined with -ConfigOnly.'
}
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]*$') {
    throw 'ProjectName must contain only lower-case letters, digits, underscores, and hyphens.'
}

$composePath = Get-NormalizedFullPath $ComposeFile
if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) {
    throw "Production Compose file is missing: $composePath"
}

$secretDirectoryValue = [Environment]::GetEnvironmentVariable('PMS_SECRET_DIR', 'Process')
if ([string]::IsNullOrWhiteSpace($secretDirectoryValue)) {
    throw 'PMS_SECRET_DIR must point to a controlled secret directory outside the repository.'
}
if (-not [IO.Path]::IsPathFullyQualified($secretDirectoryValue)) {
    throw 'PMS_SECRET_DIR must be an absolute path.'
}
$secretDirectory = Get-NormalizedFullPath $secretDirectoryValue
if (Test-PathWithin $secretDirectory $repositoryRoot) {
    throw "PMS_SECRET_DIR must be outside the repository: $repositoryRoot"
}

$targetPlatform = [Environment]::GetEnvironmentVariable('PMS_TARGET_PLATFORM', 'Process')
if ([string]::IsNullOrWhiteSpace($targetPlatform) -or $targetPlatform -cnotmatch $platformPattern) {
    throw 'PMS_TARGET_PLATFORM must be an explicit os/architecture[/variant] value such as linux/amd64.'
}
if (-not $targetPlatform.StartsWith('linux/', [StringComparison]::Ordinal)) {
    throw 'PMS_TARGET_PLATFORM must select a Linux container platform because the runtime gate verifies /proc/1/status.'
}

$composeOutput = (& docker compose --project-name $ProjectName -f $composePath config --format json) -join "`n"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($composeOutput)) {
    throw 'Unable to render the independent production Compose file.'
}
try {
    $compose = $composeOutput | ConvertFrom-Json
} catch {
    throw "Docker Compose returned invalid JSON: $($_.Exception.Message)"
}

$services = Get-RequiredProperty $compose 'services' 'compose'
$serviceNames = @($services.PSObject.Properties.Name)
$requiredServiceNames = @('api', 'web', 'mysql', 'redis')
if ($serviceNames.Count -ne $requiredServiceNames.Count) {
    throw 'Production Compose must contain exactly api, web, mysql, and redis so every deployed image is gated.'
}

$networkDefinitions = Get-RequiredProperty $compose 'networks' 'compose'
$requiredNetworkNames = @('ingress', 'frontend', 'data')
Assert-ExactStringSet @($networkDefinitions.PSObject.Properties.Name) $requiredNetworkNames 'compose.networks'

$volumeDefinitions = Get-RequiredProperty $compose 'volumes' 'compose'
$configuredVolumeNames = Get-ApprovedVolumeNames $volumeDefinitions @('mysql-data', 'redis-data')
foreach ($logicalVolumeName in @('mysql-data', 'redis-data')) {
    $expectedVolumeName = "${ProjectName}_$logicalVolumeName"
    if ($configuredVolumeNames[$logicalVolumeName] -cne $expectedVolumeName) {
        throw "volumes.$logicalVolumeName.name must remain project-owned and resolve exactly to '$expectedVolumeName'."
    }
}

$configuredNetworkNames = @{}
foreach ($networkName in $requiredNetworkNames) {
    $networkDefinition = Get-RequiredProperty $networkDefinitions $networkName 'networks'
    $configuredNetworkName = Get-RequiredProperty $networkDefinition 'name' "networks.$networkName"
    if ($configuredNetworkName -isnot [string] -or [string]::IsNullOrWhiteSpace($configuredNetworkName)) {
        throw "networks.$networkName.name must resolve to a non-empty Docker network name."
    }
    $expectedNetworkName = "${ProjectName}_$networkName"
    if ($configuredNetworkName -cne $expectedNetworkName) {
        throw "networks.$networkName.name must remain project-owned and resolve exactly to '$expectedNetworkName'."
    }
    $external = Get-OptionalProperty $networkDefinition 'external'
    if ($null -ne $external -and ($external -isnot [bool] -or $external)) {
        throw "networks.$networkName must be project-owned, not external or shared."
    }
    $driver = Get-OptionalProperty $networkDefinition 'driver'
    if ($null -ne $driver -and ($driver -isnot [string] -or $driver -cne 'bridge')) {
        throw "networks.$networkName may use only Docker's local bridge driver."
    }
    $driverOptions = Get-OptionalProperty $networkDefinition 'driver_opts'
    if ($null -ne $driverOptions -and @($driverOptions.PSObject.Properties).Count -gt 0) {
        throw "networks.$networkName.driver_opts is forbidden."
    }
    $attachable = Get-OptionalProperty $networkDefinition 'attachable'
    if ($null -ne $attachable -and ($attachable -isnot [bool] -or $attachable)) {
        throw "networks.$networkName must not be attachable by standalone containers."
    }
    $ipam = Get-OptionalProperty $networkDefinition 'ipam'
    if ($null -ne $ipam -and @($ipam.PSObject.Properties).Count -gt 0) {
        throw "networks.$networkName.ipam overrides are forbidden."
    }
    $configuredNetworkNames[$networkName] = $configuredNetworkName
}

$dataNetwork = Get-RequiredProperty $networkDefinitions 'data' 'networks'
$dataInternal = Get-OptionalProperty $dataNetwork 'internal'
if ($dataInternal -isnot [bool] -or -not $dataInternal) {
    throw 'The data network must set internal: true.'
}
$frontendNetwork = Get-RequiredProperty $networkDefinitions 'frontend' 'networks'
$frontendInternal = Get-OptionalProperty $frontendNetwork 'internal'
if ($frontendInternal -isnot [bool] -or -not $frontendInternal) {
    throw 'The frontend network must set internal: true so API has no public egress route.'
}
$ingressNetwork = Get-RequiredProperty $networkDefinitions 'ingress' 'networks'
$ingressInternal = Get-OptionalProperty $ingressNetwork 'internal'
if ($ingressInternal -eq $true) {
    throw 'The ingress network must support the Web loopback published port.'
}

$expectedServiceNetworks = @{
    web = @('ingress', 'frontend')
    api = @('frontend', 'data')
    mysql = @('data')
    redis = @('data')
}
$expectedServiceVolumes = @{
    web = @{}
    api = @{}
    mysql = @{ 'mysql-data' = '/var/lib/mysql' }
    redis = @{ 'redis-data' = '/data' }
}
foreach ($serviceName in $requiredServiceNames) {
    $service = Get-RequiredProperty $services $serviceName 'services'
    Assert-ServiceNetworks $service $serviceName @($expectedServiceNetworks[$serviceName])
    Assert-ServicePrivilegeBoundary $service $serviceName
    Assert-ServiceVolumes $service $serviceName ($expectedServiceVolumes[$serviceName])
    $servicePlatform = Get-RequiredProperty $service 'platform' "services.$serviceName"
    if ($servicePlatform -isnot [string] -or $servicePlatform -cne $targetPlatform) {
        throw "services.$serviceName.platform must equal PMS_TARGET_PLATFORM ($targetPlatform)."
    }
}
foreach ($serviceName in @('api', 'mysql', 'redis')) {
    $service = Get-RequiredProperty $services $serviceName 'services'
    $publishedPorts = Get-OptionalProperty $service 'ports'
    if ($null -ne $publishedPorts -and @($publishedPorts).Count -gt 0) {
        throw "Production service '$serviceName' must not publish host ports."
    }
}

$web = Get-RequiredProperty $services 'web' 'services'
$webPorts = Get-RequiredProperty $web 'ports' 'services.web'
if ($webPorts -isnot [System.Array] -or $webPorts.Count -ne 1) {
    throw 'Production Web must publish exactly one port mapping.'
}
$webPort = $webPorts[0]
$webPortTarget = Get-RequiredProperty $webPort 'target' 'services.web.ports[0]'
$webPortPublished = Get-RequiredProperty $webPort 'published' 'services.web.ports[0]'
$webPortProtocol = Get-RequiredProperty $webPort 'protocol' 'services.web.ports[0]'
$webPortHostIp = Get-RequiredProperty $webPort 'host_ip' 'services.web.ports[0]'
if (-not (Test-IsInteger $webPortTarget) -or [int64]$webPortTarget -ne 8080) {
    throw 'Production Web must publish container port 8080.'
}
if ($webPortProtocol -isnot [string] -or $webPortProtocol -cne 'tcp') {
    throw 'Production Web port 8080 must use TCP.'
}
$publishedPortNumber = 0
if (-not [int]::TryParse([string]$webPortPublished, [ref]$publishedPortNumber) -or
    $publishedPortNumber -lt 1 -or $publishedPortNumber -gt 65535) {
    throw 'Production Web published port must be a fixed integer from 1 through 65535; ephemeral port 0 is forbidden.'
}
if ($publishedPortNumber -ne 5174) {
    throw 'Production Web published port must be exactly 5174 to match deploy/nginx-tls.conf.example.'
}
if ($webPortHostIp -isnot [string] -or $webPortHostIp -cne '127.0.0.1') {
    throw 'Production Web host bind must be exactly 127.0.0.1 to match the host TLS upstream.'
}

$configuredImages = @{}
foreach ($serviceName in $requiredServiceNames) {
    $service = Get-RequiredProperty $services $serviceName 'services'
    if ($null -ne (Get-OptionalProperty $service 'build')) {
        throw "Production service '$serviceName' must not contain a build definition."
    }
    $image = Get-RequiredProperty $service 'image' "services.$serviceName"
    if ($image -isnot [string] -or $image -cnotmatch $manifestDigestPattern) {
        throw "Production service '$serviceName' image must be registry/repository@sha256:<64 lower-case hex>."
    }
    $configuredImages[$serviceName] = $image
}
if (@($configuredImages.Values | Select-Object -Unique).Count -ne $requiredServiceNames.Count) {
    throw 'Each production service must use a distinct immutable image digest.'
}

$mysqlService = Get-RequiredProperty $services 'mysql' 'services'
$redisService = Get-RequiredProperty $services 'redis' 'services'
$apiService = Get-RequiredProperty $services 'api' 'services'
$webService = Get-RequiredProperty $services 'web' 'services'
Assert-ExactStringSequence (Get-RequiredProperty $mysqlService 'command' 'services.mysql') @(
    '--character-set-server=utf8mb4',
    '--collation-server=utf8mb4_0900_ai_ci'
) 'services.mysql.command'
foreach ($serviceContract in @(
    @{ Service = $apiService; Name = 'api'; Property = 'command' },
    @{ Service = $webService; Name = 'web'; Property = 'command' },
    @{ Service = $redisService; Name = 'redis'; Property = 'command' },
    @{ Service = $apiService; Name = 'api'; Property = 'entrypoint' },
    @{ Service = $webService; Name = 'web'; Property = 'entrypoint' },
    @{ Service = $mysqlService; Name = 'mysql'; Property = 'entrypoint' }
)) {
    $overrideProperty = $serviceContract.Service.PSObject.Properties[$serviceContract.Property]
    $overrideValue = if ($null -eq $overrideProperty) { $null } else { $overrideProperty.Value }
    if ($overrideValue -is [System.Array] -or $null -ne $overrideValue) {
        throw "services.$($serviceContract.Name).$($serviceContract.Property) must not override the scanned image startup contract."
    }
}
$lockedRedisEntrypoint = Get-RequiredProperty $redisService 'entrypoint' 'services.redis'
if ($lockedRedisEntrypoint.Count -ne 3 -or $lockedRedisEntrypoint[0] -cne '/bin/sh' -or
    $lockedRedisEntrypoint[1] -cne '-ec' -or
    (Get-TextSha256 ([string]$lockedRedisEntrypoint[2])) -cne '20282c247fc7d9a2eb3884964b0793a0b6817d685b67376aa5badb3488010123') {
    throw 'services.redis.entrypoint differs from the exact reviewed ACL bootstrap program.'
}

$healthcheckContracts = @{
    mysql = @{
        Test = @('CMD-SHELL', 'MYSQL_PWD=$$(cat /run/secrets/MYSQL_ROOT_PASSWORD) mysqladmin ping -h localhost -uroot --silent')
        Interval = '10s'; Timeout = '5s'; Retries = 30
    }
    redis = @{
        Test = @('CMD-SHELL', 'REDISCLI_AUTH="$$(cat /run/secrets/REDIS_PASSWORD)" redis-cli ping | grep -q PONG')
        Interval = '10s'; Timeout = '5s'; Retries = 20
    }
    api = @{
        Test = @('CMD-SHELL', 'wget -q -O - http://localhost:8088/actuator/health | grep -q UP')
        Interval = '10s'; Timeout = '5s'; Retries = 30
    }
    web = @{
        Test = @('CMD-SHELL', 'wget -q -O - http://127.0.0.1:8080/login | grep -q ''<div id="app"''')
        Interval = '10s'; Timeout = '5s'; Retries = 20
    }
}
foreach ($serviceName in $requiredServiceNames) {
    $contract = $healthcheckContracts[$serviceName]
    Assert-ServiceHealthcheckContract `
        -Service (Get-RequiredProperty $services $serviceName 'services') `
        -ServiceName $serviceName `
        -ExpectedTest @($contract.Test) `
        -ExpectedInterval $contract.Interval `
        -ExpectedTimeout $contract.Timeout `
        -ExpectedRetries $contract.Retries
}

$api = Get-RequiredProperty $services 'api' 'services'
$apiEnvironment = Get-RequiredProperty $api 'environment' 'services.api'
foreach ($secretEnvironmentName in @(
    'MYSQL_PASSWORD',
    'REDIS_PASSWORD',
    'JWT_SECRET',
    'PMS_CALLBACK_SIGNING_SECRET',
    'PMS_BOOTSTRAP_ADMIN_PASSWORD'
)) {
    if ($null -ne $apiEnvironment.PSObject.Properties[$secretEnvironmentName]) {
        throw "API must not receive plaintext secret environment variable '$secretEnvironmentName'."
    }
}
$expectedApiEnvironmentNames = @(
    'MYSQL_URL',
    'MYSQL_USER',
    'REDIS_HOST',
    'PMS_CALLBACK_MAX_SKEW_SECONDS',
    'LOGGING_STRUCTURED_FORMAT_CONSOLE',
    'PMS_BOOTSTRAP_ADMIN_USERNAME',
    'PMS_FORMAL_EMPTY_BASELINE',
    'LOGIN_MAX_FAILURES',
    'LOGIN_WINDOW_MINUTES',
    'LOGIN_LOCK_MINUTES',
    'PMS_API_PORT',
    'SPRINGDOC_API_DOCS_ENABLED',
    'SPRINGDOC_SWAGGER_UI_ENABLED'
)
Assert-ExactStringSet @($apiEnvironment.PSObject.Properties.Name) $expectedApiEnvironmentNames 'services.api.environment properties'

$fixedApiEnvironment = @{
    REDIS_HOST = 'redis'
    PMS_CALLBACK_MAX_SKEW_SECONDS = '300'
    LOGGING_STRUCTURED_FORMAT_CONSOLE = 'ecs'
    PMS_FORMAL_EMPTY_BASELINE = 'true'
    PMS_API_PORT = '8088'
    SPRINGDOC_API_DOCS_ENABLED = 'false'
    SPRINGDOC_SWAGGER_UI_ENABLED = 'false'
}
foreach ($setting in $fixedApiEnvironment.GetEnumerator()) {
    if ([string](Get-RequiredProperty $apiEnvironment $setting.Key 'services.api.environment') -cne $setting.Value) {
        throw "API production setting $($setting.Key) must remain locked to $($setting.Value)."
    }
}
foreach ($setting in @{
    LOGIN_MAX_FAILURES = '5'
    LOGIN_WINDOW_MINUTES = '15'
    LOGIN_LOCK_MINUTES = '15'
}.GetEnumerator()) {
    $actualValue = Get-RequiredProperty $apiEnvironment $setting.Key 'services.api.environment'
    if ([string]$actualValue -cne $setting.Value) {
        throw "API production login protection setting $($setting.Key) must remain locked to $($setting.Value)."
    }
}

$mysql = Get-RequiredProperty $services 'mysql' 'services'
$mysqlEnvironment = Get-RequiredProperty $mysql 'environment' 'services.mysql'
Assert-ExactStringSet @($mysqlEnvironment.PSObject.Properties.Name) @(
    'MYSQL_DATABASE',
    'MYSQL_PASSWORD_FILE',
    'MYSQL_ROOT_PASSWORD_FILE',
    'MYSQL_USER',
    'TZ'
) 'services.mysql.environment properties'
if ((Get-RequiredProperty $mysqlEnvironment 'MYSQL_PASSWORD_FILE' 'services.mysql.environment') -cne '/run/secrets/MYSQL_PASSWORD' -or
    (Get-RequiredProperty $mysqlEnvironment 'MYSQL_ROOT_PASSWORD_FILE' 'services.mysql.environment') -cne '/run/secrets/MYSQL_ROOT_PASSWORD') {
    throw 'MySQL must consume both passwords through the official _FILE environment variables.'
}
$mysqlDatabase = [string](Get-RequiredProperty $mysqlEnvironment 'MYSQL_DATABASE' 'services.mysql.environment')
$mysqlUser = [string](Get-RequiredProperty $mysqlEnvironment 'MYSQL_USER' 'services.mysql.environment')
if ($mysqlDatabase -cnotmatch '^[A-Za-z0-9_]{1,64}$' -or $mysqlUser -cnotmatch '^[A-Za-z0-9_]{1,32}$' -or $mysqlUser -ceq 'root') {
    throw 'MySQL database/user identifiers must be bounded identifiers and the application user must not be root.'
}
$expectedMysqlUrl = "jdbc:mysql://mysql:3306/${mysqlDatabase}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
if ([string](Get-RequiredProperty $apiEnvironment 'MYSQL_URL' 'services.api.environment') -cne $expectedMysqlUrl -or
    [string](Get-RequiredProperty $apiEnvironment 'MYSQL_USER' 'services.api.environment') -cne $mysqlUser) {
    throw 'API MySQL URL/user must target the approved internal mysql service and match the MySQL service identity.'
}
if ([string](Get-RequiredProperty $mysqlEnvironment 'TZ' 'services.mysql.environment') -cne 'UTC') {
    throw 'MySQL TZ must remain locked to UTC.'
}

$redis = Get-RequiredProperty $services 'redis' 'services'
$redisEntrypoint = @((Get-RequiredProperty $redis 'entrypoint' 'services.redis')) -join "`n"
foreach ($requiredFragment in @(
    "tr -d '\r\n'",
    'sha256sum',
    'resetkeys resetchannels ~pms3:* &pms3:* nocommands -@dangerous -@admin',
    'aclfile /run/pms-redis/users.acl',
    'chown redis:redis /run/pms-redis ',
    'chmod 0700 /run/pms-redis',
    'unset password password_sha256'
)) {
    if (-not $redisEntrypoint.Contains($requiredFragment, [StringComparison]::Ordinal)) {
        throw "Redis entrypoint is missing required credential protection: $requiredFragment"
    }
}
$redisAclMatch = [regex]::Match(
    $redisEntrypoint,
    "printf 'user default on #%s (?<policy>.+?)\\n'",
    [Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $redisAclMatch.Success) {
    throw 'Redis entrypoint must generate exactly one explicit default-user ACL policy.'
}
$actualRedisAclTokens = @($redisAclMatch.Groups['policy'].Value.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))
$expectedRedisAclTokens = @(
    'resetkeys', 'resetchannels', '~pms3:*', '&pms3:*', 'nocommands', '-@dangerous', '-@admin',
    '+auth', '+hello', '+ping', '+info', '+quit', '+select', '+client|setname', '+client|setinfo',
    '+get', '+mget', '+set', '+mset', '+setnx', '+getdel', '+getex', '+del', '+unlink', '+exists',
    '+expire', '+pexpire', '+expireat', '+pexpireat', '+persist', '+ttl', '+pttl',
    '+incr', '+incrby', '+incrbyfloat', '+decr', '+decrby',
    '+hget', '+hgetall', '+hmget', '+hset', '+hsetnx', '+hdel', '+hexists', '+hlen', '+hincrby', '+hincrbyfloat',
    '+lpush', '+rpush', '+lpop', '+rpop', '+lrange', '+llen', '+lrem', '+ltrim',
    '+sadd', '+srem', '+smembers', '+sismember', '+scard',
    '+zadd', '+zrem', '+zrange', '+zrangebyscore', '+zrevrange', '+zscore', '+zcard', '+zcount',
    '+multi', '+exec', '+discard', '+watch', '+unwatch'
)
Assert-ExactStringSet $actualRedisAclTokens $expectedRedisAclTokens 'Redis generated ACL policy'
foreach ($forbiddenAclFragment in @('~*', '&*', '+@all', '+@admin', '+@dangerous', '+publish', '+subscribe', '+psubscribe', '+ssubscribe')) {
    if ($redisEntrypoint.Contains($forbiddenAclFragment, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Redis entrypoint contains forbidden ACL expansion: $forbiddenAclFragment"
    }
}
$redisTmpfs = Get-RequiredProperty $redis 'tmpfs' 'services.redis'
if (@($redisTmpfs | Where-Object { $_ -is [string] -and $_ -match '^/run/pms-redis:' }).Count -ne 1) {
    throw 'Redis generated ACL/configuration must be stored on /run/pms-redis tmpfs.'
}

$expectedServiceSecrets = @{
    mysql = @{
        pms_mysql_password = 'MYSQL_PASSWORD'
        pms_mysql_root_password = 'MYSQL_ROOT_PASSWORD'
    }
    redis = @{
        pms_redis_password = 'REDIS_PASSWORD'
    }
    api = @{
        pms_mysql_password = 'MYSQL_PASSWORD'
        pms_redis_password = 'REDIS_PASSWORD'
        pms_jwt_secret = 'JWT_SECRET'
        pms_callback_signing_secret = 'PMS_CALLBACK_SIGNING_SECRET'
        pms_bootstrap_admin_password = 'PMS_BOOTSTRAP_ADMIN_PASSWORD'
    }
    web = @{}
}
foreach ($serviceName in $requiredServiceNames) {
    $service = Get-RequiredProperty $services $serviceName 'services'
    Assert-ServiceSecretMounts $service $serviceName ($expectedServiceSecrets[$serviceName])
}

$secretDefinitions = Get-RequiredProperty $compose 'secrets' 'compose'
$expectedSecretFiles = [ordered]@{
    pms_mysql_password = 'MYSQL_PASSWORD'
    pms_mysql_root_password = 'MYSQL_ROOT_PASSWORD'
    pms_redis_password = 'REDIS_PASSWORD'
    pms_jwt_secret = 'JWT_SECRET'
    pms_callback_signing_secret = 'PMS_CALLBACK_SIGNING_SECRET'
    pms_bootstrap_admin_password = 'PMS_BOOTSTRAP_ADMIN_PASSWORD'
}
if (@($secretDefinitions.PSObject.Properties.Name).Count -ne $expectedSecretFiles.Count) {
    throw 'Production Compose must define exactly the six approved file-backed secrets.'
}

$configuredSecretPaths = @{}
foreach ($secretName in $expectedSecretFiles.Keys) {
    $definition = Get-RequiredProperty $secretDefinitions $secretName 'secrets'
    if ($null -ne (Get-OptionalProperty $definition 'external')) {
        throw "Secret '$secretName' must be file-backed for ordinary Docker Compose, not external."
    }
    $configuredFile = Get-RequiredProperty $definition 'file' "secrets.$secretName"
    if ($configuredFile -isnot [string] -or -not [IO.Path]::IsPathFullyQualified($configuredFile)) {
        throw "secrets.$secretName.file must resolve to an absolute path."
    }
    $configuredPath = Get-NormalizedFullPath $configuredFile
    $expectedPath = Get-NormalizedFullPath (Join-Path $secretDirectory $expectedSecretFiles[$secretName])
    if (-not [string]::Equals($configuredPath, $expectedPath, $pathComparison)) {
        throw "secrets.$secretName.file must be '$expectedPath', got '$configuredPath'."
    }
    $configuredParent = Get-NormalizedFullPath ([IO.Path]::GetDirectoryName($configuredPath))
    if (-not [string]::Equals($configuredParent, $secretDirectory, $pathComparison)) {
        throw "All production secret files must be direct children of PMS_SECRET_DIR: $secretDirectory"
    }
    if (Test-PathWithin $configuredPath $repositoryRoot) {
        throw "Secret '$secretName' resolves inside the repository: $configuredPath"
    }
    $configuredSecretPaths[$secretName] = $configuredPath
}

if (-not $SkipSecretFileContentChecks) {
    if (-not (Test-Path -LiteralPath $secretDirectory -PathType Container)) {
        throw "PMS_SECRET_DIR does not exist: $secretDirectory"
    }
    Assert-NoReparsePoint $secretDirectory 'PMS_SECRET_DIR'
    $resolvedSecretDirectory = Get-NormalizedFullPath ((Resolve-Path -LiteralPath $secretDirectory).Path)
    if (Test-PathWithin $resolvedSecretDirectory $repositoryRoot) {
        throw "PMS_SECRET_DIR resolves inside the repository: $resolvedSecretDirectory"
    }
    if ($runningOnWindows) {
        Assert-RestrictedWindowsAcl $secretDirectory 'PMS_SECRET_DIR'
    } else {
        Assert-StrictUnixDirectoryMode $secretDirectory 'PMS_SECRET_DIR'
    }

    foreach ($secretName in $expectedSecretFiles.Keys) {
        $secretPath = $configuredSecretPaths[$secretName]
        if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) {
            throw "Required secret file is missing: $secretPath"
        }
        $secretItem = Get-Item -Force -LiteralPath $secretPath
        if ($secretItem.Length -le 0) { throw "Required secret file is empty: $secretPath" }
        Assert-NoReparsePoint $secretPath "Secret '$secretName'"
        $resolvedSecretPath = Get-NormalizedFullPath ((Resolve-Path -LiteralPath $secretPath).Path)
        $resolvedParent = Get-NormalizedFullPath ([IO.Path]::GetDirectoryName($resolvedSecretPath))
        if (-not [string]::Equals($resolvedParent, $resolvedSecretDirectory, $pathComparison) -or
            (Test-PathWithin $resolvedSecretPath $repositoryRoot)) {
            throw "Secret '$secretName' must resolve directly below PMS_SECRET_DIR and outside the repository."
        }
        if ($runningOnWindows) {
            Assert-RestrictedWindowsAcl $secretPath "Secret '$secretName'"
            Assert-ReadOnlyWindowsSecret $secretPath "Secret '$secretName'"
        } else {
            Assert-ReadOnlyUnixSecretMode $secretPath "Secret '$secretName'"
        }
    }

    $secretTexts = @{}
    foreach ($secretName in $expectedSecretFiles.Keys) {
        $secretTexts[$secretName] = Get-StrictUtf8SecretText $configuredSecretPaths[$secretName] "Secret '$secretName'"
    }

    $bootstrapUsername = Get-RequiredProperty $apiEnvironment 'PMS_BOOTSTRAP_ADMIN_USERNAME' 'services.api.environment'
    if ($bootstrapUsername -isnot [string]) {
        throw 'services.api.environment.PMS_BOOTSTRAP_ADMIN_USERNAME must be a string.'
    }
    Assert-StrongBootstrapPassword $bootstrapUsername $secretTexts['pms_bootstrap_admin_password']

    $jwtSecret = $secretTexts['pms_jwt_secret']
    $callbackSecret = $secretTexts['pms_callback_signing_secret']
    if ($jwtSecret.Length -lt 32) {
        throw 'JWT_SECRET must contain at least 32 Java String-compatible characters.'
    }
    if ($callbackSecret.Length -lt 32) {
        throw 'PMS_CALLBACK_SIGNING_SECRET must contain at least 32 Java String-compatible characters.'
    }
    if ($jwtSecret -ceq $callbackSecret) {
        throw 'JWT_SECRET and PMS_CALLBACK_SIGNING_SECRET must be different.'
    }
    if ($secretTexts['pms_mysql_password'] -ceq $secretTexts['pms_mysql_root_password']) {
        throw 'MYSQL_PASSWORD and MYSQL_ROOT_PASSWORD must be different.'
    }

    $redisSecretBytes = [IO.File]::ReadAllBytes($configuredSecretPaths['pms_redis_password'])
    if ($redisSecretBytes -contains [byte]0 -or $redisSecretBytes -contains [byte]10 -or $redisSecretBytes -contains [byte]13) {
        throw 'REDIS_PASSWORD must not contain NUL, CR, or LF bytes.'
    }
}

if ($ConfigOnly) {
    [pscustomobject]@{
        Status = 'PASSED'
        Mode = 'CONFIG_ONLY'
        Services = $requiredServiceNames.Count
        SecretFilesChecked = -not $SkipSecretFileContentChecks
    }
    return
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    throw 'EvidencePath is required for pre-deploy and deployed-image validation.'
}
$normalizedEvidencePath = Get-NormalizedFullPath $EvidencePath
if (-not (Test-Path -LiteralPath $normalizedEvidencePath -PathType Leaf)) {
    throw "Container scan evidence is missing: $normalizedEvidencePath"
}
Assert-NoReparsePoint $normalizedEvidencePath 'Container scan evidence'
$resolvedEvidencePath = Get-NormalizedFullPath ((Resolve-Path -LiteralPath $normalizedEvidencePath).Path)
$evidenceDirectory = Get-NormalizedFullPath ([IO.Path]::GetDirectoryName($resolvedEvidencePath))
try {
    $evidence = Get-Content -Raw -LiteralPath $normalizedEvidencePath | ConvertFrom-Json
} catch {
    throw "Container scan evidence is not valid JSON: $($_.Exception.Message)"
}

$schemaVersion = Get-RequiredProperty $evidence 'SchemaVersion' 'evidence'
if (-not (Test-IsInteger $schemaVersion) -or [int64]$schemaVersion -ne 3) {
    throw 'evidence.SchemaVersion must be integer 3.'
}
$generatedAt = Get-RequiredTimestamp $evidence 'GeneratedAtUtc' 'evidence'
$now = [DateTimeOffset]::UtcNow
$futureTolerance = $now.AddMinutes(5)
if ($generatedAt -gt $futureTolerance) { throw 'evidence.GeneratedAtUtc is unreasonably in the future.' }

$trivyImage = Get-RequiredProperty $evidence 'TrivyImage' 'evidence'
if ($trivyImage -isnot [string] -or $trivyImage -cne $requiredTrivyImage) {
    throw "evidence.TrivyImage must equal the approved scanner digest: $requiredTrivyImage"
}
$trivyVersion = Get-RequiredProperty $evidence 'TrivyVersion' 'evidence'
if ($trivyVersion -isnot [string] -or $trivyVersion -notmatch '(?m)^Version:\s+v?\d+\.\d+\.\d+(?:\s|$)') {
    throw 'evidence.TrivyVersion is missing a valid semantic version line.'
}
$rawTrivyVersionMatch = [regex]::Match($trivyVersion, '(?m)^Version:\s*(\S+)\s*$')
if (-not $rawTrivyVersionMatch.Success) {
    throw 'evidence.TrivyVersion cannot be mapped to raw-report scanner metadata.'
}
$expectedRawTrivyVersion = $rawTrivyVersionMatch.Groups[1].Value

$databaseDownloadTimes = @{}
foreach ($databaseName in @('VulnerabilityDatabase', 'JavaDatabase')) {
    $database = Get-RequiredProperty $evidence $databaseName 'evidence'
    $databaseVersion = Get-RequiredProperty $database 'Version' "evidence.$databaseName"
    if (-not (Test-IsInteger $databaseVersion) -or [int64]$databaseVersion -le 0) {
        throw "evidence.$databaseName.Version must be a positive integer."
    }
    $updatedAt = Get-RequiredTimestamp $database 'UpdatedAt' "evidence.$databaseName"
    $downloadedAt = Get-RequiredTimestamp $database 'DownloadedAt' "evidence.$databaseName"
    $nextUpdate = Get-RequiredTimestamp $database 'NextUpdate' "evidence.$databaseName"
    if ($updatedAt -gt $futureTolerance -or $downloadedAt -gt $futureTolerance) {
        throw "evidence.$databaseName contains a timestamp unreasonably in the future."
    }
    if ($downloadedAt -lt $updatedAt) {
        throw "evidence.$databaseName.DownloadedAt precedes UpdatedAt."
    }
    if ($nextUpdate -le $updatedAt -or $nextUpdate -le $now) {
        throw "evidence.$databaseName is stale or has an invalid NextUpdate."
    }
    if ($generatedAt -lt $downloadedAt -or $generatedAt -ge $nextUpdate) {
        throw "evidence.GeneratedAtUtc must fall after $databaseName download and before its NextUpdate."
    }
    $databaseDownloadTimes[$databaseName] = $downloadedAt
}

$evidenceImagesValue = Get-RequiredProperty $evidence 'Images' 'evidence'
if ($evidenceImagesValue -isnot [System.Array]) {
    throw 'evidence.Images must be a JSON array.'
}
$evidenceImages = @($evidenceImagesValue)
if ($evidenceImages.Count -ne $requiredServiceNames.Count) {
    throw "evidence.Images must contain exactly $($requiredServiceNames.Count) entries."
}

$validatedEvidenceImages = foreach ($index in 0..($evidenceImages.Count - 1)) {
    $item = $evidenceImages[$index]
    $context = "evidence.Images[$index]"
    $image = Get-RequiredProperty $item 'Image' $context
    $imageId = Get-RequiredProperty $item 'ImageId' $context
    $repoDigests = Get-RequiredProperty $item 'RepoDigests' $context
    $platform = Get-RequiredProperty $item 'Platform' $context
    $critical = Get-RequiredProperty $item 'Critical' $context
    $high = Get-RequiredProperty $item 'High' $context
    $reportFile = Get-RequiredProperty $item 'ReportFile' $context
    $reportSha256 = Get-RequiredProperty $item 'ReportSha256' $context
    $reportCreatedAtUtc = Get-RequiredProperty $item 'ReportCreatedAtUtc' $context
    $rawSchemaVersion = Get-RequiredProperty $item 'RawSchemaVersion' $context
    $summaryTargets = Get-RequiredProperty $item 'Targets' $context

    if ($image -isnot [string] -or [string]::IsNullOrWhiteSpace($image) -or $image -match '\s') {
        throw "$context.Image must be a non-empty image reference without whitespace."
    }
    if ($imageId -isnot [string] -or $imageId -cnotmatch $imageIdPattern) {
        throw "$context.ImageId must be sha256:<64 lower-case hex>."
    }
    if ($repoDigests -isnot [System.Array]) { throw "$context.RepoDigests must be a JSON array." }
    foreach ($repoDigest in $repoDigests) {
        if ($repoDigest -isnot [string] -or $repoDigest -cnotmatch $manifestDigestPattern) {
            throw "$context.RepoDigests contains an invalid manifest digest."
        }
    }
    if ($platform -isnot [string] -or $platform -cnotmatch $platformPattern) {
        throw "$context.Platform is not a valid os/architecture[/variant] string."
    }
    if ($platform -cne $targetPlatform) {
        throw "$context.Platform must equal PMS_TARGET_PLATFORM ($targetPlatform)."
    }
    if (-not (Test-IsInteger $critical) -or [int64]$critical -lt 0) {
        throw "$context.Critical must be a non-negative integer."
    }
    if (-not (Test-IsInteger $high) -or [int64]$high -lt 0) {
        throw "$context.High must be a non-negative integer."
    }

    if ($reportFile -isnot [string] -or [string]::IsNullOrWhiteSpace($reportFile) -or
        $reportFile -cnotmatch '^[a-z0-9][a-z0-9_.-]*\.json$' -or
        $reportFile -cne [IO.Path]::GetFileName($reportFile) -or
        $reportFile -in @('.', '..', 'summary.json') -or
        [IO.Path]::GetExtension($reportFile) -cne '.json') {
        throw "$context.ReportFile must be a safe relative JSON basename."
    }
    if ($reportSha256 -isnot [string] -or $reportSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw "$context.ReportSha256 must be 64 lower-case hexadecimal characters."
    }
    if (-not (Test-IsInteger $rawSchemaVersion) -or [int64]$rawSchemaVersion -ne 2) {
        throw "$context.RawSchemaVersion must be integer 2."
    }
    if ($summaryTargets -isnot [System.Array] -or @($summaryTargets).Count -eq 0) {
        throw "$context.Targets must be a non-empty JSON array."
    }
    $parsedReportCreatedAt = Get-RequiredTimestamp $item 'ReportCreatedAtUtc' $context
    if ($parsedReportCreatedAt -lt $databaseDownloadTimes['VulnerabilityDatabase'] -or
        $parsedReportCreatedAt -lt $databaseDownloadTimes['JavaDatabase'] -or
        $parsedReportCreatedAt -gt $generatedAt) {
        throw "$context.ReportCreatedAtUtc must fall after both database downloads and no later than evidence.GeneratedAtUtc."
    }

    $rawReportPath = Get-NormalizedFullPath (Join-Path $evidenceDirectory $reportFile)
    if (-not [string]::Equals((Get-NormalizedFullPath ([IO.Path]::GetDirectoryName($rawReportPath))), $evidenceDirectory, $pathComparison) -or
        -not (Test-Path -LiteralPath $rawReportPath -PathType Leaf)) {
        throw "$context raw report must exist directly beside the evidence summary: $reportFile"
    }
    Assert-NoReparsePoint $rawReportPath "$context raw report"

    $requiresJava = $image -ceq $configuredImages['api'] -or @($repoDigests) -ccontains $configuredImages['api']
    $rawValidation = Test-TrivyRawReport `
        -ReportPath $rawReportPath `
        -Image $image `
        -ExpectedImageId $imageId `
        -ExpectedPlatform $platform `
        -ExpectedRepoDigests @($repoDigests) `
        -ExpectedTrivyVersion $expectedRawTrivyVersion `
        -RequireJava:$requiresJava
    if ($rawValidation.ReportSha256 -cne $reportSha256) {
        throw "$context.ReportSha256 does not match the raw report."
    }
    if ($rawValidation.CreatedAtUtc -cne [string]$reportCreatedAtUtc) {
        throw "$context.ReportCreatedAtUtc does not exactly match the raw report."
    }
    if ($rawValidation.RawSchemaVersion -ne [int64]$rawSchemaVersion) {
        throw "$context.RawSchemaVersion does not match the raw report."
    }
    if ($rawValidation.Critical -ne [int64]$critical -or $rawValidation.High -ne [int64]$high) {
        throw "$context vulnerability counts do not match the raw report."
    }
    if (@($summaryTargets).Count -ne @($rawValidation.Targets).Count) {
        throw "$context.Targets count does not match the raw report."
    }
    for ($targetIndex = 0; $targetIndex -lt @($summaryTargets).Count; $targetIndex++) {
        $expectedTarget = $summaryTargets[$targetIndex]
        $actualTarget = $rawValidation.Targets[$targetIndex]
        $targetContext = "$context.Targets[$targetIndex]"
        foreach ($targetProperty in @('Target', 'Class', 'Type', 'PackageCount')) {
            $expectedTargetValue = Get-RequiredProperty $expectedTarget $targetProperty $targetContext
            if ($targetProperty -ceq 'PackageCount') {
                if (-not (Test-IsInteger $expectedTargetValue) -or [int64]$expectedTargetValue -ne [int64]$actualTarget.$targetProperty) {
                    throw "$targetContext.$targetProperty does not match the raw report."
                }
            } elseif ($expectedTargetValue -isnot [string] -or $expectedTargetValue -cne [string]$actualTarget.$targetProperty) {
                throw "$targetContext.$targetProperty does not match the raw report."
            }
        }
    }

    [pscustomobject]@{
        Image = $image
        ImageId = $imageId
        RepoDigests = @($repoDigests)
        Platform = $platform
        Critical = [int64]$critical
        High = [int64]$high
        ReportFile = $reportFile
        ReportSha256 = $reportSha256
        Targets = @($rawValidation.Targets)
    }
}
$validatedEvidenceImages = @($validatedEvidenceImages)
if (@($validatedEvidenceImages.ImageId | Select-Object -Unique).Count -ne $validatedEvidenceImages.Count) {
    throw 'evidence.Images must contain distinct local image IDs.'
}

$matchedEvidence = @{}
foreach ($serviceName in $requiredServiceNames) {
    $configuredImage = $configuredImages[$serviceName]
    $matches = @($validatedEvidenceImages | Where-Object {
        $_.Image -ceq $configuredImage -or @($_.RepoDigests) -ccontains $configuredImage
    })
    if ($matches.Count -ne 1) {
        throw "Production image '$configuredImage' for '$serviceName' must match exactly one scan evidence entry."
    }
    if ($matches[0].Critical -ne 0 -or $matches[0].High -ne 0) {
        throw "Production image '$configuredImage' failed the vulnerability gate: Critical=$($matches[0].Critical), High=$($matches[0].High)."
    }
    $matchedEvidence[$serviceName] = $matches[0]
}

# Resolve a multi-architecture manifest on the target daemon and prove that the
# child image selected for this host is the exact image that was scanned.
$pulledImages = @{}
foreach ($serviceName in $requiredServiceNames) {
    $configuredImage = $configuredImages[$serviceName]
    & docker pull --platform $targetPlatform $configuredImage | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to pull production image '$configuredImage' for platform $targetPlatform."
    }
    $localImage = Get-DockerImageInspection $configuredImage "localImage.$serviceName"
    if ($localImage.Platform -cne $targetPlatform) {
        throw "Pulled '$serviceName' image platform does not match PMS_TARGET_PLATFORM ($targetPlatform)."
    }
    if ($localImage.ImageId -cne $matchedEvidence[$serviceName].ImageId) {
        throw "Pulled '$serviceName' child image ID does not match scan evidence."
    }
    $pulledImages[$serviceName] = $localImage
}

if ($VerifyRunningContainers) {
    foreach ($networkName in $requiredNetworkNames) {
        $dockerNetworkName = $configuredNetworkNames[$networkName]
        $networkInspectOutput = (& docker network inspect --format '{{json .}}' $dockerNetworkName) -join "`n"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($networkInspectOutput)) {
            throw "Unable to inspect production network '$dockerNetworkName'."
        }
        $networkInspection = $networkInspectOutput | ConvertFrom-Json
        $networkDriver = Get-RequiredProperty $networkInspection 'Driver' "network.$networkName"
        $networkInternal = Get-RequiredProperty $networkInspection 'Internal' "network.$networkName"
        $networkAttachable = Get-RequiredProperty $networkInspection 'Attachable' "network.$networkName"
        $networkScope = Get-RequiredProperty $networkInspection 'Scope' "network.$networkName"
        $networkLabels = Get-RequiredProperty $networkInspection 'Labels' "network.$networkName"
        if ($networkDriver -cne 'bridge' -or $networkScope -cne 'local' -or
            $networkAttachable -isnot [bool] -or $networkAttachable) {
            throw "Production network '$dockerNetworkName' must be a non-attachable local bridge."
        }
        $expectedInternal = $networkName -cin @('frontend', 'data')
        if ($networkInternal -isnot [bool] -or $networkInternal -ne $expectedInternal) {
            throw "Production network '$dockerNetworkName' has an unexpected internal-routing state."
        }
        if ((Get-RequiredProperty $networkLabels 'com.docker.compose.project' "network.$networkName.Labels") -cne $ProjectName -or
            (Get-RequiredProperty $networkLabels 'com.docker.compose.network' "network.$networkName.Labels") -cne $networkName) {
            throw "Production network '$dockerNetworkName' is not owned by the expected Compose project/logical network."
        }
    }

    foreach ($serviceName in $requiredServiceNames) {
        $containerIds = @(
            (& docker compose --project-name $ProjectName -f $composePath ps --all --quiet $serviceName) |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        if ($LASTEXITCODE -ne 0 -or $containerIds.Count -ne 1) {
            throw "Expected exactly one container for production service '$serviceName'."
        }
        $inspectOutput = (& docker inspect --format '{{json .}}' $containerIds[0]) -join "`n"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($inspectOutput)) {
            throw "Unable to inspect the production '$serviceName' container."
        }
        $container = $inspectOutput | ConvertFrom-Json
        Assert-RunningContainerPrivilegeBoundary $container $serviceName
        Assert-RunningContainerMountBoundary `
            -Container $container `
            -ServiceName $serviceName `
            -ExpectedPersistentVolumes ($expectedServiceVolumes[$serviceName]) `
            -ConfiguredVolumeNames $configuredVolumeNames `
            -ExpectedSecrets ($expectedServiceSecrets[$serviceName]) `
            -ConfiguredSecretPaths $configuredSecretPaths
        $containerConfig = Get-RequiredProperty $container 'Config' "container.$serviceName"
        Assert-RunningProcessContract `
            -ContainerConfig $containerConfig `
            -ImageConfig $pulledImages[$serviceName].Config `
            -Service (Get-RequiredProperty $services $serviceName 'services') `
            -ServiceName $serviceName
        Assert-RunningEnvironmentContract `
            -ContainerConfig $containerConfig `
            -ImageConfig $pulledImages[$serviceName].Config `
            -Service (Get-RequiredProperty $services $serviceName 'services') `
            -ServiceName $serviceName
        $configuredReference = Get-RequiredProperty $containerConfig 'Image' "container.$serviceName.Config"
        $runningImageId = Get-RequiredProperty $container 'Image' "container.$serviceName"
        $networkSettings = Get-RequiredProperty $container 'NetworkSettings' "container.$serviceName"
        $containerNetworks = Get-RequiredProperty $networkSettings 'Networks' "container.$serviceName.NetworkSettings"
        $expectedRunningNetworks = @($expectedServiceNetworks[$serviceName] | ForEach-Object { $configuredNetworkNames[$_] })
        Assert-ExactStringSet @($containerNetworks.PSObject.Properties.Name) $expectedRunningNetworks "container.$serviceName networks"
        $state = Get-RequiredProperty $container 'State' "container.$serviceName"
        $status = Get-RequiredProperty $state 'Status' "container.$serviceName.State"
        $health = Get-RequiredProperty $state 'Health' "container.$serviceName.State"
        $healthStatus = Get-RequiredProperty $health 'Status' "container.$serviceName.State.Health"

        if ($configuredReference -cne $configuredImages[$serviceName]) {
            throw "Running '$serviceName' container reference does not match the approved manifest digest."
        }
        if ($runningImageId -cne $matchedEvidence[$serviceName].ImageId) {
            throw "Running '$serviceName' image ID does not match scan evidence."
        }
        $runningImage = Get-DockerImageInspection $runningImageId "runningImage.$serviceName"
        if ($runningImage.Platform -cne $targetPlatform) {
            throw "Running '$serviceName' image platform does not match PMS_TARGET_PLATFORM ($targetPlatform)."
        }
        if ($status -cne 'running' -or $healthStatus -cne 'healthy') {
            throw "Production service '$serviceName' is not running and healthy (status=$status, health=$healthStatus)."
        }

        $processStatus = (& docker exec $containerIds[0] cat /proc/1/status) -join "`n"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($processStatus)) {
            throw "Unable to read /proc/1/status for production service '$serviceName'."
        }
        $uidMatch = [regex]::Match($processStatus, '(?m)^Uid:\s+(\d+)(?:\s|$)')
        if (-not $uidMatch.Success) {
            throw "Production service '$serviceName' returned an invalid /proc/1/status Uid field."
        }
        if ([int64]$uidMatch.Groups[1].Value -eq 0) {
            throw "Production service '$serviceName' is running PID 1 as root (UID 0)."
        }
    }
}

[pscustomobject]@{
    Status = 'PASSED'
    Mode = if ($VerifyRunningContainers) { 'DEPLOYED' } else { 'PRE_DEPLOY' }
    Services = $requiredServiceNames.Count
    Critical = 0
    High = 0
    Evidence = $normalizedEvidencePath
}
