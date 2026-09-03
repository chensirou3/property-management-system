param(
    [Parameter(Mandatory = $true)]
    [string]$WorkbookPath,

    [string]$OutputParent,

    [string]$DeliveryName = '物业管理系统-便携交付版-v0.1.4-2026-09-03'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputParent) {
    $OutputParent = Split-Path $repo -Parent
}
$outputParentResolved = [System.IO.Path]::GetFullPath($OutputParent)
$deliveryDirectory = [System.IO.Path]::GetFullPath((Join-Path $outputParentResolved $DeliveryName))
$deliveryZip = [System.IO.Path]::GetFullPath((Join-Path $outputParentResolved ($DeliveryName + '.zip')))
$workbookResolved = (Resolve-Path -LiteralPath $WorkbookPath).Path

if ($deliveryDirectory -eq $repo -or -not $deliveryDirectory.StartsWith($outputParentResolved, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw '交付目录解析结果不在指定的 OutputParent 内。'
}
if (Test-Path -LiteralPath $deliveryDirectory) {
    throw "交付目录已存在，请先人工改名或指定新的 DeliveryName：$deliveryDirectory"
}
if (Test-Path -LiteralPath $deliveryZip) {
    throw "交付 ZIP 已存在，请先人工改名或指定新的 DeliveryName：$deliveryZip"
}

$repoGit = $repo.Replace('\', '/')
$status = & git -c "safe.directory=$repoGit" -C $repo status --porcelain
if ($LASTEXITCODE -ne 0) {
    throw '无法读取 Git 工作树状态。'
}
if ($status) {
    throw '工作树不是干净状态；请先审查并提交交付内容，再构建分发包。'
}

$temporaryZip = Join-Path ([System.IO.Path]::GetTempPath()) ("pms3-delivery-" + [guid]::NewGuid().ToString('N') + '.zip')
$completed = $false
try {
    & git -c "safe.directory=$repoGit" -C $repo archive --format=zip --output=$temporaryZip HEAD
    if ($LASTEXITCODE -ne 0) {
        throw 'git archive 构建失败。'
    }
    Expand-Archive -LiteralPath $temporaryZip -DestinationPath $deliveryDirectory

    $sampleDirectory = Join-Path $deliveryDirectory 'delivery\sample-data'
    if (-not (Test-Path -LiteralPath $sampleDirectory)) {
        New-Item -ItemType Directory -Path $sampleDirectory | Out-Null
    }
    Copy-Item -LiteralPath $workbookResolved -Destination (Join-Path $sampleDirectory '物业管理系统-模拟导入数据-32条合格1条隔离.xlsx')

    $convenienceCopies = @(
        @{ Source = 'delivery\物业管理系统交付版使用说明.md'; Destination = '物业管理系统交付版使用说明.md' }
        @{ Source = 'delivery\交付清单.md'; Destination = '交付清单.md' }
        @{ Source = 'delivery\最终交付测试报告-2026-09-03.md'; Destination = '最终交付测试报告-2026-09-03.md' }
        @{ Source = 'delivery\sample-data\物业管理系统-模拟导入-32条合格1条隔离.json'; Destination = '物业管理系统-模拟导入-32条合格1条隔离.json' }
        @{ Source = 'delivery\sample-data\物业管理系统-模拟导入数据-32条合格1条隔离.xlsx'; Destination = '物业管理系统-模拟导入数据-32条合格1条隔离.xlsx' }
    )
    foreach ($copy in $convenienceCopies) {
        Copy-Item -LiteralPath (Join-Path $deliveryDirectory $copy.Source) `
            -Destination (Join-Path $deliveryDirectory $copy.Destination)
    }

    $forbiddenDirectories = @('.git', 'node_modules', 'target', 'dist', 'coverage', 'playwright-report', 'test-results', '.artifacts', '.tools', 'e2e')
    $forbidden = Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -Force | Where-Object {
        ($_.PSIsContainer -and $forbiddenDirectories -contains $_.Name) -or
        (-not $_.PSIsContainer -and ($_.Name -eq '.env' -or $_.Name -match '\.(spec|test)\.ts$'))
    }
    if ($forbidden) {
        throw ('纯净包中发现禁止项：' + (($forbidden.FullName | Select-Object -First 10) -join '; '))
    }

    $files = Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File | Sort-Object FullName
    $fileHashes = foreach ($file in $files) {
        $relative = $file.FullName.Substring($deliveryDirectory.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        [pscustomobject]@{ RelativePath = $relative; Length = $file.Length; Sha256 = $hash }
    }
    $commit = (& git -c "safe.directory=$repoGit" -C $repo rev-parse HEAD).Trim()
    $manifestPath = Join-Path $deliveryDirectory 'DELIVERY-MANIFEST.txt'
    $manifest = @(
        'Property Management System formal empty-data delivery manifest'
        "DeliveryName: $DeliveryName"
        "BuiltAtUtc: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
        "GitCommit: $commit"
        "FileCountBeforeManifest: $($fileHashes.Count)"
        "TotalBytesBeforeManifest: $(($fileHashes | Measure-Object -Property Length -Sum).Sum)"
        'Excluded: .git, .env, credentials, node_modules, target, dist, caches, tests, E2E, snapshots, reports'
        'BusinessDataBaseline: empty on a new installation (PMS_FORMAL_EMPTY_BASELINE=true)'
        'SampleDataPolicy: optional manual-import workbook only; never preloaded or automatically imported'
        'OptionalSampleExpectedResult: 33 Raw / 32 Canonical / 1 Quarantine / 31 Production / 9 reconciliations / rollback remaining 0'
        ''
        'FILES (sha256  bytes  relative-path)'
    ) + ($fileHashes | ForEach-Object { "$($_.Sha256)  $($_.Length)  $($_.RelativePath)" })
    Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding utf8

    Compress-Archive -Path (Join-Path $deliveryDirectory '*') -DestinationPath $deliveryZip -CompressionLevel Optimal
    $zipHash = (Get-FileHash -LiteralPath $deliveryZip -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($deliveryZip + '.sha256'), "$zipHash  $([IO.Path]::GetFileName($deliveryZip))`r`n", [Text.UTF8Encoding]::new($false))

    $completed = $true

    [pscustomobject]@{
        DeliveryDirectory = $deliveryDirectory
        DeliveryZip = $deliveryZip
        ZipSha256 = $zipHash
        FileCount = (Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File).Count
        TotalBytes = (Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File | Measure-Object -Property Length -Sum).Sum
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryZip) {
        Remove-Item -LiteralPath $temporaryZip -Force
    }
    if (-not $completed) {
        if (Test-Path -LiteralPath $deliveryDirectory) {
            Remove-Item -LiteralPath $deliveryDirectory -Recurse -Force
        }
        if (Test-Path -LiteralPath $deliveryZip) {
            Remove-Item -LiteralPath $deliveryZip -Force
        }
        if (Test-Path -LiteralPath ($deliveryZip + '.sha256')) {
            Remove-Item -LiteralPath ($deliveryZip + '.sha256') -Force
        }
    }
}
