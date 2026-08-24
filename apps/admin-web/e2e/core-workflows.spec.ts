import { expect, test } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD

test.beforeEach(async ({ page }) => {
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
})

test('dashboard and core archive data are backed by the API', async ({ page }) => {
  await expect(page.getByRole('heading', { name: '项目看板' })).toBeVisible()
  await expect(page.locator('.topbar')).toContainText('我的应用')
  await expect(page.locator('.sidebar')).toBeVisible()
  await expect(page.locator('.workspace-tabs')).toContainText('项目看板')
  await expect(page.locator('.stat-card').filter({ hasText: '房屋资产' })).toContainText('359')
  await page.goto('/archives/rooms')
  await expect(page.getByRole('heading', { name: '房产信息' })).toBeVisible()
  await expect(page.locator('.workspace-tab.active')).toContainText('房产信息')
  await expect(page.locator('.record-summary')).toContainText('359')
})

test('receivable, cashier and meter workflow pages load real data', async ({ page }) => {
  await page.goto('/fees/receivables')
  await expect(page.getByRole('heading', { name: '选择账期与计费资产' })).toBeVisible()
  await expect(page.getByRole('row', { name: /R0001/ })).toBeVisible()

  await page.goto('/cashier')
  await expect(page.getByRole('heading', { name: '待收账单' })).toBeVisible()
  await expect(page.getByRole('row', { name: /SYN-BILL-202607-0001/ })).toBeVisible()

  await page.goto('/metering/batches')
  await expect(page.getByRole('heading', { name: '新建抄表批次' })).toBeVisible()
  const batchNo = `E2E-METER-${Date.now()}`
  await page.getByRole('textbox', { name: '批次号' }).fill(batchNo)
  await page.getByRole('button', { name: '创建批次' }).click()
  await expect(page.getByRole('row', { name: new RegExp(batchNo) })).toBeVisible()
})
