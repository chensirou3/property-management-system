import { expect, test, type Page } from '@playwright/test'
import { createRequire } from 'node:module'
import { reportCodeByPath } from '../src/config/reporting'

type VisualCatalogPage = { pageNo: number; title: string; path: string; columns: string[] }
const pageCatalog = createRequire(import.meta.url)('../src/config/page-catalog.json').pages as VisualCatalogPage[]
const selectedVisualPage = process.env.PMS_VISUAL_PAGE?.trim()
const visualCatalog = selectedVisualPage
  ? pageCatalog.filter((page) => String(page.pageNo) === selectedVisualPage)
  : pageCatalog
if (selectedVisualPage && visualCatalog.length !== 1) {
  throw new Error(`PMS_VISUAL_PAGE must identify one catalog page; received ${selectedVisualPage}`)
}
const governedPageByCode = new Map(Object.entries(reportCodeByPath).map(([path, code]) => [
  code, pageCatalog.find((page) => page.path === path)!,
]))

const username = process.env.PMS_E2E_USERNAME || 'admin'
const password = process.env.PMS_E2E_PASSWORD
const dashboardVisualFixture = {
  communityId: '30000000-0000-0000-0000-000000000001',
  counts: { rooms: 359, customers: 403, parking_spaces: 250, meters: 31,
    fee_definitions: 23, fee_standards: 17, allocations: 773, asset_allocations: 743, meter_allocations: 30 },
  finance: { receivable: 2468.84, received: 241.26, outstanding: 2227.58,
    bill_count: 360, collection_rate: 9.77, metricSource: 'COLLECTION_RATE' },
  quality: { room_detail_mismatches: 0, orphan_customer_relations: 0, orphan_allocations: 0, synthetic: true },
  adapters: { payment: 'simulator', invoice: 'simulator', bank: 'simulator', iot: 'simulator', java110: 'disabled' },
}
const visualBills = [
  { id: 'visual-bill-1', asset_id: 'visual-asset-1', customer_id: 'visual-customer-1', bill_no: 'VIS-BILL-202608-0001', asset_code: 'R0801', customer_name: '视觉客户 0001', billing_period: '2026-08', original_amount: 128.5, adjustment_amount: -8.5, total_amount: 120, paid_amount: 20, outstanding_amount: 100, due_date: '2026-08-31', status: 'PARTIAL' },
  { id: 'visual-bill-2', asset_id: 'visual-asset-2', customer_id: 'visual-customer-2', bill_no: 'VIS-BILL-202608-0002', asset_code: 'R0802', customer_name: '视觉客户 0002', billing_period: '2026-08', original_amount: 96, adjustment_amount: 0, total_amount: 96, paid_amount: 0, outstanding_amount: 96, due_date: '2026-08-31', status: 'UNPAID' },
]
const healthyReconciliation = { healthy: true, billMismatches: 0, paymentMismatches: 0, prepaymentMismatches: 0, depositMismatches: 0, settlementMismatches: 0, receiptChecksumMismatches: 0, invoiceChecksumMismatches: 0 }
const financialVisualFixtures = new Map<string, unknown>([
  ['/api/v1/finance/bills', { items: visualBills, page: 1, size: 200, total: visualBills.length, asOfDate: '2026-08-25' }],
  ['/api/v1/finance/arrears', { items: [visualBills[1]], count: 1, outstandingAmount: 96, asOfDate: '2026-08-25' }],
  ['/api/v1/finance/settlements', [{ id: 'visual-settlement-1', settlement_date: '2026-08-24', transaction_count: 8, gross_amount: 560, reversal_amount: -20, net_amount: 540, attached_transaction_count: 8, status: 'LOCKED', created_by_name: '视觉收银员' }]],
  ['/api/v1/finance/settlements:preview', { transactionCount: 3, grossAmount: 220, reversalAmount: -20, netAmount: 200 }],
  ['/api/v1/finance/reconciliation', healthyReconciliation],
  ['/api/v1/finance/adjustments', [{ id: 'visual-adjustment-1', adjustment_no: 'VIS-ADJ-0001', bill_no: 'VIS-BILL-202608-0001', asset_code: 'R0801', adjustment_type: 'WAIVER', amount: -8.5, reason: '视觉基线减免', status: 'APPLIED', requested_by_name: '视觉财务员' }]],
  ['/api/v1/finance/receipts', [{ id: 'visual-receipt-1', receipt_no: 'VIS-RCT-000001', order_no: 'VIS-PAY-000001', confirmed_amount: 20, payment_channel: 'BANK_TRANSFER', original_receipt_no: null, status: 'ISSUED', issued_at: '2026-08-24T10:30:00', event_reason: '正常签发' }]],
  ['/api/v1/finance/receipt-segments', [{ id: 'visual-segment-1', segment_code: 'VIS-2026', remaining_count: 998, status: 'ACTIVE' }]],
  ['/api/v1/finance/discount-policies', [{ id: 'visual-discount-1', policy_code: 'VIS-DISCOUNT-01', display_name: '物业费示例优惠', discount_type: 'PERCENT', discount_value: 0.05, maximum_amount: 50, effective_from: '2026-01-01', effective_to: null, status: 'ACTIVE' }]],
  ['/api/v1/finance/balances', { prepayments: [{ id: 'visual-prepayment-1', customer_no: 'VIS-CUSTOMER-001', customer_name: '视觉客户 0001', balance: 80, frozen_balance: 0, transaction_count: 2, version: 2 }], deposits: [{ id: 'visual-deposit-1', customer_no: 'VIS-CUSTOMER-002', customer_name: '视觉客户 0002', asset_code: 'R0802', deposit_type: 'ACCESS_CARD', balance: 100, transaction_count: 1, status: 'HELD' }] }],
  ['/api/v1/finance/invoices', [{ id: 'visual-invoice-1', request_no: 'VIS-INV-000001', receipt_no: 'VIS-RCT-000001', operation_type: 'ISSUE', original_request_no: null, amount: 20, title_snapshot: '视觉客户示例抬头', status: 'ISSUED', created_at: '2026-08-24T10:35:00' }]],
  ['/api/v1/finance/transactions', [{ id: 'visual-transaction-2', transaction_no: 'VIS-TXN-000002', order_no: 'VIS-PAY-000001', payment_channel: 'BANK_TRANSFER', transaction_type: 'REVERSAL', amount: -20, cashier_name: '视觉收银员', settlement_id: 'VIS-SET-0001', occurred_at: '2026-08-24T11:00:00' }, { id: 'visual-transaction-1', transaction_no: 'VIS-TXN-000001', order_no: 'VIS-PAY-000001', payment_channel: 'BANK_TRANSFER', transaction_type: 'PAYMENT', amount: 20, cashier_name: '视觉收银员', settlement_id: 'VIS-SET-0001', occurred_at: '2026-08-24T10:30:00' }]],
])
const cashierVisualFixtures = new Map<string, unknown>([
  ['/api/v1/cashier/shifts:current', { open: false }],
  ['/api/v1/cashier/context', { bills: visualBills, customers: [{ id: 'visual-customer-1', display_name: '视觉客户 0001' }, { id: 'visual-customer-2', display_name: '视觉客户 0002' }], assets: [{ id: 'visual-asset-1', code: 'R0801', display_name: '8-01' }, { id: 'visual-asset-2', code: 'R0802', display_name: '8-02' }] }],
])
const meterVisualWorkbench = {
  summary: { meterCount: 32, activeMeterCount: 31, batchCount: 6, pendingReviewCount: 1,
    iotEvidenceCount: 2, reconciliationMismatchCount: 0 },
  meters: [
    { id: 'visual-meter-master', meterNo: 'VIS-MASTER-001', meterType: 'WATER', meterClass: 'MASTER',
      status: 'ACTIVE', rangeValue: 999999, multiplier: 1, lossRate: 0, correction: 0,
      assetId: null, assetName: null, parentMeterNo: null, lastReading: 860, lastReadingPeriod: '2026-07' },
    { id: 'visual-meter-1', meterNo: 'VIS-SUB-001', meterType: 'WATER', meterClass: 'SUB',
      status: 'ACTIVE', rangeValue: 99999, multiplier: 1, lossRate: 0.02, correction: 0,
      assetId: 'visual-asset-1', assetName: '1号楼 1单元 101', parentMeterNo: 'VIS-MASTER-001',
      lastReading: 125.5, lastReadingPeriod: '2026-07' },
    { id: 'visual-meter-2', meterNo: 'VIS-SUB-002', meterType: 'WATER', meterClass: 'SUB',
      status: 'ACTIVE', rangeValue: 99999, multiplier: 1.5, lossRate: 0.02, correction: 0,
      assetId: 'visual-asset-2', assetName: '1号楼 1单元 102', parentMeterNo: 'VIS-MASTER-001',
      lastReading: 98, lastReadingPeriod: '2026-07' },
  ],
  batches: [
    { id: 'visual-meter-batch', batchNo: 'VIS-MR-202608', readingPeriod: '2026-08', sourceType: 'MIXED',
      totalCount: 2, normalCount: 1, anomalyCount: 1, reviewedCount: 0, status: 'DRAFT', dataChecksum: '审核后生成' },
    { id: 'visual-meter-approved', batchNo: 'VIS-MR-202607', readingPeriod: '2026-07', sourceType: 'MANUAL',
      totalCount: 2, normalCount: 2, anomalyCount: 0, reviewedCount: 0, status: 'APPROVED',
      dataChecksum: '8f58ed768cd8f46c5b932bd23930195ce4800374571f404ca9d20d254659c07d' },
  ],
  shareRules: [{ id: 'visual-share-rule', name: '按建筑面积公摊（合成假设）', status: 'ACTIVE',
    activeVersionId: 'visual-share-version', activeVersionNo: 1, assumptionRule: true }],
  feeStandards: [{ id: 'visual-meter-standard', code: 'FEE-METER-WATER', name: '水费（合成示范）',
    status: 'ACTIVE', versionNo: 1, unitPrice: 3.5, roundingMode: 'HALF_UP' }],
}
const meterVisualBatchDetail = {
  batch: meterVisualWorkbench.batches[0],
  readings: [
    { id: 'visual-reading-1', meterNo: 'VIS-SUB-001', assetName: '1号楼 1单元 101',
      previousReading: 125.5, currentReading: 138.5, adjustedUsage: 13.26, allocatedShare: 0,
      billableUsage: 13.26, validationStatus: 'NORMAL', anomalyCode: null, version: 0 },
    { id: 'visual-reading-2', meterNo: 'VIS-SUB-002', assetName: '1号楼 1单元 102',
      previousReading: 97, currentReading: 110, adjustedUsage: 19.89, allocatedShare: 0,
      billableUsage: 19.89, validationStatus: 'REVIEW_REQUIRED', anomalyCode: 'PREVIOUS_MISMATCH', version: 0 },
  ],
  iotEvidence: [{ id: 'visual-iot-1', adapterCode: 'IOT_SIMULATOR', simulated: true, status: 'APPLIED' }],
  reconciliation: [],
}
const integrationVisualWorkbench = {
  communityId: '30000000-0000-0000-0000-000000000001',
  adapters: [
    { adapterCode: 'PAYMENT_SIMULATOR', providerType: 'PAYMENT', providerName: '支付模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, endpointMasked: 'local://payment-simulator', credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 2000, maxAttempts: 3, retryBaseSeconds: 5, lastCheckedAt: '2026-08-25T10:20:00' },
    { adapterCode: 'INVOICE_SIMULATOR', providerType: 'INVOICE', providerName: '发票模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, endpointMasked: 'local://invoice-simulator', credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 2000, maxAttempts: 3, retryBaseSeconds: 5, lastCheckedAt: '2026-08-25T10:20:01' },
    { adapterCode: 'BANK_TRUST_SIMULATOR', providerType: 'BANK', providerName: '银行信托模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, endpointMasked: 'local://bank-trust-simulator', credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 10, lastCheckedAt: '2026-08-25T10:20:02' },
    { adapterCode: 'IOT_SIMULATOR', providerType: 'IOT', providerName: 'IoT 模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, endpointMasked: 'local://iot-simulator', credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 2000, maxAttempts: 3, retryBaseSeconds: 5, lastCheckedAt: '2026-08-25T10:20:03' },
    { adapterCode: 'JAVA110_DISABLED', providerType: 'JAVA110', providerName: 'Java110 兼容适配器', mode: 'DISABLED', enabled: false, productionReady: false, endpointMasked: 'disabled://java110', credentialStatus: 'NOT_CONFIGURED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 10 },
  ],
  outboxSummary: [{ status: 'PUBLISHED', itemCount: 64 }, { status: 'PENDING', itemCount: 2 }],
  callbacks: [{ id: 'visual-callback-1', adapterCode: 'PAYMENT_SIMULATOR', callbackId: 'VIS-CALLBACK-0001', requestId: 'visual-request-0001', payloadChecksum: 'a'.repeat(64), replayCount: 1, status: 'PROCESSED', createdAt: '2026-08-25T10:21:00' }],
  attempts: [{ id: 'visual-attempt-1', direction: 'OUTBOUND', adapterCode: 'PAYMENT_SIMULATOR', referenceId: 'visual-outbox-1', attemptNo: 1, outcome: 'SUCCEEDED', httpStatus: 200, durationMs: 8, createdAt: '2026-08-25T10:22:00' }],
  deadLetters: [{ id: 'visual-dead-1', direction: 'OUTBOUND', adapterCode: 'PAYMENT_SIMULATOR', referenceId: 'visual-outbox-2', payloadChecksum: 'b'.repeat(64), reason: '视觉固定故障证据', retryCount: 3, status: 'RESOLVED', createdAt: '2026-08-25T10:19:00' }],
  security: { signedCallbacks: true, maxSkewSeconds: 300, secretConfigured: true, secretsReadable: false },
  observability: { health: '/actuator/health', readiness: '/actuator/health/readiness', metrics: '/actuator/prometheus', requestTraceHeader: 'X-Request-Id' },
}
const dashboardConfigurationVisualFixture = {
  communityId: '30000000-0000-0000-0000-000000000001', roleCode: 'ALL', total: 4, publishedCount: 4,
  availableMetrics: ['ARREARS_SUMMARY', 'ASSET_COUNTS', 'COLLECTION_RATE', 'DATA_QUALITY', 'INTEGRATION_STATUS', 'METER_PROGRESS'],
  items: [
    { id: 'visual-widget-1', widget_code: 'ASSET_SUMMARY', widget_name: '资产概览', metric_code: 'ASSET_COUNTS', position_code: 'SUMMARY', visible: true, refresh_interval_seconds: 300, display_order: 1, status: 'PUBLISHED', version: 2 },
    { id: 'visual-widget-2', widget_code: 'FINANCE_OVERVIEW', widget_name: '收费概览', metric_code: 'COLLECTION_RATE', position_code: 'MAIN', visible: true, refresh_interval_seconds: 300, display_order: 2, status: 'PUBLISHED', version: 2 },
    { id: 'visual-widget-3', widget_code: 'DATA_QUALITY', widget_name: '数据质量', metric_code: 'DATA_QUALITY', position_code: 'SIDE', visible: true, refresh_interval_seconds: 600, display_order: 3, status: 'PUBLISHED', version: 2 },
    { id: 'visual-widget-4', widget_code: 'INTEGRATION_STATUS', widget_name: '集成状态', metric_code: 'INTEGRATION_STATUS', position_code: 'SIDE', visible: false, refresh_interval_seconds: 600, display_order: 4, status: 'PUBLISHED', version: 2 },
  ],
}
const visitorVisualFixture = {
  adapterCode: 'IOT_SIMULATOR', simulated: true, productionConnected: false, sensitiveFieldsStoredMasked: true,
  page: 1, size: 20, total: 3,
  items: [
    { id: 'visual-visitor-1', visit_no: 'SYN-VISIT-0003', visitor_name_masked: '合成访客丙**', visitor_mobile_masked: '137****0003', host_name_masked: '合成住户丙**', asset_name: '3号楼-1单元-0301', scheduled_at: '2026-08-25T14:00:00', check_in_at: null, check_out_at: null, visit_status: 'REGISTERED', source_mode: 'IOT_SIMULATOR', production_connected: false, version: 0 },
    { id: 'visual-visitor-2', visit_no: 'SYN-VISIT-0002', visitor_name_masked: '合成访客乙**', visitor_mobile_masked: '139****0002', host_name_masked: '合成住户乙**', asset_name: '2号楼-2单元-0202', scheduled_at: '2026-08-25T10:00:00', check_in_at: '2026-08-25T10:03:00', check_out_at: null, visit_status: 'CHECKED_IN', source_mode: 'IOT_SIMULATOR', production_connected: false, version: 0 },
    { id: 'visual-visitor-3', visit_no: 'SYN-VISIT-0001', visitor_name_masked: '合成访客甲**', visitor_mobile_masked: '138****0001', host_name_masked: '合成住户甲**', asset_name: '1号楼-1单元-0101', scheduled_at: '2026-08-25T08:30:00', check_in_at: '2026-08-25T08:35:00', check_out_at: '2026-08-25T09:15:00', visit_status: 'CHECKED_OUT', source_mode: 'IOT_SIMULATOR', production_connected: false, version: 0 },
  ],
}
const targetViewports = [
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1920x1080', width: 1920, height: 1080 },
] as const

for (const viewport of targetViewports) {
  test(`49-page structural visual baseline at ${viewport.name}`, async ({ page }) => {
    test.setTimeout(600_000)
    if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.route(/\/api\/v1\/dashboard(?:\?.*)?$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dashboardVisualFixture) })
      } else {
        await route.continue()
      }
    })
    await installFixtureRoutes(page, '**/api/v1/finance/**', financialVisualFixtures)
    await installFixtureRoutes(page, '**/api/v1/cashier/**', cashierVisualFixtures)
    await page.route('**/api/v1/meter-workbench*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(meterVisualWorkbench) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/meter-reading-batches/visual-meter-batch*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(meterVisualBatchDetail) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/migrations/batches*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 1, size: 20, total: 0 }) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/integrations/workbench*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(integrationVisualWorkbench) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/dashboard/configurations*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dashboardConfigurationVisualFixture) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/visitors*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(visitorVisualFixture) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/receivable-jobs*', async (route) => {
      const requestUrl = new URL(route.request().url())
      if (route.request().method() === 'GET' && requestUrl.pathname.endsWith('/api/v1/receivable-jobs')) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/reports/**', async (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      const code = new URL(route.request().url()).pathname.split('/').at(-1) || ''
      const catalogPage = governedPageByCode.get(code)
      if (!catalogPage) return route.continue()
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify(governedReportFixture(code, catalogPage)),
      })
    })
    for (const pattern of ['**/api/v1/report-jobs*', '**/api/v1/receipt-print-jobs*', '**/api/v1/notification-batches*']) {
      await page.route(pattern, async (route) => {
        if (route.request().method() === 'GET') {
          await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
        } else {
          await route.continue()
        }
      })
    }
    await page.goto('/login')
    await page.getByRole('textbox', { name: '账号' }).fill(username)
    await page.getByRole('textbox', { name: '密码' }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    for (const catalogPage of visualCatalog) {
      await page.goto(catalogPage.path)
      await expect(page.locator('.page-heading h1')).toHaveText(catalogPage.title)
      await page.locator('.el-loading-mask').first().waitFor({ state: 'detached', timeout: 8_000 }).catch(() => undefined)
      await page.evaluate(() => document.fonts.ready)
      await page.mouse.move(2, 2)
      await page.waitForTimeout(100)
      await expect(page).toHaveScreenshot(`page-${String(catalogPage.pageNo).padStart(2, '0')}-${viewport.name}.png`, {
        animations: 'disabled',
        caret: 'hide',
        maxDiffPixelRatio: 0.008,
      })
    }
  })
}

async function installFixtureRoutes(page: Page, pattern: string, fixtures: Map<string, unknown>) {
  await page.route(pattern, async (route) => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'GET' && fixtures.has(path)) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fixtures.get(path)) })
    } else {
      await route.continue()
    }
  })
}

