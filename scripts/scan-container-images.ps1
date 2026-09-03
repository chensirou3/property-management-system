[CmdletBinding()]
param(
    [string[]]$Images = @(
        'property-management-system-api:latest',
        'property-management-system-web:latest',
        'property-management-system-mysql:8.4-hardened',
        'redis:7.4-alpine'
    ),
    [string[]]$JavaImages = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:ContainerScanEvidenceSchemaVersion = 3

function Get-RequiredJsonProperty {
    param(
        [Parameter(Mandatory)] [object]$InputObject,
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Context
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "$Context is missing required property '$Name'."
    }
    return $property.Value
}

function Test-TrivyRawReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string]$ReportPath,
        [Parameter(Mandatory)] [string]$Image,
        [Parameter(Mandatory)] [string]$ExpectedImageId,
        [Parameter(Mandatory)] [string]$ExpectedPlatform,
        [string[]]$ExpectedRepoDigests = @(),
        [string]$ExpectedTrivyVersion,
        [switch]$RequireJava,
        [Nullable[DateTimeOffset]]$NotOlderThanUtc
    )

    if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
        throw "Trivy report is missing for ${Image}: $ReportPath"
    }
    $reportBytes = [IO.File]::ReadAllBytes($ReportPath)
    if ($reportBytes.Length -eq 0) {
        throw "Trivy report is empty for ${Image}: $ReportPath"
    }
    try {
        $reportText = [Text.UTF8Encoding]::new($false, $true).GetString($reportBytes)
        $report = $reportText | ConvertFrom-Json
    } catch {
        throw "Trivy created invalid UTF-8 JSON for ${Image}: $($_.Exception.Message)"
    }
    if ($report -is [System.Array]) {
        throw "Trivy report root must be an object for $Image."
    }

    $rawSchemaVersion = Get-RequiredJsonProperty $report 'SchemaVersion' "Trivy report for $Image"
    if ($rawSchemaVersion -isnot [int] -and $rawSchemaVersion -isnot [long]) {
        throw "Trivy report SchemaVersion must be an integer for $Image."
    }
    if ([int64]$rawSchemaVersion -ne 2) {
        throw "Unsupported Trivy report SchemaVersion for ${Image}: $rawSchemaVersion"
    }
    $artifactType = [string](Get-RequiredJsonProperty $report 'ArtifactType' "Trivy report for $Image")
    if ($artifactType -cne 'container_image') {
        throw "Trivy report artifact type mismatch for ${Image}: expected container_image, got $artifactType."
    }
    $artifactName = [string](Get-RequiredJsonProperty $report 'ArtifactName' "Trivy report for $Image")
    if ($artifactName -cne $ExpectedImageId) {
        throw "Trivy report artifact mismatch for ${Image}: expected $ExpectedImageId, got $artifactName."
    }

    $createdAtValue = [string](Get-RequiredJsonProperty $report 'CreatedAt' "Trivy report for $Image")
    try {
        $createdAt = [DateTimeOffset]::Parse($createdAtValue, [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw "Trivy report CreatedAt is invalid for ${Image}: $createdAtValue"
    }
    if ($createdAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5)) {
        throw "Trivy report CreatedAt is in the future for ${Image}: $createdAt"
    }
    if ($null -ne $NotOlderThanUtc) {
        # PowerShell unwraps Nullable<T> parameter values to T when a value is
        # supplied, so accessing Nullable.Value fails for real scan invocations.
        $minimumCreatedAt = ([DateTimeOffset]$NotOlderThanUtc).AddMinutes(-5)
        if ($createdAt -lt $minimumCreatedAt) {
            throw "Trivy report is older than this scan invocation for ${Image}: $createdAt"
        }
    }

    $trivy = Get-RequiredJsonProperty $report 'Trivy' "Trivy report for $Image"
    $rawTrivyVersion = [string](Get-RequiredJsonProperty $trivy 'Version' "Trivy report scanner metadata for $Image")
    if ([string]::IsNullOrWhiteSpace($rawTrivyVersion)) {
        throw "Trivy report scanner version is empty for $Image."
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedTrivyVersion) -and $rawTrivyVersion -cne $ExpectedTrivyVersion) {
        throw "Trivy report scanner version mismatch for ${Image}: expected $ExpectedTrivyVersion, got $rawTrivyVersion."
    }

    $metadata = Get-RequiredJsonProperty $report 'Metadata' "Trivy report for $Image"
    $scannedImageId = [string](Get-RequiredJsonProperty $metadata 'ImageID' "Trivy report metadata for $Image")
    if ($scannedImageId -cne $ExpectedImageId) {
        throw "Trivy report image ID mismatch for ${Image}: expected $ExpectedImageId, got $scannedImageId."
    }
    $imageConfig = Get-RequiredJsonProperty $metadata 'ImageConfig' "Trivy report metadata for $Image"
    $configOs = [string](Get-RequiredJsonProperty $imageConfig 'os' "Trivy image config for $Image")
    $configArchitecture = [string](Get-RequiredJsonProperty $imageConfig 'architecture' "Trivy image config for $Image")
    $configVariantProperty = $imageConfig.PSObject.Properties['variant']
    $reportedPlatform = "$configOs/$configArchitecture"
    if ($null -ne $configVariantProperty -and -not [string]::IsNullOrWhiteSpace([string]$configVariantProperty.Value)) {
        $reportedPlatform += "/$([string]$configVariantProperty.Value)"
    }
    if ($reportedPlatform -cne $ExpectedPlatform) {
        throw "Trivy report platform mismatch for ${Image}: expected $ExpectedPlatform, got $reportedPlatform."
    }

    $rawRepoDigestsProperty = $metadata.PSObject.Properties['RepoDigests']
    $rawRepoDigests = @()
    if ($null -ne $rawRepoDigestsProperty -and $null -ne $rawRepoDigestsProperty.Value) {
        if ($rawRepoDigestsProperty.Value -isnot [System.Array]) {
            throw "Trivy report RepoDigests must be an array for $Image."
        }
        $rawRepoDigests = @($rawRepoDigestsProperty.Value | ForEach-Object { [string]$_ })
    }
    $expectedDigestSet = @($ExpectedRepoDigests | Sort-Object -CaseSensitive -Unique)
    $rawDigestSet = @($rawRepoDigests | Sort-Object -CaseSensitive -Unique)
    $digestDifferences = @()
    if ($expectedDigestSet.Count -gt 0 -and $rawDigestSet.Count -gt 0) {
        $digestDifferences = @(Compare-Object -ReferenceObject $expectedDigestSet -DifferenceObject $rawDigestSet -CaseSensitive)
    }
    if ($expectedDigestSet.Count -ne $rawDigestSet.Count -or $digestDifferences.Count -ne 0) {
        throw "Trivy report repository digests do not exactly match Docker inspection for $Image."
    }

    $osMetadata = Get-RequiredJsonProperty $metadata 'OS' "Trivy report metadata for $Image"
    $osFamily = [string](Get-RequiredJsonProperty $osMetadata 'Family' "Trivy OS metadata for $Image")
    $osName = [string](Get-RequiredJsonProperty $osMetadata 'Name' "Trivy OS metadata for $Image")
    if ([string]::IsNullOrWhiteSpace($osFamily) -or [string]::IsNullOrWhiteSpace($osName)) {
        throw "Trivy OS metadata is incomplete for $Image."
    }

    $resultsProperty = $report.PSObject.Properties['Results']
    if ($null -eq $resultsProperty -or $null -eq $resultsProperty.Value) {
        throw "Trivy report for $Image is missing required property 'Results'."
    }
    $results = $resultsProperty.Value
    if ($results -isnot [System.Array] -or @($results).Count -eq 0) {
        throw "Trivy report contains no analysis targets for $Image."
    }
    $targets = @()
    $findings = @()
    foreach ($result in @($results)) {
        if ($null -eq $result) {
            throw "Trivy report contains a null analysis target for $Image."
        }
        $target = [string](Get-RequiredJsonProperty $result 'Target' "Trivy result for $Image")
        $class = [string](Get-RequiredJsonProperty $result 'Class' "Trivy result '$target' for $Image")
        $type = [string](Get-RequiredJsonProperty $result 'Type' "Trivy result '$target' for $Image")
        if ([string]::IsNullOrWhiteSpace($target) -or [string]::IsNullOrWhiteSpace($class) -or
            [string]::IsNullOrWhiteSpace($type)) {
            throw "Trivy returned an incomplete analysis target for $Image."
        }
        if ($class -cnotin @('os-pkgs', 'lang-pkgs')) {
            throw "Trivy returned an unexpected vulnerability target class '$class' for $Image."
        }
        $errorProperty = $result.PSObject.Properties['Error']
        if ($null -ne $errorProperty -and -not [string]::IsNullOrWhiteSpace([string]$errorProperty.Value)) {
            throw "Trivy target '$target' failed for ${Image}: $($errorProperty.Value)"
        }
        $packagesProperty = $result.PSObject.Properties['Packages']
        if ($null -eq $packagesProperty -or $null -eq $packagesProperty.Value) {
            throw "Trivy target '$target' for $Image is missing required property 'Packages'."
        }
        $packages = $packagesProperty.Value
        if ($packages -isnot [System.Array] -or @($packages).Count -eq 0) {
            throw "Trivy target '$target' contains no package inventory for $Image; analysis completion cannot be proven."
        }
        foreach ($package in @($packages)) {
            if ($null -eq $package) {
                throw "Trivy target '$target' contains a null package for $Image."
            }
            $packageName = [string](Get-RequiredJsonProperty $package 'Name' "Trivy package in target '$target' for $Image")
            $packageVersion = [string](Get-RequiredJsonProperty $package 'Version' "Trivy package '$packageName' in target '$target' for $Image")
            if ([string]::IsNullOrWhiteSpace($packageName) -or [string]::IsNullOrWhiteSpace($packageVersion)) {
                throw "Trivy target '$target' contains incomplete package inventory for $Image."
            }
        }

        $vulnerabilityProperty = $result.PSObject.Properties['Vulnerabilities']
        if ($null -ne $vulnerabilityProperty -and $null -ne $vulnerabilityProperty.Value) {
            if ($vulnerabilityProperty.Value -isnot [System.Array]) {
                throw "Trivy vulnerabilities must be an array for target '$target' in $Image."
            }
            foreach ($finding in @($vulnerabilityProperty.Value)) {
                if ($null -eq $finding) {
                    throw "Trivy target '$target' contains a null vulnerability for $Image."
                }
                $vulnerabilityId = [string](Get-RequiredJsonProperty $finding 'VulnerabilityID' "Trivy finding in target '$target' for $Image")
                $severity = [string](Get-RequiredJsonProperty $finding 'Severity' "Trivy finding '$vulnerabilityId' for $Image")
                if ([string]::IsNullOrWhiteSpace($vulnerabilityId) -or $severity -cnotin @('CRITICAL', 'HIGH')) {
                    throw "Trivy returned an incomplete or unexpected vulnerability for ${Image}: ID='$vulnerabilityId', severity='$severity'."
                }
                $findings += $finding
            }
        }
        $targets += [pscustomobject]@{
            Target = $target
            Class = $class
            Type = $type
            PackageCount = @($packages).Count
        }
    }

    $osTargets = @($targets | Where-Object { $_.Class -ceq 'os-pkgs' })
    if ($osTargets.Count -ne 1) {
        throw "Trivy report must contain exactly one operating-system package target for ${Image}; found $($osTargets.Count)."
    }
    if ($osTargets[0].Type -cne $osFamily) {
        throw "Trivy OS target type does not match OS metadata for ${Image}: '$($osTargets[0].Type)' vs '$osFamily'."
    }
    $javaTargets = @($targets | Where-Object { $_.Class -ceq 'lang-pkgs' -and $_.Type -ceq 'jar' })
    if ($RequireJava -and $javaTargets.Count -eq 0) {
        throw "Trivy report contains no Java/JAR package target for API image $Image."
    }

    $sha256 = [Security.Cryptography.SHA256]::HashData($reportBytes)
    $reportSha256 = [Convert]::ToHexString($sha256).ToLowerInvariant()
    return [pscustomobject]@{
        ReportSha256 = $reportSha256
        CreatedAtUtc = $createdAt.ToUniversalTime().ToString('o')
        RawSchemaVersion = [int64]$rawSchemaVersion
        TrivyVersion = $rawTrivyVersion
        Targets = $targets
        Critical = @($findings | Where-Object Severity -ceq 'CRITICAL').Count
        High = @($findings | Where-Object Severity -ceq 'HIGH').Count
    }
}

