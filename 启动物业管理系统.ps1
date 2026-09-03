$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

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

$environmentFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $lines = @(
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
        throw 'Docker Desktop 在 4 分钟内未就绪。请手动打开 Docker Desktop，完成许可确认/首次设置并确认引擎运行后重试。'
    }
}

$containerOs = (& $dockerCli info --format '{{.OSType}}' 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $containerOs -ne 'linux') {
    throw '物业管理系统需要 Docker Linux 容器。请将 Docker Desktop 切换为 Linux containers 后重试。'
}

& $dockerCli compose version *> $null
if ($LASTEXITCODE -ne 0) { throw '未检测到 Docker Compose v2。请升级 Docker Desktop 后重试。' }

& $dockerCli compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败。' }

Write-Host '正在构建并启动物业管理系统，首次运行可能需要几分钟……' -ForegroundColor Cyan
& $dockerCli compose up -d --build
if ($LASTEXITCODE -ne 0) { throw '系统启动失败，请执行 docker compose logs api 查看原因。' }

$deadline = [DateTime]::UtcNow.AddMinutes(5)
$ready = $false
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8088/actuator/health' -TimeoutSec 4
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {
        Start-Sleep -Seconds 3
    }
}
if (-not $ready) { throw 'API 在 5 分钟内未就绪，请执行 docker compose ps 和 docker compose logs api 排查。' }

Write-Host '系统已启动：http://localhost:5174' -ForegroundColor Green
Write-Host '全新数据库会自动进入首次配置页面；已有数据库直接进入登录页。'
Start-Process 'http://localhost:5174'
