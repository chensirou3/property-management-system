[CmdletBinding()]
param(
    [string]$OutputDirectory = '',
    [string]$ComposeFile = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot '.artifacts\backups'
}
if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $repositoryRoot 'docker-compose.yml'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$ComposeFile = [IO.Path]::GetFullPath($ComposeFile)
if (-not (Test-Path -LiteralPath $ComposeFile -PathType Leaf)) {
    throw "Compose file not found: $ComposeFile"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$containerId = (& docker compose -f $ComposeFile ps -q mysql).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw 'MySQL container is not running. Start the exact Compose stack before backup.'
}

$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$nonce = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$fileName = "pms3-$stamp-$nonce.sql"
$remotePath = "/tmp/$fileName"
$outputPath = Join-Path $OutputDirectory $fileName

try {
    & docker exec $containerId sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction --quick --routines --triggers --events --set-gtid-purged=OFF --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" > "$1"' sh $remotePath
    if ($LASTEXITCODE -ne 0) { throw 'mysqldump failed inside the MySQL container.' }
    & docker cp "${containerId}:$remotePath" $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'Copying the database backup from the MySQL container failed.' }
} finally {
    & docker exec $containerId sh -lc 'rm -f -- "$1"' sh $remotePath 2>$null
}

if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf) -or (Get-Item -LiteralPath $outputPath).Length -le 0) {
    throw 'The backup artifact is missing or empty.'
}
$checksum = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
$schemaVersion = (& docker exec $containerId sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" -N -B -e "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1"').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Reading the source schema version failed.' }

$metadata = [ordered]@{
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    artifact = $fileName
    sha256 = $checksum
    bytes = (Get-Item -LiteralPath $outputPath).Length
    schemaVersion = [int]$schemaVersion
    source = 'docker-compose:mysql'
    containsSecrets = $false
}
$metadataPath = "$outputPath.metadata.json"
$metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

[pscustomobject]@{
    BackupPath = $outputPath
    MetadataPath = $metadataPath
    Sha256 = $checksum
    Bytes = $metadata.bytes
    SchemaVersion = $metadata.schemaVersion
}
