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
