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
  const changedPassword = `Changed-E2E-scope-${suffix}!`
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
    await expect(page).toHaveURL(/\/change-password$/)
    await page.getByRole('textbox', { name: '当前密码' }).fill(employeePassword)
    await page.getByRole('textbox', { name: '新密码', exact: true }).fill(changedPassword)
    await page.getByRole('textbox', { name: '确认新密码' }).fill(changedPassword)
    await page.getByRole('button', { name: '确认修改' }).click()
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
        expectedVersion: createdUser.version + 1,
      },
    })
    expect(cleanupResponse.ok(), await cleanupResponse.text()).toBeTruthy()
  }
})

test('IAM read-only role can inspect accounts but cannot mutate data', async ({ page }) => {
  const adminToken = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(adminToken).toBeTruthy()
  const suffix = Date.now()
  const username = `e2e-iam-reader-${suffix}`
  const initialPassword = `E2E-Iam-read-${suffix}!`
  const changedPassword = `Changed-E2E-Iam-${suffix}!`
  const primaryProject = '30000000-0000-0000-0000-000000000001'

  const permissionsResponse = await page.request.get('/api/v1/iam/permissions', {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(permissionsResponse.ok(), await permissionsResponse.text()).toBeTruthy()
  const permissions = await permissionsResponse.json()
  const iamReadPermission = permissions.find((item: { code: string }) => item.code === 'iam:read')
  expect(iamReadPermission).toBeTruthy()

  const roleResponse = await page.request.post('/api/v1/iam/roles', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      enterpriseId: null,
      code: `E2E_IAM_READ_${suffix}`,
      name: `E2E IAM 只读 ${suffix}`,
      description: 'E2E temporary read-only role',
      permissionIds: [iamReadPermission.id],
    },
  })
  expect(roleResponse.ok(), await roleResponse.text()).toBeTruthy()
  const createdRole = await roleResponse.json()

  const userResponse = await page.request.post('/api/v1/iam/users', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      username,
      password: initialPassword,
      displayName: 'E2E IAM 只读账号',
      employeeId: null,
      enabled: true,
      roleIds: [createdRole.id],
      projectIds: [primaryProject],
    },
  })
  expect(userResponse.ok(), await userResponse.text()).toBeTruthy()
  const createdUser = await userResponse.json()

  try {
    await page.evaluate(() => {
      sessionStorage.clear()
      localStorage.clear()
    })
    await page.goto('/login')
    await page.getByRole('textbox', { name: '账号' }).fill(username)
    await page.getByRole('textbox', { name: '密码' }).fill(initialPassword)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/change-password$/)
    await page.getByRole('textbox', { name: '当前密码' }).fill(initialPassword)
    await page.getByRole('textbox', { name: '新密码', exact: true }).fill(changedPassword)
    await page.getByRole('textbox', { name: '确认新密码' }).fill(changedPassword)
    await page.getByRole('button', { name: '确认修改' }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    await page.goto('/enterprise/accounts')
    await expect(page).toHaveURL(/\/enterprise\/accounts$/)
    await expect(page.locator('.iam-summary-card').filter({ hasText: '当前模块' })).toContainText('账号与项目权限')
    await expect(page.getByRole('button', { name: '新增账号' })).toHaveCount(0)
    await expect(page.getByRole('columnheader', { name: '操作' })).toHaveCount(0)

    const readerToken = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
    const forbiddenWrite = await page.request.post('/api/v1/iam/enterprises', {
      headers: { Authorization: `Bearer ${readerToken}` },
      data: { code: `E2E_DENIED_${suffix}`, name: '不应创建的企业' },
    })
    expect(forbiddenWrite.status()).toBe(403)
    expect((await forbiddenWrite.json()).code).toBe('PERMISSION_DENIED')
  } finally {
    const disableUserResponse = await page.request.put(`/api/v1/iam/users/${createdUser.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        displayName: createdUser.displayName,
        employeeId: createdUser.employeeId,
        enabled: false,
        passwordChangeRequired: false,
        roleIds: createdUser.roleIds,
        projectIds: createdUser.projectIds,
        expectedVersion: createdUser.version + 1,
      },
    })
    expect(disableUserResponse.ok(), await disableUserResponse.text()).toBeTruthy()

    const disableRoleResponse = await page.request.put(`/api/v1/iam/roles/${createdRole.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        name: createdRole.name,
        description: createdRole.description,
        enabled: false,
        permissionIds: createdRole.permissionIds,
        expectedVersion: createdRole.version,
      },
    })
    expect(disableRoleResponse.ok(), await disableRoleResponse.text()).toBeTruthy()
  }
})

