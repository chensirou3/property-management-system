param(
    [string]$ApiBaseUrl = "http://localhost:8088/api/v1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$primaryProjectId = "30000000-0000-0000-0000-000000000001"
$isolatedProjectId = "30000000-0000-0000-0000-000000000002"

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw "LIVE_ASSERTION_FAILED: $Message"
    }
}

function Convert-ToDecimal {
    param([Parameter(Mandatory = $true)]$Value)
    return [decimal]::Parse(
        [string]$Value,
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture
    )
}

$username = $env:PMS_E2E_USERNAME
$password = $env:PMS_E2E_PASSWORD
Assert-Condition (-not [string]::IsNullOrWhiteSpace($username)) "PMS_E2E_USERNAME is required"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($password)) "PMS_E2E_PASSWORD is required"

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/auth/login" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($loginBody))
Remove-Variable password, loginBody

Assert-Condition (-not [string]::IsNullOrWhiteSpace($login.accessToken)) "login did not return an access token"
Assert-Condition (@($login.user.projectIds) -contains $primaryProjectId) "account cannot access the primary project"
Assert-Condition (@($login.user.projectIds) -contains $isolatedProjectId) "account cannot access the isolated project"

$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Get-PmsApi {
    param([Parameter(Mandatory = $true)][string]$Path)
    return Invoke-RestMethod -Method Get -Uri "$ApiBaseUrl$Path" -Headers $headers
}

$primary = Get-PmsApi "/dashboard?communityId=$primaryProjectId"
$isolated = Get-PmsApi "/dashboard?communityId=$isolatedProjectId"

