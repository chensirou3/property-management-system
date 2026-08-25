[CmdletBinding()]
param(
    [string]$BackupFile = '',
    [string]$ComposeFile = '',
    [switch]$KeepRestoredDatabase
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $repositoryRoot 'docker-compose.yml'
}
$ComposeFile = [IO.Path]::GetFullPath($ComposeFile)
if (-not (Test-Path -LiteralPath $ComposeFile -PathType Leaf)) {
    throw "Compose file not found: $ComposeFile"
}

if ([string]::IsNullOrWhiteSpace($BackupFile)) {
    $backupResult = & (Join-Path $PSScriptRoot 'backup-mysql.ps1') -ComposeFile $ComposeFile
    $BackupFile = $backupResult.BackupPath
}
$BackupFile = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) {
    throw "Backup file not found: $BackupFile"
}
$backupChecksum = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Get-Item -LiteralPath $BackupFile).Length -le 0) { throw 'Backup file is empty.' }

$containerId = (& docker compose -f $ComposeFile ps -q mysql).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw 'MySQL container is not running. Start the exact Compose stack before the restore drill.'
}

$suffix = ([DateTime]::UtcNow.ToString('yyyyMMddHHmmss') + [Guid]::NewGuid().ToString('N').Substring(0, 6)).ToLowerInvariant()
$drillDatabase = "pms3_restore_drill_$suffix"
if ($drillDatabase -notmatch '^pms3_restore_drill_[a-z0-9]+$') { throw 'Unsafe restore database name.' }
$remoteName = "pms3-restore-$suffix.sql"
$remotePath = "/tmp/$remoteName"
$databaseCreated = $false

function Invoke-MySql([string]$Database, [string]$Sql) {
    if ($Database -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsafe database name supplied to restore drill.' }
    if ($Sql.Contains('"')) { throw 'Restore drill SQL must not contain a double quote.' }
    $command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "' + $Database + '" -N -B -e "' + $Sql + '"'
    $result = & docker exec $containerId sh -lc $command
    if ($LASTEXITCODE -ne 0) { throw "MySQL verification failed for database $Database." }
    return ($result -join "`n").Trim()
}

try {
    & docker cp $BackupFile "${containerId}:$remotePath"
    if ($LASTEXITCODE -ne 0) { throw 'Copying the backup into the MySQL container failed.' }

    $createCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "CREATE DATABASE ' + $drillDatabase + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
    & docker exec $containerId sh -lc $createCommand
    if ($LASTEXITCODE -ne 0) { throw 'Creating the isolated restore database failed.' }
    $databaseCreated = $true

    $importCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "' + $drillDatabase + '" < "' + $remotePath + '"'
    & docker exec $containerId sh -lc $importCommand
    if ($LASTEXITCODE -ne 0) { throw 'Importing the backup into the isolated restore database failed.' }

    $sourceDatabase = (& docker exec $containerId sh -lc 'printf %s "$MYSQL_DATABASE"').Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceDatabase -notmatch '^[a-zA-Z0-9_]+$') { throw 'Cannot resolve a safe source database name.' }
    $countSql = 'SELECT CONCAT((SELECT COUNT(*) FROM community),CHAR(124),(SELECT COUNT(*) FROM asset),CHAR(124),(SELECT COUNT(*) FROM customer),CHAR(124),(SELECT COUNT(*) FROM bill),CHAR(124),(SELECT COUNT(*) FROM payment_transaction),CHAR(124),(SELECT COUNT(*) FROM outbox_event),CHAR(124),(SELECT COUNT(*) FROM audit_event))'
    $sourceCounts = Invoke-MySql $sourceDatabase $countSql
    $restoredCounts = Invoke-MySql $drillDatabase $countSql
    if ($sourceCounts -ne $restoredCounts) {
        throw "Restore reconciliation failed: source and restored key-table counts differ."
    }

    $schemaVersion = [int](Invoke-MySql $drillDatabase 'SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1')
    $tableCount = [int](Invoke-MySql $drillDatabase 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type=''BASE TABLE''')
    $reportCount = [int](Invoke-MySql $drillDatabase 'SELECT COUNT(*) FROM report_definition WHERE status=''ACTIVE''')
    $adapterCount = [int](Invoke-MySql $drillDatabase 'SELECT COUNT(*) FROM integration_adapter_policy')
    $productionReady = [int](Invoke-MySql $drillDatabase 'SELECT COUNT(*) FROM integration_adapter_policy WHERE production_ready=TRUE')
    $checksumMismatches = [int](Invoke-MySql $drillDatabase 'SELECT COUNT(*) FROM outbox_event WHERE payload_checksum<>SHA2(payload_json,256)')

    if ($schemaVersion -ne 19) { throw "Expected Flyway v19, restored v$schemaVersion." }
    if ($tableCount -ne 88) { throw "Expected 88 base tables, restored $tableCount." }
    if ($reportCount -ne 22) { throw "Expected 22 enabled reports, restored $reportCount." }
    if ($adapterCount -ne 5 -or $productionReady -ne 0) { throw 'Fail-closed adapter policy reconciliation failed.' }
    if ($checksumMismatches -ne 0) { throw 'Outbox payload checksum reconciliation failed.' }

    [pscustomobject]@{
        Status = 'PASSED'
        BackupPath = $BackupFile
        BackupSha256 = $backupChecksum
        RestoreDatabase = $drillDatabase
        FlywayVersion = $schemaVersion
        BaseTables = $tableCount
        EnabledReports = $reportCount
        AdapterPolicies = $adapterCount
        ProductionReadyAdapters = $productionReady
        KeyTableCounts = $restoredCounts
        KeptForInspection = [bool]$KeepRestoredDatabase
    }
} finally {
    & docker exec $containerId sh -lc 'rm -f -- "$1"' sh $remotePath 2>$null
    if ($databaseCreated -and -not $KeepRestoredDatabase) {
        $dropCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "DROP DATABASE ' + $drillDatabase + '"'
        & docker exec $containerId sh -lc $dropCommand
        if ($LASTEXITCODE -ne 0) { Write-Warning "Isolated restore database cleanup failed: $drillDatabase" }
    }
}
