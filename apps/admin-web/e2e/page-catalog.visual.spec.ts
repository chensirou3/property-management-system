import { expect, test, type Page } from '@playwright/test'
import { createRequire } from 'node:module'

const pageCatalog = createRequire(import.meta.url)('../src/config/page-catalog.json').pages as Array<{
  pageNo: number
  title: string
  path: string
}>

const username = process.env.PMS_E2E_USERNAME || 'admin'
const password = process.env.PMS_E2E_PASSWORD
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
    await page.route('**/api/v1/receivable-jobs*', async (route) => {
      const requestUrl = new URL(route.request().url())
      if (route.request().method() === 'GET' && requestUrl.pathname.endsWith('/api/v1/receivable-jobs')) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
      } else {
        await route.continue()
      }
    })
    await page.goto('/login')
    await page.getByRole('textbox', { name: '账号' }).fill(username)
    await page.getByRole('textbox', { name: '密码' }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    for (const catalogPage of pageCatalog) {
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
