$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function New-PropertySystemSecret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($buffer) -replace '-', '').ToLowerInvariant()
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

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '未找到 Docker。请先安装并启动 Docker Desktop。'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 尚未就绪，请启动后重试。' }

docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败。' }

Write-Host '正在构建并启动物业管理系统，首次运行可能需要几分钟……' -ForegroundColor Cyan
docker compose up -d --build
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
