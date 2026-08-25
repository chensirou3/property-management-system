import { expect, test } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD

test('migration center completes the governed five-layer lifecycle', async ({ page }) => {
  test.setTimeout(90_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  await page.goto('/system/migrations')
  await expect(page.getByRole('heading', { name: '数据迁移' })).toBeVisible()
  await expect(page.getByText(/Raw、Quarantine、Canonical、Staging、Production/)).toBeVisible()
  await page.getByRole('button', { name: '载入 32+1 验收样本' }).click()
  const createDialog = page.getByRole('dialog', { name: '创建迁移批次' })
  await expect(createDialog).toContainText('PROJECT 1')
  await expect(createDialog).toContainText('RELATION 11')
  await expect(createDialog).toContainText('共 33 条')
  await createDialog.getByRole('button', { name: '写入 Raw 层' }).click()

  const drawer = page.locator('.el-drawer:not(.task-drawer)')
  await expect(drawer).toContainText('Raw')
  await expect(drawer.locator('.stage-card').filter({ hasText: 'Raw' })).toContainText('33')
  await drawer.getByRole('button', { name: '执行校验' }).click()
  await expect(drawer.getByText('部分失败', { exact: true })).toBeVisible()
  await expect(drawer.locator('.stage-card').filter({ hasText: 'Quarantine' })).toContainText('1')
  await expect(drawer.getByRole('cell', { name: /CUSTOMER 引用不存在/ })).toBeVisible()

  await drawer.getByRole('button', { name: '审批批次' }).click()
  await page.getByRole('dialog', { name: '迁移审批' }).getByRole('button', { name: '确认审批' }).click()
  await expect(drawer.getByText('已审批', { exact: true })).toBeVisible()

  await drawer.getByRole('button', { name: '写入生产层' }).click()
  await page.getByRole('dialog', { name: '写入生产层' }).getByRole('button', { name: '确认执行' }).click()
  await expect(drawer.getByText('已完成', { exact: true })).toBeVisible()
  await expect(drawer.locator('.stage-card').filter({ hasText: 'Production' })).toContainText('31')

  await drawer.getByRole('button', { name: '执行对账' }).click()
  await expect(drawer.getByText('已对账', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: /对账结果/ }).click()
  await expect(drawer.getByRole('cell', { name: 'BUILDING_AREA_SUM' })).toBeVisible()
  await expect(drawer.getByText('不一致', { exact: true })).toHaveCount(0)

  const rollbackToken = await drawer.locator('code').textContent()
  expect(rollbackToken).toBeTruthy()
  await drawer.getByRole('button', { name: '逆序回滚' }).click()
  const rollbackDialog = page.getByRole('dialog', { name: '逆序回滚生产写入' })
  await rollbackDialog.getByRole('textbox', { name: '回滚凭证' }).fill(rollbackToken || '')
  await rollbackDialog.getByRole('textbox', { name: '回滚原因' }).fill('Playwright G4 可逆性验收')
  await rollbackDialog.getByRole('button', { name: '确认回滚' }).click()
  await expect(drawer.getByText('已回滚', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: /对账结果/ }).click()
  const rollbackMetric = drawer.getByRole('row').filter({ hasText: 'ROLLBACK_REMAINING_TARGET_COUNT' })
  await expect(rollbackMetric).toContainText('一致')
})
