param(
    [string]$OutputParent,
    [string]$DeliveryName = '物业管理系统-便携交付版-v0.1.4-2026-09-03'
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($OutputParent)) {
    $OutputParent = Split-Path $sourceRoot -Parent
}
$outputParentPath = [IO.Path]::GetFullPath($OutputParent)
if (-not (Test-Path -LiteralPath $outputParentPath -PathType Container)) {
    throw "输出父目录不存在：$outputParentPath"
}

$deliveryDirectory = [IO.Path]::GetFullPath((Join-Path $outputParentPath $DeliveryName))
$deliveryZip = [IO.Path]::GetFullPath((Join-Path $outputParentPath ($DeliveryName + '.zip')))
$deliveryHashFile = $deliveryZip + '.sha256'
$outputBoundary = $outputParentPath.TrimEnd('\') + '\'
$sourceBoundary = $sourceRoot.TrimEnd('\') + '\'
if (-not $deliveryDirectory.StartsWith($outputBoundary, [StringComparison]::OrdinalIgnoreCase)) {
    throw '交付目录必须位于指定的输出父目录内。'
}
if ($deliveryDirectory -eq $sourceRoot -or $deliveryDirectory.StartsWith($sourceBoundary, [StringComparison]::OrdinalIgnoreCase)) {
    throw '交付目录不能位于项目源目录内部。'
}
foreach ($targetPath in @($deliveryDirectory, $deliveryZip, $deliveryHashFile)) {
    if (Test-Path -LiteralPath $targetPath) {
        throw "输出目标已经存在，为避免覆盖已交付版本，脚本已停止：$targetPath"
    }
}

$excludedDirectories = @(
    '.git',
    '.codex-audit-spreadsheet',
    '.portable-preflight-fixture',
    'node_modules',
    'target',
    'dist',
    'coverage',
    'playwright-report',
    'test-results',
    '.artifacts',
    '.tools',
    'e2e'
)
$excludedFiles = @('.env', 'DELIVERY-MANIFEST.txt', '.DS_Store')

function Copy-CleanTree([string]$source, [string]$destination) {
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $source -Force) {
        if ($item.PSIsContainer) {
            if ($excludedDirectories -contains $item.Name) { continue }
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "源目录包含不允许进入交付包的链接：$($item.FullName)"
            }
            Copy-CleanTree $item.FullName (Join-Path $destination $item.Name)
            continue
        }
        if ($excludedFiles -contains $item.Name) { continue }
        if ($item.Name -match '\.(spec|test)\.ts$') { continue }
        Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $destination $item.Name)
    }
}

$completed = $false
try {
    Copy-CleanTree $sourceRoot $deliveryDirectory

    $forbidden = Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -Force | Where-Object {
        ($_.PSIsContainer -and $excludedDirectories -contains $_.Name) -or
        (-not $_.PSIsContainer -and ($_.Name -eq '.env' -or $_.Name -match '\.(spec|test)\.ts$'))
    }
    if ($forbidden) {
        throw ('纯净包中发现禁止项：' + (($forbidden.FullName | Select-Object -First 10) -join '; '))
    }

    $files = Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File | Sort-Object FullName
    $fileHashes = foreach ($file in $files) {
        $relativePath = $file.FullName.Substring($deliveryDirectory.Length).TrimStart('\').Replace('\', '/')
        $sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        [pscustomobject]@{ RelativePath = $relativePath; Length = $file.Length; Sha256 = $sha256 }
    }
    $manifestPath = Join-Path $deliveryDirectory 'DELIVERY-MANIFEST.txt'
    $manifestLines = @(
        'Property Management System portable delivery manifest'
        'Version: v0.1.4-portable-delivery.1'
        "DeliveryName: $DeliveryName"
        "BuiltAtUtc: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
        "FileCountBeforeManifest: $($fileHashes.Count)"
        "TotalBytesBeforeManifest: $(($fileHashes | Measure-Object -Property Length -Sum).Sum)"
        'Excluded: .git, .env, credentials, node_modules, target, dist, caches, tests, E2E, snapshots and reports'
        'StoragePolicy: Docker named volumes only; no host drive-letter bind mounts'
        'BusinessDataBaseline: empty on a new installation (PMS_FORMAL_EMPTY_BASELINE=true)'
        'SampleDataPolicy: optional manual-import files only; never automatically imported'
        ''
        'FILES (sha256  bytes  relative-path)'
    ) + ($fileHashes | ForEach-Object { "$($_.Sha256)  $($_.Length)  $($_.RelativePath)" })
    [IO.File]::WriteAllLines($manifestPath, $manifestLines, [Text.UTF8Encoding]::new($false))

    Compress-Archive -Path (Join-Path $deliveryDirectory '*') -DestinationPath $deliveryZip -CompressionLevel Optimal
    $zipSha256 = (Get-FileHash -LiteralPath $deliveryZip -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($deliveryHashFile, "$zipSha256  $([IO.Path]::GetFileName($deliveryZip))`r`n", [Text.UTF8Encoding]::new($false))
    $completed = $true

    [pscustomobject]@{
        Version = 'v0.1.4-portable-delivery.1'
        DeliveryDirectory = $deliveryDirectory
        DeliveryZip = $deliveryZip
        ZipSha256 = $zipSha256
        FileCount = (Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File).Count
        TotalBytes = (Get-ChildItem -LiteralPath $deliveryDirectory -Recurse -File | Measure-Object -Property Length -Sum).Sum
    }
}
finally {
    if (-not $completed) {
        if ($deliveryDirectory.StartsWith($outputBoundary, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $deliveryDirectory)) {
            Remove-Item -LiteralPath $deliveryDirectory -Recurse -Force
        }
        if (Test-Path -LiteralPath $deliveryZip) { Remove-Item -LiteralPath $deliveryZip -Force }
        if (Test-Path -LiteralPath $deliveryHashFile) { Remove-Item -LiteralPath $deliveryHashFile -Force }
    }
}
