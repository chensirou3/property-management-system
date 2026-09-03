[CmdletBinding()]
param(
    [string]$FixtureRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$testRoot = $PSScriptRoot
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $testRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($FixtureRoot)) {
    $FixtureRoot = Join-Path $repositoryRoot '.artifacts\security\trivy'
}
. (Join-Path $repositoryRoot 'scripts\scan-container-images.ps1')

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$ExpectedMessage)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notmatch $ExpectedMessage) {
            throw "Expected error /$ExpectedMessage/, got: $($_.Exception.Message)"
        }
        return
    }
    throw "Expected action to throw /$ExpectedMessage/."
}

function Get-FixtureArguments {
    param([string]$Path, [string]$Image, [bool]$RequireJava)
    $json = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    $platform = "$($json.Metadata.ImageConfig.os)/$($json.Metadata.ImageConfig.architecture)"
    $variantProperty = $json.Metadata.ImageConfig.PSObject.Properties['variant']
    if ($null -ne $variantProperty -and -not [string]::IsNullOrWhiteSpace([string]$variantProperty.Value)) {
        $platform += "/$([string]$variantProperty.Value)"
    }
    return @{
        ReportPath = $Path
        Image = $Image
        ExpectedImageId = [string]$json.Metadata.ImageID
        ExpectedPlatform = $platform
        ExpectedRepoDigests = @($json.Metadata.RepoDigests)
        ExpectedTrivyVersion = [string]$json.Trivy.Version
        RequireJava = $RequireJava
    }
}

function Write-MutatedFixture {
    param([object]$Json, [string]$Path)
    $text = $Json | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($Path, $text + "`n", [Text.UTF8Encoding]::new($false))
}

Assert-Condition ($script:ContainerScanEvidenceSchemaVersion -eq 3) 'Container scan evidence schema must be v3.'

$cases = @(
    @{ File = 'pms3-replica-api-latest.json'; Image = 'pms3-replica-api:latest'; Java = $true },
    @{ File = 'pms3-replica-web-latest.json'; Image = 'pms3-replica-web:latest'; Java = $false },
    @{ File = 'pms3-replica-mysql-8.4-hardened.json'; Image = 'pms3-replica-mysql:8.4-hardened'; Java = $false },
    @{ File = 'redis-7.4-alpine.json'; Image = 'redis:7.4-alpine'; Java = $false }
)

$validated = 0
foreach ($case in $cases) {
    $path = Join-Path $FixtureRoot $case.File
    Assert-Condition (Test-Path -LiteralPath $path -PathType Leaf) "Missing required raw-report fixture: $path"
    $arguments = Get-FixtureArguments -Path $path -Image $case.Image -RequireJava $case.Java
    $result = Test-TrivyRawReport @arguments
    Assert-Condition ($result.ReportSha256 -cmatch '^[0-9a-f]{64}$') "Invalid fixture SHA-256: $($case.File)"
    Assert-Condition ($result.ReportSha256 -ceq (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()) "Fixture SHA-256 mismatch: $($case.File)"
    Assert-Condition (@($result.Targets | Where-Object Class -ceq 'os-pkgs').Count -eq 1) "Missing fixture OS target: $($case.File)"
    if ($case.Java) {
        Assert-Condition (@($result.Targets | Where-Object { $_.Class -ceq 'lang-pkgs' -and $_.Type -ceq 'jar' }).Count -gt 0) "Missing fixture JAR target: $($case.File)"
    }
    $validated++
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("pms3-trivy-fixtures-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $sourcePath = Join-Path $FixtureRoot 'pms3-replica-api-latest.json'
    $baseArguments = Get-FixtureArguments -Path $sourcePath -Image 'pms3-replica-api:latest' -RequireJava $true

    $emptyResults = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
    $emptyResults.Results = @()
    $path = Join-Path $tempRoot 'empty-results.json'
    Write-MutatedFixture $emptyResults $path
    $arguments = $baseArguments.Clone(); $arguments.ReportPath = $path
    Assert-Throws { Test-TrivyRawReport @arguments } 'no analysis targets'

    $noPackages = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
    $noPackages.Results[0].Packages = @()
    $path = Join-Path $tempRoot 'no-packages.json'
    Write-MutatedFixture $noPackages $path
    $arguments = $baseArguments.Clone(); $arguments.ReportPath = $path
    Assert-Throws { Test-TrivyRawReport @arguments } 'no package inventory'

    $noJar = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
    $noJar.Results = @($noJar.Results | Where-Object Class -cne 'lang-pkgs')
    $path = Join-Path $tempRoot 'no-jar.json'
    Write-MutatedFixture $noJar $path
    $arguments = $baseArguments.Clone(); $arguments.ReportPath = $path
    Assert-Throws { Test-TrivyRawReport @arguments } 'no Java/JAR package target'

    $targetError = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
    $targetError.Results[0] | Add-Member -NotePropertyName Error -NotePropertyValue 'analysis failed'
    $path = Join-Path $tempRoot 'target-error.json'
    Write-MutatedFixture $targetError $path
    $arguments = $baseArguments.Clone(); $arguments.ReportPath = $path
    Assert-Throws { Test-TrivyRawReport @arguments } 'analysis failed'

    $badVulnerabilities = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
    $badVulnerabilities.Results[0] | Add-Member -NotePropertyName Vulnerabilities -NotePropertyValue ([pscustomobject]@{ VulnerabilityID = 'CVE-fixture'; Severity = 'HIGH' })
    $path = Join-Path $tempRoot 'scalar-vulnerabilities.json'
    Write-MutatedFixture $badVulnerabilities $path
    $arguments = $baseArguments.Clone(); $arguments.ReportPath = $path
    Assert-Throws { Test-TrivyRawReport @arguments } 'vulnerabilities must be an array'

    $wrongImage = $baseArguments.Clone(); $wrongImage.ExpectedImageId = 'sha256:' + ('0' * 64)
    Assert-Throws { Test-TrivyRawReport @wrongImage } 'artifact mismatch'

    $wrongPlatform = $baseArguments.Clone(); $wrongPlatform.ExpectedPlatform = 'linux/arm64'
    Assert-Throws { Test-TrivyRawReport @wrongPlatform } 'platform mismatch'

    $wrongDigests = $baseArguments.Clone(); $wrongDigests.ExpectedRepoDigests = @('example.invalid/pms3@sha256:' + ('0' * 64))
    Assert-Throws { Test-TrivyRawReport @wrongDigests } 'repository digests do not exactly match'
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot)
        $systemTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedTempRoot.StartsWith($systemTempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolvedTempRoot).StartsWith('pms3-trivy-fixtures-', [StringComparison]::Ordinal)) {
            throw "Refusing to remove unexpected fixture path: $resolvedTempRoot"
        }
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
}

[pscustomobject]@{
    Status = 'PASSED'
    ExistingRawReportsValidated = $validated
    NegativeFailClosedCases = 8
}