if ($MyInvocation.InvocationName -eq '.') {
    return
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportRoot = Join-Path $repositoryRoot '.artifacts\security\trivy'
$cacheRoot = Join-Path $repositoryRoot '.artifacts\security\trivy-cache'
$trivyImage = 'aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
New-Item -ItemType Directory -Force -Path $reportRoot, $cacheRoot | Out-Null

$evidencePath = Join-Path $reportRoot 'summary.json'
Remove-Item -LiteralPath $evidencePath -Force -ErrorAction SilentlyContinue

if ($Images.Count -eq 0) { throw 'At least one image must be supplied.' }
if (@($Images | Sort-Object -Unique).Count -ne $Images.Count) {
    throw 'Image references must be unique.'
}
$reportNames = @($Images | ForEach-Object { (($_ -replace '[^a-zA-Z0-9_.-]', '-').ToLowerInvariant()) + '.json' })
if (@($reportNames | Sort-Object -CaseSensitive -Unique).Count -ne $reportNames.Count) {
    throw 'Image references must map to unique raw-report file names.'
}
if ($JavaImages.Count -eq 0) {
    $JavaImages = @($Images | Where-Object { $_ -match '(?i)(^|[/_.-])api(?=[:/@_.-]|$)' })
}
if ($JavaImages.Count -eq 0) {
    throw 'The API Java image could not be inferred; pass it explicitly with -JavaImages.'
}
foreach ($javaImage in $JavaImages) {
    if ($Images -cnotcontains $javaImage) {
        throw "Java image '$javaImage' is not present in -Images."
    }
}

$trivyVersion = ((& docker run --rm $trivyImage --version) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($trivyVersion)) {
    throw 'Unable to determine the pinned Trivy scanner version.'
}
$trivyVersionMatch = [regex]::Match($trivyVersion, '(?m)^Version:\s*(\S+)\s*$')
if (-not $trivyVersionMatch.Success) {
    throw "Unable to parse the pinned Trivy scanner version: $trivyVersion"
}
$rawTrivyVersion = $trivyVersionMatch.Groups[1].Value

$databaseArguments = @(
    'run', '--rm',
    '-v', "${cacheRoot}:/root/.cache/trivy",
    $trivyImage,
    'image', '--download-db-only'
)
& docker @databaseArguments
if ($LASTEXITCODE -ne 0) { throw 'Trivy vulnerability database refresh failed.' }

$javaDatabaseArguments = @(
    'run', '--rm',
    '-v', "${cacheRoot}:/root/.cache/trivy",
    $trivyImage,
    'image', '--download-java-db-only'
)
& docker @javaDatabaseArguments
if ($LASTEXITCODE -ne 0) { throw 'Trivy Java vulnerability database refresh failed.' }

$databaseMetadataPath = Join-Path $cacheRoot 'db\metadata.json'
if (-not (Test-Path -LiteralPath $databaseMetadataPath -PathType Leaf)) {
    throw "Trivy vulnerability database metadata is missing: $databaseMetadataPath"
}
$databaseMetadata = Get-Content -Raw -LiteralPath $databaseMetadataPath | ConvertFrom-Json
$databaseVersion = [int64]$databaseMetadata.Version
$databaseUpdatedAt = [DateTimeOffset]::Parse([string]$databaseMetadata.UpdatedAt)
$databaseDownloadedAt = [DateTimeOffset]::Parse([string]$databaseMetadata.DownloadedAt)
$databaseNextUpdate = [DateTimeOffset]::Parse([string]$databaseMetadata.NextUpdate)
if ($databaseVersion -le 0 -or $databaseDownloadedAt -lt $databaseUpdatedAt -or
    $databaseUpdatedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
    $databaseDownloadedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
    $databaseNextUpdate -le $databaseUpdatedAt -or $databaseNextUpdate -le [DateTimeOffset]::UtcNow) {
    throw "Trivy vulnerability database is stale; next update was $databaseNextUpdate."
}
$javaDatabaseMetadataPath = Join-Path $cacheRoot 'java-db\metadata.json'
if (-not (Test-Path -LiteralPath $javaDatabaseMetadataPath -PathType Leaf)) {
    throw "Trivy Java vulnerability database metadata is missing: $javaDatabaseMetadataPath"
}
$javaDatabaseMetadata = Get-Content -Raw -LiteralPath $javaDatabaseMetadataPath | ConvertFrom-Json
$javaDatabaseVersion = [int64]$javaDatabaseMetadata.Version
$javaDatabaseUpdatedAt = [DateTimeOffset]::Parse([string]$javaDatabaseMetadata.UpdatedAt)
$javaDatabaseDownloadedAt = [DateTimeOffset]::Parse([string]$javaDatabaseMetadata.DownloadedAt)
$javaDatabaseNextUpdate = [DateTimeOffset]::Parse([string]$javaDatabaseMetadata.NextUpdate)
if ($javaDatabaseVersion -le 0 -or $javaDatabaseDownloadedAt -lt $javaDatabaseUpdatedAt -or
    $javaDatabaseUpdatedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
    $javaDatabaseDownloadedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
    $javaDatabaseNextUpdate -le $javaDatabaseUpdatedAt -or $javaDatabaseNextUpdate -le [DateTimeOffset]::UtcNow) {
    throw "Trivy Java vulnerability database is stale; next update was $javaDatabaseNextUpdate."
}

$summary = foreach ($image in $Images) {
    $imageId = ((& docker image inspect $image --format '{{.Id}}') -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "Required image is missing or has an invalid image ID: $image"
    }
    $repoDigestsJson = ((& docker image inspect $image --format '{{json .RepoDigests}}') -join '').Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect repository digests for image: $image" }
    $repoDigests = @()
    if (-not [string]::IsNullOrWhiteSpace($repoDigestsJson) -and $repoDigestsJson -ne 'null') {
        $repoDigests = @($repoDigestsJson | ConvertFrom-Json)
    }
    $platform = ((& docker image inspect $image --format '{{.Os}}/{{.Architecture}}{{if .Variant}}/{{.Variant}}{{end}}') -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($platform)) {
        throw "Unable to inspect image platform: $image"
    }
    $slug = ($image -replace '[^a-zA-Z0-9_.-]', '-').ToLowerInvariant()
    $reportName = "$slug.json"
    $reportPath = Join-Path $reportRoot $reportName
    Remove-Item -LiteralPath $reportPath -Force -ErrorAction SilentlyContinue
    $scanStartedAt = [DateTimeOffset]::UtcNow
    $arguments = @(
        'run', '--rm',
        '-v', '/var/run/docker.sock:/var/run/docker.sock',
        '-v', "${cacheRoot}:/root/.cache/trivy",
        '-v', "${reportRoot}:/reports",
        $trivyImage,
        'image', '--skip-db-update', '--skip-java-db-update', '--scanners', 'vuln', '--list-all-pkgs', '--severity', 'CRITICAL,HIGH', '--format', 'json',
        '--output', "/reports/$reportName", $imageId
    )
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "Trivy execution failed for $image." }

    $isJavaImage = $JavaImages -ccontains $image
    $validation = Test-TrivyRawReport `
        -ReportPath $reportPath `
        -Image $image `
        -ExpectedImageId $imageId `
        -ExpectedPlatform $platform `
        -ExpectedRepoDigests $repoDigests `
        -ExpectedTrivyVersion $rawTrivyVersion `
        -RequireJava:$isJavaImage `
        -NotOlderThanUtc $scanStartedAt
    $currentImageId = ((& docker image inspect $image --format '{{.Id}}') -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $imageId -cne $currentImageId) {
        throw "Image reference changed while it was being scanned: $image"
    }
    [pscustomobject]@{
        Image = $image
        ImageId = $imageId
        RepoDigests = $repoDigests
        Platform = $platform
        Critical = $validation.Critical
        High = $validation.High
        ReportFile = $reportName
        ReportSha256 = $validation.ReportSha256
        ReportCreatedAtUtc = $validation.CreatedAtUtc
        RawSchemaVersion = $validation.RawSchemaVersion
        Targets = $validation.Targets
    }
}

