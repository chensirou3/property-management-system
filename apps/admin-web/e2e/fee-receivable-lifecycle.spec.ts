import { expect, test, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD

test('G5 fee workspaces complete periodic and temporary receivable lifecycles', async ({ page }) => {
  test.setTimeout(90_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await login(page)

  await page.goto('/fees/definitions')
  await expect(page.getByRole('heading', { name: '费用定义与财税口径' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '舍入' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '会计科目' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'FEE-TEMP-001' })).toContainText('启用')

  await page.goto('/fees/allocations')
  await expect(page.getByRole('heading', { name: '费用分配与生效范围' })).toBeVisible()
  await expect(page.getByText(/批量分配先预览命中对象/)).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '对象编码' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '已分配费用与有效期' })).toBeVisible()

  await page.goto('/fees/receivables')
  await expect(page.getByRole('heading', { name: '选择账期与计费资产' })).toBeVisible()
  const assetCard = page.locator('.workflow-card').first()
  await assetCard.getByRole('textbox', { name: '房屋检索' }).fill('R0100')
  await assetCard.getByRole('button', { name: '查询' }).click()
  const unbilledAssetRow = assetCard.getByRole('row').filter({ hasText: 'R0100' })
  await expect(unbilledAssetRow).toBeVisible()
  await unbilledAssetRow.locator('.el-checkbox').click()
  await expect(assetCard.getByText('已选择 1 项')).toBeVisible()
  await assetCard.getByRole('button', { name: '计算应收预览' }).click()
  await expect(page.getByRole('heading', { name: '试算明细与版本证据' })).toBeVisible()
  await expect(page.getByText('整批配置校验值')).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '舍入' })).toBeVisible()
  await assetCard.getByRole('button', { name: '创建异步生成任务' }).click()
  const periodicDrawer = page.locator('.el-drawer')
  await expect(periodicDrawer.getByText('已完成', { exact: true })).toBeVisible({ timeout: 15_000 })
  await expect(periodicDrawer.getByRole('heading', { name: '配置—应收—账单对账' })).toBeVisible()
  await expect(periodicDrawer.getByText('不一致', { exact: true })).toHaveCount(0)
  await periodicDrawer.getByRole('button', { name: '关闭此对话框' }).click()

  await page.goto('/fees/temporary-receivables')
  await expect(page.getByRole('heading', { name: '临时费用明细' })).toBeVisible()
  await expect(page.getByText('FEE-TEMP-001 · 临时服务费（合成示范）').first()).toBeVisible()
  await page.getByPlaceholder('临时费用说明').fill('G5 浏览器验收临时服务费')
  await page.getByPlaceholder('单价').fill('12.34')
  await page.getByRole('button', { name: '校验并预览' }).click()
  await expect(page.getByRole('heading', { name: '校验与金额快照' })).toBeVisible()
  await expect(page.getByText('¥ 12.34')).toBeVisible()
  await page.getByRole('button', { name: '确认生成临时应收' }).click()
  const temporaryDrawer = page.locator('.el-drawer')
  await expect(temporaryDrawer.getByText('已完成', { exact: true })).toBeVisible({ timeout: 15_000 })
  await expect(temporaryDrawer.getByText('不一致', { exact: true })).toHaveCount(0)
})

async function login(page: Page) {
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password || '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}
