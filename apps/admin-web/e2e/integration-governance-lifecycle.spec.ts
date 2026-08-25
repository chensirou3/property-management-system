import { createHmac } from 'node:crypto'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const callbackSecret = process.env.PMS_E2E_CALLBACK_SECRET
const project = '30000000-0000-0000-0000-000000000001'

test('G9 fail-closed adapters, signed callbacks, retries, dead letters and replay complete a browser lifecycle', async ({ page }) => {
  test.setTimeout(120_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  if (!callbackSecret) throw new Error('PMS_E2E_CALLBACK_SECRET is required; use the ignored local environment only')
  await login(page)
  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }

  const initial = await apiJson(page.request, `/api/v1/integrations/workbench?communityId=${project}`, headers)
  expect(initial.adapters).toHaveLength(5)
  expect(initial.adapters.map((item: Record<string, any>) => item.mode)).toEqual(
    expect.arrayContaining(['SIMULATOR', 'SIMULATOR', 'SIMULATOR', 'SIMULATOR', 'DISABLED']),
  )
  expect(initial.adapters.every((item: Record<string, any>) => item.productionReady === false)).toBe(true)
  expect(initial.security).toEqual(expect.objectContaining({ signedCallbacks: true, secretsReadable: false }))

  for (const adapter of initial.adapters) {
    const tested = await postJson(page.request,
      `/api/v1/integrations/adapters/${adapter.adapterCode}:test?communityId=${project}`, headers, {})
    expect(tested).toEqual(expect.objectContaining({
      adapterCode: adapter.adapterCode, mode: adapter.mode, productionReady: false,
    }))
    expect(tested.outcome).toMatch(/SUCCEEDED|DISABLED/)
  }

  const suffix = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const adapterCode = 'PAYMENT_SIMULATOR'
  const callbackId = `e2e-g9-callback-${suffix}`
  const timestamp = new Date().toISOString()
  const payload = JSON.stringify({ communityId: project, eventType: 'PAYMENT_CONFIRMED_SIMULATED', reference: suffix })
  const rejected = await page.request.post(`/api/v1/integrations/callbacks/${adapterCode}`, {
    headers: callbackHeaders(callbackId, timestamp, '00'), data: payload,
  })
  expect(rejected.status()).toBe(401)
  expect((await rejected.json()).code).toBe('CALLBACK_SIGNATURE_REJECTED')

  const signature = sign(adapterCode, callbackId, timestamp, payload)
  const accepted = await signedCallback(page.request, adapterCode, callbackId, timestamp, signature, payload)
  expect(accepted).toEqual(expect.objectContaining({ status: 'PROCESSED', replayed: false, simulated: true }))
  expect(accepted.payloadChecksum).toMatch(/^[a-f0-9]{64}$/)
  const replayed = await signedCallback(page.request, adapterCode, callbackId, timestamp, signature, payload)
  expect(replayed.replayed).toBe(true)

  const conflictingPayload = JSON.stringify({ communityId: project, eventType: 'CONFLICTING_SIMULATED', reference: suffix })
  const conflict = await page.request.post(`/api/v1/integrations/callbacks/${adapterCode}`, {
    headers: callbackHeaders(callbackId, timestamp, sign(adapterCode, callbackId, timestamp, conflictingPayload)),
    data: conflictingPayload,
  })
  expect(conflict.status()).toBe(409)
  expect((await conflict.json()).code).toBe('CALLBACK_REPLAY_CONFLICT')

  const event = await postJson(page.request, '/api/v1/integrations/outbox-events:simulate', headers, {
    communityId: project, eventType: `E2E_G9_RECOVERY_${suffix}`, payload: { source: 'playwright' },
  })
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const failed = await postJson(page.request,
      `/api/v1/integrations/outbox-events/${event.id}:simulate-delivery`, headers,
      { adapterCode, outcome: 'RETRYABLE_FAILURE' })
    expect(failed.retryCount).toBe(attempt)
  }
  let governed = await apiJson(page.request, `/api/v1/integrations/workbench?communityId=${project}`, headers)
  const deadLetter = governed.deadLetters.find((item: Record<string, any>) =>
    item.referenceId === event.id && item.status === 'OPEN')
  expect(deadLetter).toBeTruthy()
  const queued = await postJson(page.request,
    `/api/v1/integrations/dead-letters/${deadLetter.id}:replay`, headers, {})
  expect(queued.status).toBe('REPLAYED')
  const recovered = await postJson(page.request,
    `/api/v1/integrations/outbox-events/${event.id}:simulate-delivery`, headers,
    { adapterCode, outcome: 'SUCCEEDED' })
  expect(recovered.status).toBe('PUBLISHED')

  governed = await apiJson(page.request, `/api/v1/integrations/workbench?communityId=${project}`, headers)
  expect(governed.callbacks.find((item: Record<string, any>) => item.callbackId === callbackId)).toEqual(
    expect.objectContaining({ replayCount: 1, status: 'PROCESSED', payloadChecksum: accepted.payloadChecksum }),
  )
  expect(governed.deadLetters.find((item: Record<string, any>) => item.id === deadLetter.id).status).toBe('RESOLVED')
  expect(governed.attempts.filter((item: Record<string, any>) => item.referenceId === event.id)).toHaveLength(4)

  await page.goto('/system/third-party-settings')
  await expect(page.getByRole('heading', { name: '第三方参数设置', exact: true, level: 1 })).toBeVisible()
  await expect(page.getByRole('heading', { name: '第三方集成治理台', exact: true })).toBeVisible()
  await expect(page.getByText('当前没有任何生产通道', { exact: true })).toBeVisible()
  await expect(page.getByText('PAYMENT_SIMULATOR', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(callbackId, { exact: true }).first()).toBeVisible()
  await expect(page.getByText('密钥只允许通过部署环境变量/密钥管理器变更', { exact: false })).toBeVisible()
})

function callbackHeaders(callbackId: string, timestamp: string, signature: string) {
  return { 'Content-Type': 'application/json', 'X-PMS-Callback-Id': callbackId,
    'X-PMS-Timestamp': timestamp, 'X-PMS-Signature': signature }
}

function sign(adapterCode: string, callbackId: string, timestamp: string, payload: string) {
  return createHmac('sha256', callbackSecret || '')
    .update(`${timestamp}\n${adapterCode}\n${callbackId}\n${payload}`).digest('hex')
}

async function signedCallback(request: APIRequestContext, adapterCode: string, callbackId: string,
                              timestamp: string, signature: string, payload: string) {
  const response = await request.post(`/api/v1/integrations/callbacks/${adapterCode}`, {
    headers: callbackHeaders(callbackId, timestamp, signature), data: payload,
  })
  expect(response.ok(), `${response.status()}: ${await response.text()}`).toBeTruthy()
  return response.json()
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

async function login(page: Page) {
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password || '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}
