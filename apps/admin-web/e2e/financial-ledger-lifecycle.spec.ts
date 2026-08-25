import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const project = '30000000-0000-0000-0000-000000000001'

test('G6 cashier and immutable ledger complete the governed financial lifecycle', async ({ page }) => {
  test.setTimeout(90_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await login(page)
  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }
  const suffix = Date.now().toString(36)

  const bills = await apiJson(page.request, `/api/v1/finance/bills?communityId=${project}&status=UNPAID&size=50`, headers)
  const eligible = bills.items.filter((bill: Record<string, any>) => bill.customer_name && Number(bill.outstanding_amount) >= 10)
  expect(eligible.length).toBeGreaterThanOrEqual(4)

  await page.goto('/finance/bills')
  await expect(page.getByRole('heading', { name: '应收管理', exact: true, level: 1 })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '原应收' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '调整' })).toBeVisible()

  const firstKey = `e2e-g6-payment-${suffix}`
  const firstPayment = { communityId: project, paymentMethod: 'QR_SIMULATOR', bills: [
    { billId: eligible[0].id, amount: 3.21 }, { billId: eligible[1].id, amount: 4.32 },
  ] }
  const firstOrder = await apiJson(page.request, '/api/v1/payment-orders', headers, firstPayment, firstKey)
  const replayedOrder = await apiJson(page.request, '/api/v1/payment-orders', headers, firstPayment, firstKey)
  expect(replayedOrder.orderId).toBe(firstOrder.orderId)
  expect(replayedOrder.replayed).toBeTruthy()
  const conflictingOrder = await page.request.post('/api/v1/payment-orders', {
    headers: { ...headers, 'Idempotency-Key': firstKey },
    data: { communityId: project, paymentMethod: 'QR_SIMULATOR', bills: [{ billId: eligible[0].id, amount: 1.11 }] },
  })
  expect(conflictingOrder.status()).toBe(409)

  const confirmations = await Promise.all([
    page.request.post(`/api/v1/payment-orders/${firstOrder.orderId}:confirm-simulated?communityId=${project}`, { headers }),
    page.request.post(`/api/v1/payment-orders/${firstOrder.orderId}:confirm-simulated?communityId=${project}`, { headers }),
  ])
  expect(confirmations.every((response) => response.ok())).toBeTruthy()
  const confirmationBodies = await Promise.all(confirmations.map((response) => response.json()))
  expect(confirmationBodies.map((body) => body.replayed).sort()).toEqual([false, true])
  const firstConfirmed = confirmationBodies.find((body) => !body.replayed)
  const reversal = await apiJson(page.request, `/api/v1/payment-transactions/${firstConfirmed.transactionId}:reverse`, headers,
    { communityId: project, reason: 'G6 浏览器验收冲正' })
  expect(reversal.status).toBe('REVERSED')
  const reversalReplay = await apiJson(page.request, `/api/v1/payment-transactions/${firstConfirmed.transactionId}:reverse`, headers,
    { communityId: project, reason: '重复调用应重放既有结果' })
  expect(reversalReplay.replayed).toBeTruthy()

  const customerId = eligible[2].customer_id
  const prepayment = await apiJson(page.request, '/api/v1/prepayment-accounts', headers,
    { communityId: project, customerId })
  await apiJson(page.request, `/api/v1/prepayment-accounts/${prepayment.id}:top-up`, headers,
    { communityId: project, amount: 20, reason: 'G6 E2E 预收充值' }, `e2e-g6-topup-${suffix}`)
  const prepaymentOrder = await apiJson(page.request, `/api/v1/prepayment-accounts/${prepayment.id}:apply`, headers,
    { communityId: project, bills: [{ billId: eligible[2].id, amount: 4.56 }] }, `e2e-g6-prepay-${suffix}`)
  await apiJson(page.request, `/api/v1/payment-transactions/${prepaymentOrder.transactionId}:reverse`, headers,
    { communityId: project, reason: 'G6 E2E 验证预收恢复' })

  const deposit = await apiJson(page.request, '/api/v1/deposits', headers, {
    communityId: project, customerId, assetId: eligible[2].asset_id,
    depositType: `E2E_${suffix}`.slice(0, 40), amount: 10, reason: 'G6 E2E 押金收取',
  }, `e2e-g6-deposit-${suffix}`)
  const refunded = await apiJson(page.request, `/api/v1/deposits/${deposit.accountId}:refund`, headers,
    { communityId: project, amount: 10, reason: 'G6 E2E 押金退还' }, `e2e-g6-refund-${suffix}`)
  expect(Number(refunded.balanceAfter)).toBe(0)

  const adjustment = await apiJson(page.request, '/api/v1/finance/adjustments', headers, {
    communityId: project, billId: eligible[3].id, adjustmentType: 'WAIVER', amount: 0.5,
    reason: 'G6 E2E 小额减免审批',
  }, `e2e-g6-adjustment-${suffix}`)
  const appliedAdjustment = await apiJson(page.request, `/api/v1/finance/adjustments/${adjustment.id}:approve`, headers,
    { communityId: project, expectedVersion: adjustment.version, reason: 'G6 E2E 审批通过' })
  expect(appliedAdjustment.status).toBe('APPLIED')

  const secondOrder = await apiJson(page.request, '/api/v1/payment-orders', headers, {
    communityId: project, paymentMethod: 'BANK_TRANSFER', bills: [{ billId: eligible[3].id, amount: 2.22 }],
  }, `e2e-g6-invoice-payment-${suffix}`)
  const secondConfirmed = await apiJson(page.request,
    `/api/v1/payment-orders/${secondOrder.orderId}:confirm-simulated?communityId=${project}`, headers, {})
  const invoice = await apiJson(page.request, '/api/v1/invoices:simulate', headers,
    { communityId: project, receiptId: secondConfirmed.receiptId, title: `G6 E2E 抬头 ${suffix}` })
  const replacedInvoice = await apiJson(page.request, `/api/v1/finance/invoices/${invoice.id}:operate`, headers,
    { communityId: project, operationType: 'REPLACE', title: `G6 E2E 换开 ${suffix}`, reason: '换开发票验收' })
  expect(replacedInvoice.operation_type).toBe('REPLACE')
  const redInvoice = await apiJson(page.request, `/api/v1/finance/invoices/${invoice.id}:operate`, headers,
    { communityId: project, operationType: 'RED', title: `G6 E2E 抬头 ${suffix}`, reason: '红冲发票验收' })
  expect(Number(redInvoice.amount)).toBe(-2.22)
  const replacementReceipt = await apiJson(page.request,
    `/api/v1/finance/receipts/${secondConfirmed.receiptId}:replace`, headers,
    { communityId: project, reason: 'G6 E2E 收据换开' })
  expect(replacementReceipt.original_receipt_id).toBe(secondConfirmed.receiptId)

  const settlementDate = new Date(Date.UTC(2000, 0, 1) + (Date.now() % 8_000) * 86400000).toISOString().slice(0, 10)
  const settlement = await apiJson(page.request, '/api/v1/finance/settlements', headers,
    { communityId: project, settlementDate }, `e2e-g6-settlement-${suffix}`)
  const lockedSettlement = await apiJson(page.request, `/api/v1/finance/settlements/${settlement.id}:lock`, headers,
    { communityId: project, expectedVersion: settlement.version, reason: 'G6 E2E 空日结锁定验证' })
  expect(lockedSettlement.status).toBe('LOCKED')

  await page.goto('/finance/adjustments')
  await expect(page.getByRole('row').filter({ hasText: adjustment.adjustment_no })).toContainText('APPLIED')
  await page.goto('/finance/payments')
  await expect(page.getByRole('columnheader', { name: '交易号' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'REVERSAL' }).first()).toBeVisible()
  await page.goto('/finance/deposits')
  await expect(page.getByRole('heading', { name: '押金记录表', exact: true, level: 1 })).toBeVisible()
  await page.goto('/finance/invoice-replacements')
  await expect(page.getByRole('row').filter({ hasText: replacedInvoice.request_no })).toContainText('REPLACE')
  await page.goto(`/reports/daily-settlement-details`)
  await expect(page.getByRole('row').filter({ hasText: settlementDate })).toContainText('LOCKED')

  const reconciliation = await apiJson(page.request, `/api/v1/finance/reconciliation?communityId=${project}`, headers)
  expect(reconciliation).toEqual(expect.objectContaining({
    healthy: true, billMismatches: 0, paymentMismatches: 0,
    prepaymentMismatches: 0, depositMismatches: 0, settlementMismatches: 0,
  }))
})

async function apiJson(request: APIRequestContext, url: string, headers: Record<string, string>, data?: unknown, idempotencyKey?: string) {
  const response = data === undefined
    ? await request.get(url, { headers })
    : await request.post(url, { headers: idempotencyKey ? { ...headers, 'Idempotency-Key': idempotencyKey } : headers, data })
  expect(response.ok(), `${response.status()} ${url}: ${await response.text()}`).toBeTruthy()
  return response.json()
}

async function login(page: Page) {
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password || '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}
