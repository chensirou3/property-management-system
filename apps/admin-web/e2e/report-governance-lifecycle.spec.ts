import { createHash } from 'node:crypto'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const project = '30000000-0000-0000-0000-000000000001'

test('G8 governed reports, exports, receipt printing and notification evidence complete a browser lifecycle', async ({ page }) => {
  test.setTimeout(120_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await login(page)
  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }
  const suffix = Date.now().toString(36)

  const catalog = await apiJson(page.request, `/api/v1/reports/catalog?communityId=${project}`, headers)
  expect(catalog).toHaveLength(22)
  for (const definition of catalog) {
    const report = await apiJson(page.request,
      `/api/v1/reports/${definition.report_code}?communityId=${project}&page=1&size=10`, headers)
    expect(report).toEqual(expect.objectContaining({
      reportCode: definition.report_code, rowGrain: expect.any(String), formulaNote: expect.any(String),
      queryChecksum: expect.stringMatching(/^[a-f0-9]{64}$/), rows: expect.any(Array),
    }))
    expect(report.summary.rowCount).toBe(report.total)
  }
  const missingProject = await page.request.get('/api/v1/reports/ARREARS', { headers })
  expect(missingProject.status()).toBe(400)
  expect((await missingProject.json()).code).toBe('VALIDATION_FAILED')

  const dashboard = await apiJson(page.request, `/api/v1/dashboard?communityId=${project}`, headers)
  const collectionRate = await apiJson(page.request, `/api/v1/reports/COLLECTION_RATE?communityId=${project}`, headers)
  expect(dashboard.finance.metricSource).toBe('COLLECTION_RATE')
  expect(Number(dashboard.finance.receivable)).toBe(Number(collectionRate.rows[0].receivableAmount))

  const existingExports = await apiJson(page.request, `/api/v1/report-jobs?communityId=${project}`, headers)
  const existingExportIds = new Set(existingExports.map((job: Record<string, any>) => job.id))
  await page.goto('/reports/collection-rate')
  await expect(page.getByRole('heading', { name: '收缴率报表', exact: true, level: 1 })).toBeVisible()
  await expect(page.getByText('收缴率=已收/应收×100', { exact: false })).toBeVisible()
  await expect(page.getByText(collectionRate.queryChecksum, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '异步导出', exact: true }).click()
  await expect(page.getByText('异步导出任务已入队', { exact: false })).toBeVisible()

  let exportJob: Record<string, any> | undefined
  await expect.poll(async () => {
    const jobs = await apiJson(page.request, `/api/v1/report-jobs?communityId=${project}`, headers)
    exportJob = jobs.find((job: Record<string, any>) =>
      job.report_code === 'COLLECTION_RATE' && !existingExportIds.has(job.id))
    return exportJob?.status
  }, { timeout: 20_000 }).toBe('SUCCEEDED')
  expect(exportJob?.artifact_checksum).toMatch(/^[a-f0-9]{64}$/)
  const artifact = await page.request.get(`/api/v1/report-jobs/${exportJob!.id}/artifact?communityId=${project}`, { headers })
  expect(artifact.ok()).toBeTruthy()
  const artifactBytes = await artifact.body()
  expect(artifactBytes.subarray(0, 2).toString()).toBe('PK')
  expect(createHash('sha256').update(artifactBytes).digest('hex')).toBe(exportJob!.artifact_checksum)

  const receiptReportBefore = await apiJson(page.request,
    `/api/v1/reports/RECEIPT_BATCH_PRINT?communityId=${project}&receiptStatus=ISSUED&page=1&size=10`, headers)
  expect(receiptReportBefore.rows.length, '至少需要一张已签发收据用于浏览器打印验收').toBeGreaterThan(0)
  const receipt = receiptReportBefore.rows[0]
  const existingPrints = await apiJson(page.request, `/api/v1/receipt-print-jobs?communityId=${project}`, headers)
  const existingPrintIds = new Set(existingPrints.map((job: Record<string, any>) => job.id))
  await page.goto('/finance/receipt-batch-print')
  await expect(page.getByRole('columnheader', { name: '收据号' })).toBeVisible()
  await page.getByRole('combobox', { name: '收据状态' }).click()
  await page.getByRole('option', { name: 'ISSUED', exact: true }).click()
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: receipt.receiptNo }).first()
  await expect(row).toBeVisible()
  await row.locator('.el-checkbox').click()
  await page.getByRole('button', { name: '批量打印所选', exact: true }).click()
  await expect(page.getByText('批量打印任务已入队', { exact: false })).toBeVisible()

  let printJob: Record<string, any> | undefined
  await expect.poll(async () => {
    const jobs = await apiJson(page.request, `/api/v1/receipt-print-jobs?communityId=${project}`, headers)
    printJob = jobs.find((job: Record<string, any>) => !existingPrintIds.has(job.id))
    return printJob?.status
  }, { timeout: 20_000 }).toBe('SUCCEEDED')
  expect(printJob?.artifact_checksum).toMatch(/^[a-f0-9]{64}$/)
  const receiptReportAfter = await apiJson(page.request,
    `/api/v1/reports/RECEIPT_BATCH_PRINT?communityId=${project}&page=1&size=50`, headers)
  const printedReceipt = receiptReportAfter.rows.find((item: Record<string, any>) => item.receiptId === receipt.receiptId)
  expect(Number(printedReceipt.printCount)).toBe(Number(receipt.printCount) + 1)

  const arrears = await apiJson(page.request, `/api/v1/reports/ARREARS?communityId=${project}&page=1&size=50`, headers)
  const bill = arrears.rows.find((item: Record<string, any>) => item.billId && item.billingPeriod)
  expect(bill, '至少需要一张欠费账单用于模拟通知验收').toBeTruthy()
  const notification = await apiJson(page.request, '/api/v1/notification-batches', headers, {
    communityId: project, billingPeriod: bill.billingPeriod, channel: 'SMS_SIMULATOR', billIds: [bill.billId],
    contentTemplate: `G8-${suffix} 账单 {billNo} 资产 {asset} 待缴 {amount}`,
  }, `e2e-g8-notify-${suffix}`)
  expect(notification).toEqual(expect.objectContaining({ simulated: true, status: 'SUCCEEDED' }))
  expect(notification.messages[0].status).toBe('SENT_SIMULATED')
  expect(notification.messages[0].content_checksum).toMatch(/^[a-f0-9]{64}$/)
  expect(notification.messages[0].masked_recipient).toContain('*')
  await page.goto('/finance/bill-notifications')
  await expect(page.getByText('模拟通知', { exact: false }).first()).toBeVisible()
  await expect(page.getByText(notification.batch_no, { exact: true })).toBeVisible()

  await page.goto('/finance/bank-trust')
  await expect(page.getByText('BANK_TRUST_SIMULATOR', { exact: true })).toBeVisible()
  const bank = await apiJson(page.request, `/api/v1/reports/BANK_TRUST?communityId=${project}`, headers)
  expect(bank).toEqual(expect.objectContaining({ integrationMode: 'BANK_TRUST_SIMULATOR', productionConnected: false }))
  expect(bank.rows[0]).toEqual(expect.objectContaining({ submittedCount: 0, successCount: 0, reconcileStatus: 'SIMULATOR_ONLY' }))
})

async function apiJson(request: APIRequestContext, url: string, headers: Record<string, string>,
                       data?: unknown, idempotencyKey?: string) {
  const response = data === undefined
    ? await request.get(url, { headers })
    : await request.post(url, {
      headers: idempotencyKey ? { ...headers, 'Idempotency-Key': idempotencyKey } : headers, data,
    })
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
