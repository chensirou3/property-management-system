[CmdletBinding()]
param(
    [int]$MySqlPort = 13317,
    [int]$RedisPort = 16389,
    [int]$ApiPort = 18089,
    [int]$WebPort = 15175,
    [switch]$KeepStack
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFile = Join-Path $repositoryRoot 'docker-compose.yml'
$suffix = ([DateTime]::UtcNow.ToString('yyyyMMddHHmmss') + [Guid]::NewGuid().ToString('N').Substring(0, 5)).ToLowerInvariant()
$projectName = "pms3-g10-empty-$suffix"
if ($projectName -notmatch '^pms3-g10-empty-[a-z0-9]+$') { throw 'Unsafe isolated Compose project name.' }

$overrideNames = @(
    'PMS_MYSQL_HOST_PORT', 'PMS_REDIS_HOST_PORT', 'PMS_API_PORT', 'PMS_WEB_PORT',
    'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD',
    'JWT_SECRET', 'PMS_CALLBACK_SIGNING_SECRET', 'PMS_BOOTSTRAP_ADMIN_USERNAME', 'PMS_BOOTSTRAP_ADMIN_PASSWORD',
    'PMS_FORMAL_EMPTY_BASELINE'
)
$previousEnvironment = @{}
foreach ($name in $overrideNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Set-ProcessEnvironment([string]$Name, [string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function New-StrongLocalSecret {
    return ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N') + 'Aa1!')
}

$started = $false
try {
    Set-ProcessEnvironment 'PMS_MYSQL_HOST_PORT' ([string]$MySqlPort)
    Set-ProcessEnvironment 'PMS_REDIS_HOST_PORT' ([string]$RedisPort)
    Set-ProcessEnvironment 'PMS_API_PORT' ([string]$ApiPort)
    Set-ProcessEnvironment 'PMS_WEB_PORT' ([string]$WebPort)
    Set-ProcessEnvironment 'MYSQL_DATABASE' 'pms3_empty_drill'
    Set-ProcessEnvironment 'MYSQL_USER' 'pms3_empty_user'
    Set-ProcessEnvironment 'MYSQL_PASSWORD' (New-StrongLocalSecret)
    Set-ProcessEnvironment 'MYSQL_ROOT_PASSWORD' (New-StrongLocalSecret)
    Set-ProcessEnvironment 'REDIS_PASSWORD' (New-StrongLocalSecret)
    Set-ProcessEnvironment 'JWT_SECRET' (New-StrongLocalSecret)
    Set-ProcessEnvironment 'PMS_CALLBACK_SIGNING_SECRET' (New-StrongLocalSecret)
    Set-ProcessEnvironment 'PMS_BOOTSTRAP_ADMIN_USERNAME' ''
    Set-ProcessEnvironment 'PMS_BOOTSTRAP_ADMIN_PASSWORD' ''
    Set-ProcessEnvironment 'PMS_FORMAL_EMPTY_BASELINE' 'true'

    & docker compose -p $projectName -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'The isolated Compose configuration is invalid.' }
    & docker compose -p $projectName -f $composeFile up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'The isolated empty-stack build or startup failed.' }
    $started = $true

    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        $apiContainer = (& docker compose -p $projectName -f $composeFile ps -q api).Trim()
        $webContainer = (& docker compose -p $projectName -f $composeFile ps -q web).Trim()
        $apiHealth = if ($apiContainer) { (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $apiContainer).Trim() } else { '' }
        $webStatus = if ($webContainer) { (& docker inspect --format '{{.State.Status}}' $webContainer).Trim() } else { '' }
        if ($apiHealth -eq 'healthy' -and $webStatus -eq 'running') { break }
        if ($apiHealth -eq 'unhealthy' -or $webStatus -eq 'exited') { throw 'The isolated stack entered an unhealthy state.' }
        Start-Sleep -Seconds 3
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($apiHealth -ne 'healthy' -or $webStatus -ne 'running') { throw 'The isolated stack did not become ready in four minutes.' }

    $mysqlContainer = (& docker compose -p $projectName -f $composeFile ps -q mysql).Trim()
    if (-not $mysqlContainer) { throw 'The isolated MySQL container cannot be resolved.' }
    $verificationSql = 'SELECT CONCAT((SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1),CHAR(124),(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type=''BASE TABLE''),CHAR(124),(SELECT COUNT(*) FROM report_definition WHERE status=''ACTIVE''),CHAR(124),(SELECT COUNT(*) FROM integration_adapter_policy),CHAR(124),(SELECT COUNT(*) FROM integration_adapter_policy WHERE production_ready=TRUE),CHAR(124),(SELECT COUNT(*) FROM dashboard_widget_configuration),CHAR(124),(SELECT COUNT(*) FROM visitor_record),CHAR(124),(SELECT COUNT(*) FROM visitor_record WHERE production_connected=TRUE),CHAR(124),(SELECT COUNT(*) FROM enterprise),CHAR(124),(SELECT COUNT(*) FROM community),CHAR(124),(SELECT COUNT(*) FROM asset),CHAR(124),(SELECT COUNT(*) FROM customer),CHAR(124),(SELECT COUNT(*) FROM fee_definition),CHAR(124),(SELECT COUNT(*) FROM bill),CHAR(124),(SELECT COUNT(*) FROM sys_user))'
    $queryCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" -N -B'
    $verificationOutput = $verificationSql | & docker exec -i $mysqlContainer sh -lc $queryCommand
    if ($LASTEXITCODE -ne 0) { throw 'The isolated database verification query failed.' }
    $verification = ($verificationOutput | Out-String).Trim()
    if ($verification -ne '24|91|22|5|0|0|0|0|0|0|0|0|0|0|0') { throw "Unexpected isolated database invariants: $verification" }

    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort/actuator/health/readiness" -TimeoutSec 15
    if ($health.status -ne 'UP') { throw 'The isolated API readiness probe is not UP.' }
    $setupStatus = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort/api/v1/setup/status" -TimeoutSec 15
    if ($setupStatus.initialized -ne $false -or $setupStatus.deploymentMode -ne 'SINGLE_PROJECT') {
        throw 'The isolated system is not waiting for one-time web setup.'
    }
    $webResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$WebPort/setup" -TimeoutSec 15 -UseBasicParsing
    if ($webResponse.StatusCode -ne 200) { throw 'The isolated web setup page is not reachable.' }

    [pscustomobject]@{
        Status = 'PASSED'
        ProjectName = $projectName
        FlywayVersion = 24
        BaseTables = 91
        EnabledReports = 22
        AdapterPolicies = 5
        ProductionReadyAdapters = 0
        PublishedDashboardWidgets = 0
        VisitorRecords = 0
        ProductionConnectedVisitors = 0
        Enterprises = 0
        Communities = 0
        Assets = 0
        Customers = 0
        FeeDefinitions = 0
        Bills = 0
        Users = 0
        SetupInitialized = $setupStatus.initialized
        ApiReadiness = $health.status
        WebStatus = $webResponse.StatusCode
        KeptForInspection = [bool]$KeepStack
    }
} finally {
    if ($started -and -not $KeepStack) {
        $containerIds = @(& docker ps -aq --filter "label=com.docker.compose.project=$projectName")
        $mismatchedContainers = @($containerIds | Where-Object { $_ } | ForEach-Object {
            $metadata = (& docker inspect $_ | ConvertFrom-Json)[0]
            if ($metadata.Config.Labels.'com.docker.compose.project' -ne $projectName) { $_ }
        })
        if ($mismatchedContainers.Count -gt 0) {
            Write-Warning 'Isolated Compose cleanup skipped because a container label did not match the exact project.'
        } else {
            & docker compose -p $projectName -f $composeFile down -v --remove-orphans
            if ($LASTEXITCODE -ne 0) { Write-Warning "Isolated Compose cleanup failed: $projectName" }
        }
    }
    foreach ($name in $overrideNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
