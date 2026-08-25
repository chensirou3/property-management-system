import { expect, test } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const primaryProject = '30000000-0000-0000-0000-000000000001'
const roomId = '40000000-0000-0000-0000-000000000001'

function addDays(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

test('property and customer workspaces complete an ownership lifecycle', async ({ page }) => {
  test.setTimeout(90_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }
  const suffix = Date.now()
  const customerName = `E2E 产权客户 ${suffix}`
  let customer: Record<string, any> | undefined
  let originalOwnerId = ''
  let firstEffectiveDate = ''
  let transferred = false

  async function expectOk(response: Awaited<ReturnType<typeof page.request.get>>) {
    expect(response.ok(), await response.text()).toBeTruthy()
    return response.json()
  }

  try {
    customer = await expectOk(await page.request.post(`/api/v1/data/customers?communityId=${primaryProject}`, {
      headers,
      data: {
        customer_no: `E2E-CUS-${suffix}`,
        display_name: customerName,
        customer_type: 'PERSON',
        customer_class: 'OWNER',
        mobile_masked: `E2E-***-${String(suffix).slice(-4)}`,
        gender: 'UNKNOWN',
        status: 'ACTIVE',
      },
    }))

    const before = await expectOk(await page.request.get(`/api/v1/property/assets/${roomId}?communityId=${primaryProject}`, { headers }))
    const originalOwner = before.relations.find((item: Record<string, any>) =>
      item.status === 'ACTIVE' && !item.endDate && ['OWNER', 'CO_OWNER'].includes(item.relationType))
    expect(originalOwner).toBeTruthy()
    originalOwnerId = originalOwner.customerId
    firstEffectiveDate = addDays(originalOwner.startDate, 1)

    const transferKey = `e2e-property-transfer-${suffix}`
    const transfer = await page.request.post(`/api/v1/property/assets/${roomId}:transfer`, {
      headers: { ...headers, 'Idempotency-Key': transferKey },
      data: {
        communityId: primaryProject,
        newOwnerCustomerId: customer.id,
        effectiveDate: firstEffectiveDate,
        reason: 'E2E 产权变更主链路',
        expectedAssetVersion: before.asset.version,
      },
    })
    expect(transfer.ok(), await transfer.text()).toBeTruthy()
    transferred = true
    const replay = await expectOk(await page.request.post(`/api/v1/property/assets/${roomId}:transfer`, {
      headers: { ...headers, 'Idempotency-Key': transferKey },
      data: {
        communityId: primaryProject,
        newOwnerCustomerId: customer.id,
        effectiveDate: firstEffectiveDate,
        reason: 'E2E 产权变更主链路',
        expectedAssetVersion: before.asset.version,
      },
    }))
    expect(replay.replayed).toBe(true)

    await page.goto('/archives/rooms')
    await expect(page.getByRole('heading', { name: '房产信息' })).toBeVisible()
    await page.getByPlaceholder('房号/房屋名称/楼栋').fill('R0001')
    await page.getByRole('button', { name: '查询' }).click()
    const roomRow = page.getByRole('row').filter({ hasText: 'R0001' })
    await expect(roomRow).toContainText(customerName)
    await roomRow.getByRole('button', { name: '档案与关系' }).click()
    await expect(page.locator('.el-drawer').getByText(customerName, { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: '关系历史' }).click()
    await expect(page.locator('.el-drawer').getByRole('listitem').filter({ hasText: customerName }))
      .toContainText('OWNERSHIP_TRANSFERRED')

    await page.goto('/archives/customers')
    await page.getByPlaceholder('客户编号/名称/脱敏手机').fill(customerName)
    await page.getByRole('button', { name: '查询' }).click()
    const customerRow = page.getByRole('row').filter({ hasText: customerName })
    await expect(customerRow).toContainText('1')
    await customerRow.getByRole('button', { name: '资产与历史' }).click()
    await expect(page.getByText('合成房屋 0001', { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: '关系时间线' }).click()
    await expect(page.locator('.el-drawer').getByRole('listitem').filter({ hasText: 'E2E 产权变更主链路' }))
      .toContainText('OWNERSHIP_TRANSFERRED')
  } finally {
    if (transferred && customer && originalOwnerId) {
      const current = await expectOk(await page.request.get(`/api/v1/property/assets/${roomId}?communityId=${primaryProject}`, { headers }))
      const restore = await page.request.post(`/api/v1/property/assets/${roomId}:transfer`, {
        headers: { ...headers, 'Idempotency-Key': `e2e-property-restore-${suffix}` },
        data: {
          communityId: primaryProject,
          newOwnerCustomerId: originalOwnerId,
          effectiveDate: addDays(firstEffectiveDate, 1),
          reason: 'E2E 清理并恢复产权人',
          expectedAssetVersion: current.asset.version,
        },
      })
      expect(restore.ok(), await restore.text()).toBeTruthy()
    }
    if (customer?.id) {
      const archive = await page.request.delete(`/api/v1/data/customers/${customer.id}`, {
        headers,
        params: { communityId: primaryProject, version: customer.version },
      })
      expect(archive.ok(), await archive.text()).toBeTruthy()
    }
  }
})