$summary = @($summary)
$evidence = [ordered]@{
    SchemaVersion = $script:ContainerScanEvidenceSchemaVersion
    GeneratedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    TrivyImage = $trivyImage
    TrivyVersion = $trivyVersion
    VulnerabilityDatabase = [ordered]@{
        Version = $databaseVersion
        UpdatedAt = [string]$databaseMetadata.UpdatedAt
        DownloadedAt = [string]$databaseMetadata.DownloadedAt
        NextUpdate = [string]$databaseMetadata.NextUpdate
    }
    JavaDatabase = [ordered]@{
        Version = $javaDatabaseVersion
        UpdatedAt = [string]$javaDatabaseMetadata.UpdatedAt
        DownloadedAt = [string]$javaDatabaseMetadata.DownloadedAt
        NextUpdate = [string]$javaDatabaseMetadata.NextUpdate
    }
    Images = $summary
}
$evidenceJson = $evidence | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($evidencePath, $evidenceJson + "`n", [Text.UTF8Encoding]::new($false))

$summary | Format-Table Image, Critical, High, ImageId -AutoSize
$critical = ($summary | Measure-Object -Property Critical -Sum).Sum
$high = ($summary | Measure-Object -Property High -Sum).Sum
if ($critical -ne 0 -or $high -ne 0) {
    throw "Container vulnerability gate failed: Critical=$critical, High=$high."
}

[pscustomobject]@{
    Status = 'PASSED'
    Images = $summary.Count
    Critical = 0
    High = 0
    ReportDirectory = $reportRoot
    Evidence = $evidencePath
}