test('administrator completes the IAM relationship and status lifecycle across all six pages', async ({ page }) => {
  const adminToken = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(adminToken).toBeTruthy()
  const headers = { Authorization: `Bearer ${adminToken}` }
  const suffix = Date.now()
  const seededEnterprise = '31000000-0000-0000-0000-000000000001'
  const primaryProject = '30000000-0000-0000-0000-000000000001'
  const initialPassword = `Lifecycle-Initial-${suffix}!`
  const resetPassword = `Lifecycle-Reset-${suffix}!`
  const names = {
    enterprise: `E2E 生命周期企业 ${suffix}`,
    organization: `E2E 生命周期组织 ${suffix}`,
    position: `E2E 生命周期岗位 ${suffix}`,
    employee: `E2E 生命周期人员 ${suffix}`,
    role: `E2E 生命周期角色 ${suffix}`,
    account: `E2E 生命周期账号 ${suffix}`,
  }
  let enterprise: Record<string, any> | undefined
  let organization: Record<string, any> | undefined
  let position: Record<string, any> | undefined
  let employee: Record<string, any> | undefined
  let role: Record<string, any> | undefined
  let account: Record<string, any> | undefined

  async function expectApiOk(response: Awaited<ReturnType<typeof page.request.get>>) {
    expect(response.ok(), await response.text()).toBeTruthy()
    return response.json()
  }

  async function list(endpoint: string) {
    return expectApiOk(await page.request.get(`/api/v1/iam/${endpoint}`, { headers }))
  }

  async function expectVisibleRecord(path: string, title: string, needle: string, statusText?: string) {
    await page.goto(path)
    await expect(page.locator('.iam-summary-card').filter({ hasText: '当前模块' })).toContainText(title)
    await page.getByPlaceholder(`搜索${title}`).fill(needle)
    const row = page.getByRole('row').filter({ hasText: needle })
    await expect(row).toBeVisible()
    if (statusText) await expect(row).toContainText(statusText)
  }

  async function current(endpoint: string, id: string) {
    const items = await list(endpoint)
    return items.find((item: Record<string, any>) => item.id === id)
  }

  try {
    const permissions = await list('permissions')
    const iamRead = permissions.find((item: Record<string, any>) => item.code === 'iam:read')
    expect(iamRead).toBeTruthy()

    enterprise = await expectApiOk(await page.request.post('/api/v1/iam/enterprises', {
      headers,
      data: { code: `E2E_ENT_${suffix}`, name: names.enterprise },
    }))
    const duplicateEnterprise = await page.request.post('/api/v1/iam/enterprises', {
      headers,
      data: { code: `E2E_ENT_${suffix}`, name: '不应创建的重复企业' },
    })
    expect(duplicateEnterprise.status()).toBe(409)
    expect((await duplicateEnterprise.json()).code).toBe('IAM_DUPLICATE_OR_INVALID_REFERENCE')

    const staleEnterprise = await page.request.put(`/api/v1/iam/enterprises/${enterprise.id}`, {
      headers,
      data: { name: names.enterprise, status: 'ACTIVE', expectedVersion: enterprise.version + 1 },
    })
    expect(staleEnterprise.status()).toBe(409)
    expect((await staleEnterprise.json()).code).toBe('OPTIMISTIC_LOCK_CONFLICT')

    const crossEnterpriseOrganization = await page.request.post('/api/v1/iam/organizations', {
      headers,
      data: {
        enterpriseId: enterprise.id,
        communityId: primaryProject,
        code: `E2E_INVALID_ORG_${suffix}`,
        name: '不应创建的跨企业组织',
        organizationType: 'PROJECT',
        sortOrder: 1,
      },
    })
    expect(crossEnterpriseOrganization.status()).toBe(422)
    expect((await crossEnterpriseOrganization.json()).code).toBe('IAM_INVALID_REFERENCE')

    organization = await expectApiOk(await page.request.post('/api/v1/iam/organizations', {
      headers,
      data: {
        enterpriseId: seededEnterprise,
        code: `E2E_ORG_${suffix}`,
        name: names.organization,
        organizationType: 'DEPARTMENT',
        sortOrder: 90,
      },
    }))
    position = await expectApiOk(await page.request.post('/api/v1/iam/positions', {
      headers,
      data: {
        enterpriseId: seededEnterprise,
        organizationId: organization.id,
        code: `E2E_POS_${suffix}`,
        name: names.position,
        description: 'E2E lifecycle position',
      },
    }))
    employee = await expectApiOk(await page.request.post('/api/v1/iam/employees', {
      headers,
      data: {
        enterpriseId: seededEnterprise,
        organizationId: organization.id,
        positionId: position.id,
        employeeNo: `E2E_EMP_${suffix}`,
        displayName: names.employee,
        mobileMasked: '137****2026',
        hireDate: '2026-08-01',
      },
    }))
    role = await expectApiOk(await page.request.post('/api/v1/iam/roles', {
      headers,
      data: {
        enterpriseId: seededEnterprise,
        code: `E2E_ROLE_${suffix}`,
        name: names.role,
        description: 'E2E lifecycle role',
        permissionIds: [iamRead.id],
      },
    }))
    account = await expectApiOk(await page.request.post('/api/v1/iam/users', {
      headers,
      data: {
        username: `e2e-lifecycle-${suffix}`,
        password: initialPassword,
        displayName: names.account,
        employeeId: employee.id,
        enabled: true,
        roleIds: [role.id],
        projectIds: [primaryProject],
      },
    }))

    await expectVisibleRecord('/enterprise/enterprises', '企业管理', names.enterprise, 'ACTIVE')
    await expectVisibleRecord('/enterprise/organizations', '组织管理', names.organization, 'ACTIVE')
    await expectVisibleRecord('/enterprise/positions', '岗位管理', names.position, 'ACTIVE')
    await expectVisibleRecord('/enterprise/employees', '人员管理', names.employee, 'ACTIVE')
    await expectVisibleRecord('/enterprise/roles', '角色管理', names.role, '启用')
    await expectVisibleRecord('/enterprise/accounts', '账号与项目权限', names.account, '启用')

    const roleInUse = await page.request.put(`/api/v1/iam/roles/${role.id}`, {
      headers,
      data: { name: names.role, description: role.description, enabled: false,
        permissionIds: role.permissionIds, expectedVersion: role.version },
    })
    expect(roleInUse.status()).toBe(409)
    expect((await roleInUse.json()).code).toBe('IAM_RESOURCE_IN_USE')

    const employeeInUse = await page.request.put(`/api/v1/iam/employees/${employee.id}`, {
      headers,
      data: {
        organizationId: organization.id,
        positionId: position.id,
        displayName: names.employee,
        mobileMasked: employee.mobileMasked,
        employmentStatus: 'LEFT',
        hireDate: employee.hireDate,
        leaveDate: '2026-08-24',
        expectedVersion: employee.version,
      },
    })
    expect(employeeInUse.status()).toBe(409)
    expect((await employeeInUse.json()).code).toBe('IAM_RESOURCE_IN_USE')

    const positionInUse = await page.request.put(`/api/v1/iam/positions/${position.id}`, {
      headers,
      data: { organizationId: organization.id, name: names.position, description: position.description,
        status: 'INACTIVE', expectedVersion: position.version },
    })
    expect(positionInUse.status()).toBe(409)
    expect((await positionInUse.json()).code).toBe('IAM_RESOURCE_IN_USE')

    const organizationInUse = await page.request.put(`/api/v1/iam/organizations/${organization.id}`, {
      headers,
      data: { parentId: null, communityId: null, name: names.organization,
        organizationType: organization.organizationType, sortOrder: organization.sortOrder,
        status: 'INACTIVE', expectedVersion: organization.version },
    })
    expect(organizationInUse.status()).toBe(409)
    expect((await organizationInUse.json()).code).toBe('IAM_RESOURCE_IN_USE')

    account = await expectApiOk(await page.request.put(`/api/v1/iam/users/${account.id}/password`, {
      headers,
      data: { password: resetPassword, requireChange: false, expectedVersion: account.version },
    }))
    const loginAfterReset = await page.request.post('/api/v1/auth/login', {
      data: { username: `e2e-lifecycle-${suffix}`, password: resetPassword },
    })
    expect(loginAfterReset.ok(), await loginAfterReset.text()).toBeTruthy()

    account = await expectApiOk(await page.request.put(`/api/v1/iam/users/${account.id}`, {
      headers,
      data: {
        displayName: names.account,
        employeeId: employee.id,
        enabled: false,
        passwordChangeRequired: false,
        roleIds: account.roleIds,
        projectIds: account.projectIds,
        expectedVersion: account.version,
      },
    }))
    employee = await expectApiOk(await page.request.put(`/api/v1/iam/employees/${employee.id}`, {
      headers,
      data: {
        organizationId: organization.id,
        positionId: position.id,
        displayName: names.employee,
        mobileMasked: employee.mobileMasked,
        employmentStatus: 'LEFT',
        hireDate: employee.hireDate,
        leaveDate: '2026-08-24',
        expectedVersion: employee.version,
      },
    }))
    position = await expectApiOk(await page.request.put(`/api/v1/iam/positions/${position.id}`, {
      headers,
      data: { organizationId: organization.id, name: names.position, description: position.description,
        status: 'INACTIVE', expectedVersion: position.version },
    }))
    organization = await expectApiOk(await page.request.put(`/api/v1/iam/organizations/${organization.id}`, {
      headers,
      data: { parentId: null, communityId: null, name: names.organization,
        organizationType: organization.organizationType, sortOrder: organization.sortOrder,
        status: 'INACTIVE', expectedVersion: organization.version },
    }))
    role = await expectApiOk(await page.request.put(`/api/v1/iam/roles/${role.id}`, {
      headers,
      data: { name: names.role, description: role.description, enabled: false,
        permissionIds: role.permissionIds, expectedVersion: role.version },
    }))
    enterprise = await expectApiOk(await page.request.put(`/api/v1/iam/enterprises/${enterprise.id}`, {
      headers,
      data: { name: names.enterprise, status: 'INACTIVE', expectedVersion: enterprise.version },
    }))

    await expectVisibleRecord('/enterprise/enterprises', '企业管理', names.enterprise, 'INACTIVE')
    await expectVisibleRecord('/enterprise/organizations', '组织管理', names.organization, 'INACTIVE')
    await expectVisibleRecord('/enterprise/positions', '岗位管理', names.position, 'INACTIVE')
    await expectVisibleRecord('/enterprise/employees', '人员管理', names.employee, 'LEFT')
    await expectVisibleRecord('/enterprise/roles', '角色管理', names.role, '停用')
    await expectVisibleRecord('/enterprise/accounts', '账号与项目权限', names.account, '停用')
  } finally {
    if (account?.id) {
      const item = await current('users', account.id)
      if (item?.enabled) await page.request.put(`/api/v1/iam/users/${item.id}`, {
        headers,
        data: { displayName: item.displayName, employeeId: item.employeeId, enabled: false,
          passwordChangeRequired: item.passwordChangeRequired, roleIds: item.roleIds,
          projectIds: item.projectIds, expectedVersion: item.version },
      })
    }
    if (employee?.id) {
      const item = await current('employees', employee.id)
      if (item?.employmentStatus === 'ACTIVE') await page.request.put(`/api/v1/iam/employees/${item.id}`, {
        headers,
        data: { organizationId: item.organizationId, positionId: item.positionId,
          displayName: item.displayName, mobileMasked: item.mobileMasked,
          employmentStatus: 'INACTIVE', hireDate: item.hireDate, leaveDate: null,
          expectedVersion: item.version },
      })
    }
    if (position?.id) {
      const item = await current('positions', position.id)
      if (item?.status === 'ACTIVE') await page.request.put(`/api/v1/iam/positions/${item.id}`, {
        headers,
        data: { organizationId: item.organizationId, name: item.name,
          description: item.description, status: 'INACTIVE', expectedVersion: item.version },
      })
    }
    if (organization?.id) {
      const item = await current('organizations', organization.id)
      if (item?.status === 'ACTIVE') await page.request.put(`/api/v1/iam/organizations/${item.id}`, {
        headers,
        data: { parentId: item.parentId, communityId: item.communityId, name: item.name,
          organizationType: item.organizationType, sortOrder: item.sortOrder,
          status: 'INACTIVE', expectedVersion: item.version },
      })
    }
    if (role?.id) {
      const item = await current('roles', role.id)
      if (item?.enabled) await page.request.put(`/api/v1/iam/roles/${item.id}`, {
        headers,
        data: { name: item.name, description: item.description, enabled: false,
          permissionIds: item.permissionIds, expectedVersion: item.version },
      })
    }
    if (enterprise?.id) {
      const item = await current('enterprises', enterprise.id)
      if (item?.status === 'ACTIVE') await page.request.put(`/api/v1/iam/enterprises/${item.id}`, {
        headers,
        data: { name: item.name, status: 'INACTIVE', expectedVersion: item.version },
      })
    }
  }
})
