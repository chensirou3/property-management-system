[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactDirectory = Join-Path $repositoryRoot '.artifacts\sbom'
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

$backendBom = Join-Path $repositoryRoot 'apps\pms-api\target\bom.json'
if (-not (Test-Path -LiteralPath $backendBom -PathType Leaf)) {
    throw 'Backend SBOM is missing. Run Maven verify before generating the evidence bundle.'
}
$backendOutput = Join-Path $artifactDirectory 'pms-api.cyclonedx.json'
Copy-Item -LiteralPath $backendBom -Destination $backendOutput -Force

Push-Location (Join-Path $repositoryRoot 'apps\admin-web')
try {
    $frontendJson = (& npm sbom --sbom-format cyclonedx --sbom-type application) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($frontendJson)) {
        throw 'Frontend CycloneDX generation failed.'
    }
    [void]($frontendJson | ConvertFrom-Json)
    $frontendOutput = Join-Path $artifactDirectory 'admin-web.cyclonedx.json'
    [IO.File]::WriteAllText($frontendOutput, $frontendJson + "`n", [Text.UTF8Encoding]::new($false))
} finally {
    Pop-Location
}

[pscustomobject]@{
    Backend = $backendOutput
    BackendSha256 = (Get-FileHash -LiteralPath $backendOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    Frontend = $frontendOutput
    FrontendSha256 = (Get-FileHash -LiteralPath $frontendOutput -Algorithm SHA256).Hash.ToLowerInvariant()
}
