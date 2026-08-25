[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$environmentPath = Join-Path $repositoryRoot '.env'
$sensitiveValues = @{}
if (Test-Path -LiteralPath $environmentPath -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $environmentPath) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            if ($name -match '(?i)(PASSWORD|SECRET|TOKEN|PRIVATE|CREDENTIAL)' -and $value.Length -ge 8) {
                $sensitiveValues[$name] = $value
            }
        }
    }
}

Push-Location $repositoryRoot
try {
    $repositoryFiles = (& git ls-files -co --exclude-standard -z) -split "`0" | Where-Object { $_ } | Sort-Object -Unique
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate repository files.' }
    if ((& git ls-files --error-unmatch .env 2>$null)) { throw 'The local .env file is tracked by Git.' }
    $findings = [Collections.Generic.List[object]]::new()
    foreach ($relativePath in $repositoryFiles) {
        $path = Join-Path $repositoryRoot $relativePath
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        try { $content = [IO.File]::ReadAllText($path) } catch { continue }
        foreach ($entry in $sensitiveValues.GetEnumerator()) {
            if ($content.Contains([string]$entry.Value, [StringComparison]::Ordinal)) {
                $findings.Add([pscustomobject]@{ Path = $relativePath; Rule = "LOCAL_ENV_VALUE:$($entry.Key)" })
            }
        }
        if ($content -match '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----') {
            $findings.Add([pscustomobject]@{ Path = $relativePath; Rule = 'PRIVATE_KEY_BLOCK' })
        }
        if ($content -match '(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])') {
            $findings.Add([pscustomobject]@{ Path = $relativePath; Rule = 'AWS_ACCESS_KEY_ID' })
        }
        if ($content -match '(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{36,255}(?![A-Za-z0-9_])') {
            $findings.Add([pscustomobject]@{ Path = $relativePath; Rule = 'GITHUB_TOKEN' })
        }
    }
    if ($findings.Count -gt 0) {
        $findings | Format-Table -AutoSize | Out-String | Write-Error
        throw "Repository secret scan failed with $($findings.Count) finding(s)."
    }
    [pscustomobject]@{ Status = 'PASSED'; RepositoryFiles = $repositoryFiles.Count; Findings = 0; LocalSecretValuesCompared = $sensitiveValues.Count }
} finally {
    Pop-Location
}
