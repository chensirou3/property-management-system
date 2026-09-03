param(
    [switch]$CheckOnly,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$deliveryVersion = 'v0.1.4-portable-delivery.1'

function New-PropertySystemSecret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($buffer) -replace '-', '').ToLowerInvariant()
}

function Find-DockerCli {
    $command = Get-Command docker.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe')
        (Join-Path $env:LOCALAPPDATA 'Docker\resources\bin\docker.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Find-DockerDesktop {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe')
        (Join-Path $env:LOCALAPPDATA 'Docker\Docker Desktop.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Test-DockerEngine([string]$dockerCli) {
    & $dockerCli info *> $null
    return $LASTEXITCODE -eq 0
}

function Assert-DockerDesktopStorageLocation {
    if ($env:OS -ne 'Windows_NT' -or [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        return
    }

    $dockerWslDirectory = Join-Path $env:LOCALAPPDATA 'Docker\wsl'
    if (-not (Test-Path -LiteralPath $dockerWslDirectory -PathType Container)) {
        return
    }

    $diskEntry = Get-ChildItem -LiteralPath $dockerWslDirectory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'disk' } |
        Select-Object -First 1
    if (-not $diskEntry) { return }

    $isReparsePoint = ($diskEntry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    if (-not $isReparsePoint) { return }

    $linkTargets = @($diskEntry.Target) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    if ($linkTargets.Count -eq 0) {
        throw "检测到 Docker 数据目录是无法解析的链接：$($diskEntry.FullName)。请先在 Docker Desktop 的 Settings → Resources → Advanced 中重新选择有效的数据位置。"
    }

    foreach ($linkTarget in $linkTargets) {
        $expandedTarget = [Environment]::ExpandEnvironmentVariables([string]$linkTarget)
        if (-not [IO.Path]::IsPathRooted($expandedTarget)) {
            $expandedTarget = [IO.Path]::GetFullPath((Join-Path $dockerWslDirectory $expandedTarget))
        }
        if (-not (Test-Path -LiteralPath $expandedTarget -PathType Container)) {
            throw @"
Docker Desktop 的数据目录链接指向不存在的位置：
  链接：$($diskEntry.FullName)
  目标：$expandedTarget

这属于接收电脑的 Docker Desktop 全局配置，不是本项目的 D 盘依赖。请打开 Docker Desktop → Settings → Resources → Advanced，将 Disk image location 改为当前电脑存在且空间充足的磁盘；如果旧数据不需要保留，也可以在完全退出 Docker Desktop 后先备份并移走该失效链接，再重新启动 Docker Desktop。脚本不会自动删除或覆盖接收人的 Docker 数据。
"@
        }
    }
}

function Read-PropertyEnvironment([string]$path) {
    $settings = @{}
    foreach ($rawLine in Get-Content -LiteralPath $path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { continue }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $settings[$key] = $value
    }
    return $settings
}

function Assert-PropertyEnvironment([hashtable]$settings) {
    $secretMinimumLengths = @{
        MYSQL_PASSWORD = 16
        MYSQL_ROOT_PASSWORD = 16
        REDIS_PASSWORD = 16
        JWT_SECRET = 32
        PMS_CALLBACK_SIGNING_SECRET = 32
    }
    $placeholderValues = @(
        'change-me',
        'change-root-password',
        'change-redis-password',
        'replace-with-at-least-32-random-characters',
        'replace-with-a-different-32-character-random-secret'
    )
    $secretValues = @()
    foreach ($entry in $secretMinimumLengths.GetEnumerator()) {
        $value = [string]$settings[$entry.Key]
        if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $entry.Value -or $placeholderValues -contains $value) {
            throw ".env 中的 $($entry.Key) 缺失、仍是示例占位符或长度不足。最简单的修复方法是删除尚未使用的 .env 后重新运行启动脚本，让系统自动生成随机技术密钥。"
        }
        $secretValues += $value
    }
    if (($secretValues | Select-Object -Unique).Count -ne $secretValues.Count) {
        throw '.env 中数据库、Redis、JWT 和回调密钥不能重复，请分别使用独立随机值。'
    }

    $portDefaults = [ordered]@{
        PMS_MYSQL_HOST_PORT = 3307
        PMS_REDIS_HOST_PORT = 6379
        PMS_API_PORT = 8088
        PMS_WEB_PORT = 5174
    }
    $validatedPorts = [ordered]@{}
    foreach ($entry in $portDefaults.GetEnumerator()) {
        $rawValue = [string]$settings[$entry.Key]
        if ([string]::IsNullOrWhiteSpace($rawValue)) { $rawValue = [string]$entry.Value }
        $parsedPort = 0
        if (-not [int]::TryParse($rawValue, [ref]$parsedPort) -or $parsedPort -lt 1 -or $parsedPort -gt 65535) {
            throw ".env 中的 $($entry.Key) 必须是 1—65535 之间的端口号。"
        }
        $validatedPorts[$entry.Key] = $parsedPort
    }
    if (($validatedPorts.Values | Select-Object -Unique).Count -ne $validatedPorts.Count) {
        throw '.env 中的 MySQL、Redis、API 和 Web 主机端口不能重复。'
    }
    return $validatedPorts
}

function Test-LoopbackPortAvailable([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try {
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        try { $listener.Stop() } catch { }
    }
}

function Assert-HostPorts([string]$dockerCli, [System.Collections.IDictionary]$ports) {
    $servicePorts = @(
        @{ Service = 'mysql'; Key = 'PMS_MYSQL_HOST_PORT' }
        @{ Service = 'redis'; Key = 'PMS_REDIS_HOST_PORT' }
        @{ Service = 'api'; Key = 'PMS_API_PORT' }
        @{ Service = 'web'; Key = 'PMS_WEB_PORT' }
    )
    foreach ($mapping in $servicePorts) {
        $port = [int]$ports[$mapping.Key]
        if (Test-LoopbackPortAvailable $port) { continue }

        $runningContainer = ((& $dockerCli compose ps --status running -q $mapping.Service 2>$null) -join '').Trim()
        if ($LASTEXITCODE -eq 0 -and $runningContainer) { continue }

        throw "本机端口 $port 已被其他程序占用（$($mapping.Key)）。请关闭占用程序，或在 .env 中为该项设置一个未使用的端口后重试。"
    }
}

function Assert-ComposePortability([string]$dockerCli) {
    & $dockerCli compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败。' }

    $composeJsonText = ((& $dockerCli compose config --format json) -join "`n")
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($composeJsonText)) {
        throw '无法读取 Docker Compose 解析结果。'
    }
    $composeModel = $composeJsonText | ConvertFrom-Json
    foreach ($serviceProperty in $composeModel.services.PSObject.Properties) {
        foreach ($mount in @($serviceProperty.Value.volumes)) {
            if ($mount -and $mount.type -eq 'bind') {
                throw "服务 $($serviceProperty.Name) 使用了主机绑定目录 $($mount.source)。便携交付版只允许 Docker 命名卷，禁止绑定到接收电脑的 C/D 盘路径。"
            }
        }
    }
}

Write-Host "物业管理系统便携交付版 $deliveryVersion" -ForegroundColor Cyan

$environmentFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $lines = @(
        "COMPOSE_PROJECT_NAME=property-management-system-$(New-PropertySystemSecret 6)"
        'MYSQL_DATABASE=pms3_replica'
        'MYSQL_USER=pms3'
        "MYSQL_PASSWORD=$(New-PropertySystemSecret)"
        "MYSQL_ROOT_PASSWORD=$(New-PropertySystemSecret)"
        "REDIS_PASSWORD=$(New-PropertySystemSecret)"
        "JWT_SECRET=$(New-PropertySystemSecret 48)"
        "PMS_CALLBACK_SIGNING_SECRET=$(New-PropertySystemSecret 48)"
        'PMS_BOOTSTRAP_ADMIN_USERNAME='
        'PMS_BOOTSTRAP_ADMIN_PASSWORD='
        'PMS_FORMAL_EMPTY_BASELINE=true'
        'LOGIN_MAX_FAILURES=5'
        'LOGIN_WINDOW_MINUTES=15'
        'LOGIN_LOCK_MINUTES=15'
        'PMS_CALLBACK_MAX_SKEW_SECONDS=300'
        'PMS_MYSQL_HOST_PORT=3307'
        'PMS_REDIS_HOST_PORT=6379'
        'PMS_API_PORT=8088'
        'PMS_WEB_PORT=5174'
    )
    [IO.File]::WriteAllLines($environmentFile, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host '已生成本机技术密钥。管理员账号和密码将在网页中创建。' -ForegroundColor Green
}

$environmentSettings = Read-PropertyEnvironment $environmentFile
$hostPorts = Assert-PropertyEnvironment $environmentSettings
Assert-DockerDesktopStorageLocation

$dockerCli = Find-DockerCli
if (-not $dockerCli) {
    throw '未找到 Docker CLI。请先安装 Docker Desktop，完成首次安装设置后重新双击本启动文件。'
}

if (-not (Test-DockerEngine $dockerCli)) {
    $dockerDesktop = Find-DockerDesktop
    if (-not $dockerDesktop) {
        throw 'Docker 引擎尚未运行，且未找到 Docker Desktop。请手动启动 Docker 服务后重试。'
    }

    $dockerDesktopProcess = Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue
    if (-not $dockerDesktopProcess) {
        Write-Host 'Docker Desktop 尚未运行，正在自动启动……' -ForegroundColor Cyan
        Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
    } else {
        Write-Host 'Docker Desktop 正在启动，等待引擎就绪……' -ForegroundColor Cyan
    }

    $dockerDeadline = [DateTime]::UtcNow.AddMinutes(4)
    $dockerReady = $false
    $nextProgressAt = [DateTime]::UtcNow
    while ([DateTime]::UtcNow -lt $dockerDeadline) {
        if (Test-DockerEngine $dockerCli) {
            $dockerReady = $true
            break
        }
        if ([DateTime]::UtcNow -ge $nextProgressAt) {
            Write-Host '正在等待 Docker Desktop 完成初始化……'
            $nextProgressAt = [DateTime]::UtcNow.AddSeconds(15)
        }
        Start-Sleep -Seconds 3
    }
    if (-not $dockerReady) {
        Assert-DockerDesktopStorageLocation
        throw 'Docker Desktop 在 4 分钟内未就绪。请手动打开 Docker Desktop 查看提示；重点检查许可、WSL 2、虚拟化以及 Docker 数据磁盘位置。'
    }
}

$containerOs = (& $dockerCli info --format '{{.OSType}}' 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $containerOs -ne 'linux') {
    throw '物业管理系统需要 Docker Linux 容器。请将 Docker Desktop 切换为 Linux containers 后重试。'
}

& $dockerCli compose version *> $null
if ($LASTEXITCODE -ne 0) { throw '未检测到 Docker Compose v2。请升级 Docker Desktop 后重试。' }

Assert-ComposePortability $dockerCli
Assert-HostPorts $dockerCli $hostPorts

if ($CheckOnly) {
    Write-Host '运行环境检查通过：未发现失效磁盘链接、主机绑定目录、无效密钥或端口冲突。' -ForegroundColor Green
    exit 0
}

Write-Host '正在构建并启动物业管理系统，首次运行可能需要几分钟……' -ForegroundColor Cyan
& $dockerCli compose up -d --build
if ($LASTEXITCODE -ne 0) { throw '系统启动失败，请执行 docker compose ps 和 docker compose logs --tail 200 查看原因。' }

$apiHealthUri = "http://127.0.0.1:$($hostPorts.PMS_API_PORT)/actuator/health"
$webUri = "http://127.0.0.1:$($hostPorts.PMS_WEB_PORT)"
$deadline = [DateTime]::UtcNow.AddMinutes(5)
$ready = $false
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri $apiHealthUri -TimeoutSec 4
        $webResponse = Invoke-WebRequest -UseBasicParsing -Uri $webUri -TimeoutSec 4
        if ($health.status -eq 'UP' -and $webResponse.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 3
    }
}
if (-not $ready) { throw 'API 或 Web 在 5 分钟内未就绪，请执行 docker compose ps 和 docker compose logs --tail 200 排查。' }

Write-Host "系统已启动：$webUri" -ForegroundColor Green
Write-Host '全新数据库会自动进入首次配置页面；已有数据库直接进入登录页。'
if (-not $NoBrowser) {
    Start-Process $webUri
}
