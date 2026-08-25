import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const project = '30000000-0000-0000-0000-000000000001'

test('G10 dashboard designer and simulated visitor lifecycle close the final two page capabilities', async ({ page }) => {
  test.setTimeout(90_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await login(page)
  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }

  const defaults = await apiJson(page.request, `/api/v1/dashboard/configurations?communityId=${project}&roleCode=ALL`, headers)
  expect(defaults.items.length).toBeGreaterThanOrEqual(4)
  expect(defaults.items.every((item: Record<string, any>) => item.status === 'PUBLISHED')).toBe(true)

  const suffix = `${Date.now().toString(36).toUpperCase()}${Math.random().toString(36).slice(2, 6).toUpperCase()}`
  const roleCode = `E2E_${suffix}`
  const created = await postJson(page.request, '/api/v1/dashboard/configurations', headers, {
    communityId: project, roleCode, widgetCode: 'METER_PROGRESS', widgetName: '抄表进度',
    metricCode: 'METER_PROGRESS', positionCode: 'MAIN', visible: true,
    refreshIntervalSeconds: 300, displayOrder: 1,
  })
  expect(created).toEqual(expect.objectContaining({ status: 'DRAFT', version: 0 }))
  const updated = await putJson(page.request,
    `/api/v1/dashboard/configurations/${created.id}?communityId=${project}`, headers, {
      widgetName: '抄表进度（合成）', metricCode: 'METER_PROGRESS', positionCode: 'MAIN', visible: true,
      refreshIntervalSeconds: 600, expectedVersion: 0,
    })
  expect(updated.version).toBe(1)
  const conflict = await page.request.put(`/api/v1/dashboard/configurations/${created.id}?communityId=${project}`, {
    headers, data: { widgetName: '冲突写入', metricCode: 'METER_PROGRESS', positionCode: 'MAIN', visible: true,
      refreshIntervalSeconds: 600, expectedVersion: 0 },
  })
  expect(conflict.status()).toBe(409)
  expect((await conflict.json()).code).toBe('OPTIMISTIC_LOCK_CONFLICT')
  const reordered = await postJson(page.request, '/api/v1/dashboard/configurations:reorder', headers, {
    communityId: project, roleCode, widgets: [{ id: created.id, expectedVersion: 1 }],
  })
  const published = await postJson(page.request, '/api/v1/dashboard/configurations:publish', headers, {
    communityId: project, roleCode, widgets: [{ id: created.id, expectedVersion: reordered.items[0].version }],
  })
  expect(published).toEqual(expect.objectContaining({ publishedCount: 1, total: 1 }))

  const rejected = await page.request.post('/api/v1/visitors', { headers, data: {
    communityId: project, visitorNameMasked: '未脱敏姓名', visitorMobileMasked: '136****9001',
    hostNameMasked: '合成住户戊**', assetName: '5号楼-1单元-0501', scheduledAt: futureTime(),
  } })
  expect(rejected.status()).toBe(422)
  expect((await rejected.json()).code).toBe('SENSITIVE_VALUE_NOT_MASKED')

  const visitor = await postJson(page.request, '/api/v1/visitors', headers, {
    communityId: project, visitorNameMasked: '合成访客戊**', visitorMobileMasked: '136****9001',
    hostNameMasked: '合成住户戊**', assetName: '5号楼-1单元-0501', scheduledAt: futureTime(),
  })
  expect(visitor).toEqual(expect.objectContaining({ visit_status: 'REGISTERED', simulated: true, productionConnected: false }))
  const checkedIn = await postJson(page.request, `/api/v1/visitors/${visitor.id}:check-in`, headers,
    { communityId: project, expectedVersion: 0 })
  expect(checkedIn).toEqual(expect.objectContaining({ visit_status: 'CHECKED_IN', simulated: true, productionConnected: false }))
  const replayed = await postJson(page.request, `/api/v1/visitors/${visitor.id}:check-in`, headers,
    { communityId: project, expectedVersion: 0 })
  expect(replayed.replayed).toBe(true)
  const checkedOut = await postJson(page.request, `/api/v1/visitors/${visitor.id}:check-out`, headers,
    { communityId: project, expectedVersion: 1 })
  expect(checkedOut.visit_status).toBe('CHECKED_OUT')
  const records = await apiJson(page.request,
    `/api/v1/visitors?communityId=${project}&keyword=${encodeURIComponent(visitor.visit_no)}`, headers)
  expect(records).toEqual(expect.objectContaining({ adapterCode: 'IOT_SIMULATOR', productionConnected: false,
    sensitiveFieldsStoredMasked: true }))
  expect(records.items.some((item: Record<string, any>) => item.id === visitor.id && item.visit_status === 'CHECKED_OUT')).toBe(true)

  await page.goto('/dashboard/configuration')
  await expect(page.getByRole('heading', { name: '看板配置', exact: true, level: 1 })).toBeVisible()
  await expect(page.getByRole('heading', { name: '看板配置', exact: true, level: 2 })).toBeVisible()
  await expect(page.getByText('配置按项目和角色隔离', { exact: false })).toBeVisible()
  await expect(page.getByText('资产概览', { exact: true }).first()).toBeVisible()

  await page.goto('/security/visitor-records')
  await expect(page.getByRole('heading', { name: '访客记录', exact: true, level: 1 })).toBeVisible()
  await expect(page.getByRole('heading', { name: '访客记录', exact: true, level: 2 })).toBeVisible()
  await expect(page.getByText('当前仅使用 IOT_SIMULATOR', { exact: false })).toBeVisible()
  await expect(page.getByText('productionConnected=false', { exact: false })).toBeVisible()
  await expect(page.getByText(visitor.visit_no, { exact: true })).toBeVisible()
})

function futureTime() {
  return new Date(Date.now() + 86_400_000).toISOString().slice(0, 19)
}

async function apiJson(request: APIRequestContext, url: string, headers: Record<string, string>) {
  const response = await request.get(url, { headers })
  expect(response.ok(), `${response.status()} ${url}: ${await response.text()}`).toBeTruthy()
  return response.json()
}

async function postJson(request: APIRequestContext, url: string, headers: Record<string, string>, data: unknown) {
  const response = await request.post(url, { headers, data })
  expect(response.ok(), `${response.status()} ${url}: ${await response.text()}`).toBeTruthy()
  return response.json()
}

async function putJson(request: APIRequestContext, url: string, headers: Record<string, string>, data: unknown) {
  const response = await request.put(url, { headers, data })
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