Assert-Condition ([long]$primary.counts.rooms -eq 359) "primary room count must come from the 359-row dataset"
Assert-Condition ([long]$isolated.counts.rooms -eq 1) "isolated room count must be 1, proving it is not hard-coded"
Assert-Condition ([long]$primary.counts.allocations -eq 773) "primary allocation count must be 773"
Assert-Condition ([long]$primary.counts.asset_allocations -eq 743) "primary asset allocation count must be 743"
Assert-Condition ([long]$primary.counts.meter_allocations -eq 30) "primary meter allocation count must be 30"
Assert-Condition ([long]$primary.counts.allocations -eq `
    ([long]$primary.counts.asset_allocations + [long]$primary.counts.meter_allocations)) `
    "primary allocation subtype counts do not add up"
Assert-Condition ([long]$primary.quality.orphan_allocations -eq 0) "primary project still reports allocation orphans"
Assert-Condition ([long]$primary.quality.room_detail_mismatches -eq 0) "primary project reports room-detail mismatches"
Assert-Condition ([long]$isolated.quality.orphan_allocations -eq 0) "isolated project reports allocation orphans"
Assert-Condition ([long]$isolated.counts.allocations -eq 0) "isolated project allocation count must be 0"

$expectedReportCodes = @(
    "TRANSACTION_SUMMARY", "TRANSACTION_DETAILS", "RECEIPT_BATCH_PRINT", "PAYMENTS",
    "ARREARS", "BILL_NOTIFICATIONS", "BILLS", "COLLECTION_RATE",
    "ARREARS_CLEARANCE_RATE", "COMPREHENSIVE_QUERY", "COLLECTION_CLEARANCE_SUMMARY",
    "CHARGE_DETAILS", "DISCOUNT_DETAILS", "PREPAYMENTS", "OWNERSHIP_TRANSFERS",
    "REMINDERS", "FEE_STATUS", "INVOICE_STATISTICS", "DEPOSITS",
    "DAILY_SETTLEMENT_DETAILS", "ADJUSTMENTS", "BANK_TRUST"
)

$catalog = Get-PmsApi "/reports/catalog?communityId=$primaryProjectId"
Assert-Condition ($catalog.Count -eq 22) "report catalog must contain 22 reports"
$actualReportCodes = @($catalog | ForEach-Object { $_.report_code } | Sort-Object)
Assert-Condition (($actualReportCodes -join "|") -ceq (($expectedReportCodes | Sort-Object) -join "|")) `
    "report catalog codes differ from the governed contract"
Assert-Condition (@($catalog | Where-Object { [int]$_.filter_schema_version -ne 2 }).Count -eq 0) `
    "a report is not using filter schema version 2"

$options = Get-PmsApi "/reports/filter-options?communityId=$primaryProjectId&reportCode=COLLECTION_RATE"
$feeOptions = @($options.feeDefinitionId)
$seedFeeDefinitionId = "70000000-0000-0000-0000-000000000001"
Assert-Condition ($feeOptions.Count -gt 0) "COLLECTION_RATE fee-definition options are empty"
Assert-Condition (@($feeOptions.value) -contains $seedFeeDefinitionId) "the seeded fee definition is missing"
Assert-Condition (@($options.cashierId).Count -eq 0) "COLLECTION_RATE must not expose cashier options"

$rate = Get-PmsApi ("/reports/COLLECTION_RATE?communityId=$primaryProjectId" +
    "&billingPeriodFrom=2026-07&billingPeriodTo=2026-07" +
    "&feeDefinitionId=$seedFeeDefinitionId&page=1&size=50")
Assert-Condition ($rate.appliedFilters.billingPeriodFrom -ceq "2026-07") "billingPeriodFrom mapping failed"
Assert-Condition ($rate.appliedFilters.billingPeriodTo -ceq "2026-07") "billingPeriodTo mapping failed"
Assert-Condition ($rate.appliedFilters.feeDefinitionId -ceq $seedFeeDefinitionId) "feeDefinitionId mapping failed"
Assert-Condition ([int]$rate.filterSchemaVersion -eq 2) "COLLECTION_RATE filter schema version is not 2"
Assert-Condition ([int]$rate.total -eq 1) "COLLECTION_RATE must return one project summary row"
Assert-Condition ((Convert-ToDecimal $rate.rows[0].receivableAmount) -gt 0) "2026-07 receivable must be positive"

$emptyRate = Get-PmsApi ("/reports/COLLECTION_RATE?communityId=$primaryProjectId" +
    "&billingPeriodFrom=2099-01&billingPeriodTo=2099-01&page=1&size=50")
Assert-Condition ((Convert-ToDecimal $emptyRate.rows[0].receivableAmount) -eq 0) `
    "the empty billing-period filter did not reach SQL"
Assert-Condition ((Convert-ToDecimal $emptyRate.rows[0].collectedAmount) -eq 0) `
    "the empty billing-period filter returned collected money"

$details = Get-PmsApi ("/reports/TRANSACTION_DETAILS?communityId=$primaryProjectId" +
    "&dateFrom=2026-01-01&dateTo=2026-12-31" +
    "&transactionNo=__LIVE_NO_MATCH__&paymentChannel=CASH&status=SUCCESS")
Assert-Condition ($details.appliedFilters.transactionNo -ceq "__LIVE_NO_MATCH__") "transactionNo mapping failed"
Assert-Condition ($details.appliedFilters.paymentChannel -ceq "CASH") "paymentChannel mapping failed"
Assert-Condition ($details.appliedFilters.status -ceq "SUCCESS") "status mapping failed"
Assert-Condition ([int]$details.total -eq 0) "a nonexistent transaction number returned rows"

$login.accessToken = $null
$headers.Authorization = $null
Remove-Variable login, headers, username

[pscustomobject]@{
    Status = "PASSED"
    PrimaryRooms = [long]$primary.counts.rooms
    IsolatedRooms = [long]$isolated.counts.rooms
    Allocations = [long]$primary.counts.allocations
    AssetAllocations = [long]$primary.counts.asset_allocations
    MeterAllocations = [long]$primary.counts.meter_allocations
    OrphanAllocations = [long]$primary.quality.orphan_allocations
    ReportCatalog = $catalog.Count
    FilterSchemaVersion = [int]$rate.filterSchemaVersion
} | Format-List
