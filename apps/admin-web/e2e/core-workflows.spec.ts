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

test('ordinary employee is restricted to the granted project and cannot open IAM pages', async ({ page }) => {
  const adminToken = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(adminToken).toBeTruthy()
  const suffix = Date.now()
  const username = `e2e-project-user-${suffix}`
  const employeePassword = `E2E-project-scope-${suffix}!`
  const primaryProject = '30000000-0000-0000-0000-000000000001'
  const isolatedProject = '30000000-0000-0000-0000-000000000002'
  const projectManagerRole = '10000000-0000-0000-0000-000000000002'

  const createResponse = await page.request.post('/api/v1/iam/users', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      username,
      password: employeePassword,
      displayName: 'E2E 项目员工',
      employeeId: null,
      enabled: true,
      roleIds: [projectManagerRole],
      projectIds: [primaryProject],
    },
  })
  expect(createResponse.ok(), await createResponse.text()).toBeTruthy()
  const createdUser = await createResponse.json()

  try {
    await page.evaluate(() => {
      sessionStorage.clear()
      localStorage.clear()
    })
    await page.goto('/login')
    await page.getByRole('textbox', { name: '账号' }).fill(username)
    await page.getByRole('textbox', { name: '密码' }).fill(employeePassword)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.locator('.project-select .el-select__placeholder')).toContainText('优山美地（合成示范项目）')

    const employeeToken = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
    const forbiddenResponse = await page.request.get(`/api/v1/data/assets?communityId=${isolatedProject}`, {
      headers: { Authorization: `Bearer ${employeeToken}` },
    })
    expect(forbiddenResponse.status()).toBe(403)
    expect((await forbiddenResponse.json()).code).toBe('PROJECT_ACCESS_DENIED')

    await page.goto('/enterprise/accounts')
    await expect(page).toHaveURL(/\/forbidden$/)
    await expect(page.getByText('当前角色没有该页面或项目的数据权限')).toBeVisible()
  } finally {
    const cleanupResponse = await page.request.put(`/api/v1/iam/users/${createdUser.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        displayName: createdUser.displayName,
        employeeId: createdUser.employeeId,
        enabled: false,
        passwordChangeRequired: createdUser.passwordChangeRequired,
        roleIds: createdUser.roleIds,
        projectIds: createdUser.projectIds,
        expectedVersion: createdUser.version,
      },
    })
    expect(cleanupResponse.ok(), await cleanupResponse.text()).toBeTruthy()
  }
})
