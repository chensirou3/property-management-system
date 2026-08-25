[CmdletBinding()]
param(
    [string[]]$Images = @(
        'pms3-replica-api:latest',
        'pms3-replica-web:latest',
        'pms3-replica-mysql:8.4-hardened',
        'redis:7.4-alpine'
    )
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportRoot = Join-Path $repositoryRoot '.artifacts\security\trivy'
$cacheRoot = Join-Path $repositoryRoot '.artifacts\security\trivy-cache'
$trivyImage = 'aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
New-Item -ItemType Directory -Force -Path $reportRoot, $cacheRoot | Out-Null

$summary = foreach ($image in $Images) {
    & docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { throw "Required image is missing: $image" }
    $slug = ($image -replace '[^a-zA-Z0-9_.-]', '-').ToLowerInvariant()
    $reportName = "$slug.json"
    $arguments = @(
        'run', '--rm',
        '-v', '/var/run/docker.sock:/var/run/docker.sock',
        '-v', "${cacheRoot}:/root/.cache/trivy",
        '-v', "${reportRoot}:/reports",
        $trivyImage,
        'image', '--scanners', 'vuln', '--severity', 'CRITICAL,HIGH', '--format', 'json',
        '--output', "/reports/$reportName", $image
    )
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "Trivy execution failed for $image." }

    $reportPath = Join-Path $reportRoot $reportName
    $report = Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
    $findings = @(
        foreach ($result in @($report.Results)) {
            $vulnerabilities = $result.PSObject.Properties['Vulnerabilities']
            if ($null -ne $vulnerabilities -and $null -ne $vulnerabilities.Value) {
                @($vulnerabilities.Value)
            }
        }
    )
    [pscustomobject]@{
        Image = $image
        ImageId = (& docker image inspect $image --format '{{.Id}}').Trim()
        Critical = @($findings | Where-Object Severity -eq 'CRITICAL').Count
        High = @($findings | Where-Object Severity -eq 'HIGH').Count
        Report = $reportPath
    }
}

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
}