function governedReportFixture(code: string, catalogPage: VisualCatalogPage) {
  const visibleColumns = catalogPage.columns.map(parseVisualField).filter((field) => field.type !== 'selection')
  const columns = visibleColumns.map((field) => field.key)
  if (code === 'RECEIPT_BATCH_PRINT') columns.unshift('receiptId')
  const row = Object.fromEntries(visibleColumns.map((field) => [field.key, visualReportValue(field)]))
  if (code === 'RECEIPT_BATCH_PRINT') row.receiptId = 'visual-receipt-1'
  if (code === 'BANK_TRUST') Object.assign(row, {
    trustNo: 'SIMULATOR-NOT-SUBMITTED', bankChannel: 'BANK_TRUST_SIMULATOR', submittedCount: 0,
    submittedAmount: 0, successCount: 0, successAmount: 0, reconcileStatus: 'SIMULATOR_ONLY',
  })
  const numericTotals = Object.fromEntries(visibleColumns
    .filter((field) => ['money', 'number'].includes(field.type))
    .map((field) => [field.key, row[field.key]]))
  const simulated = ['BILL_NOTIFICATIONS', 'REMINDERS', 'INVOICE_STATISTICS', 'BANK_TRUST'].includes(code)
  return {
    reportCode: code, title: catalogPage.title, rowGrain: `视觉固定样本 · ${catalogPage.title}`,
    formulaNote: code === 'BANK_TRUST' ? '银行协议未授权，提交与成功数据固定为零。' : '固定视觉算例，汇总可追溯到当前明细行。',
    formula: { visualRule: 'deterministic' }, fixedSample: { input: 100, output: 80 }, columns,
    summary: { rowCount: 1, numericTotals }, rows: [row], page: 1, size: 50, total: 1,
    queryChecksum: '8'.repeat(64), durationMs: 8, drillDown: { reportCode: code, key: columns[0] },
    syntheticEnvironment: true,
    integrationMode: code === 'BANK_TRUST' ? 'BANK_TRUST_SIMULATOR'
      : code === 'INVOICE_STATISTICS' ? 'INVOICE_SIMULATOR'
        : ['BILL_NOTIFICATIONS', 'REMINDERS'].includes(code) ? 'NOTIFICATION_SIMULATOR' : 'INTERNAL_LEDGER',
    productionConnected: !simulated,
  }
}

function visualReportValue(field: { key: string; label: string; type: string }) {
  if (/Id$/.test(field.key)) return `visual-${field.key.toLowerCase()}-1`
  if (/No$/.test(field.key)) return `VIS-${field.key.replace(/No$/, '').toUpperCase()}-0001`
  if (/channel/i.test(field.key)) return 'BANK_TRANSFER'
  if (/status/i.test(field.key)) return 'SUCCEEDED'
  if (/rate/i.test(field.key) || field.type === 'percent') return 82.5
  if (field.type === 'money') return 128.5
  if (field.type === 'number') return 8
  if (field.type === 'datetime') return '2026-08-25T10:30:00'
  if (field.type === 'date') return '2026-08-25'
  if (field.type === 'month') return '2026-08'
  if (field.type === 'masked') return '视*客户'
  return `视觉${field.label}`
}

function parseVisualField(token: string) {
  const [key, label, type] = token.split('|')
  if (!key || !label || !type) throw new Error(`Invalid visual field token: ${token}`)
  return { key, label, type }
}
