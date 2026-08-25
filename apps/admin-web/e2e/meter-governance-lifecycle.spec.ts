import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const password = process.env.PMS_E2E_PASSWORD
const project = '30000000-0000-0000-0000-000000000001'
const meterStandard = '71000000-0000-0000-0000-000000000019'

test('G7 meter governance preserves continuity, evidence, charge snapshots and reconciliation', async ({ page }) => {
  test.setTimeout(120_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
  await login(page)
  const token = await page.evaluate(() => sessionStorage.getItem('pms_access_token'))
  expect(token).toBeTruthy()
  const headers = { Authorization: `Bearer ${token}` }
  const suffix = Date.now().toString(36)

  const workbench = await apiJson(page.request, `/api/v1/meter-workbench?communityId=${project}`, headers)
  const chargeMeter = workbench.meters.find((meter: Record<string, any>) =>
    meter.status === 'ACTIVE' && meter.meterClass === 'SUB' && meter.assetId
      && String(meter.meterNo).startsWith('SYN-SUB-') && meter.lastReading == null)
    || workbench.meters.find((meter: Record<string, any>) =>
      meter.status === 'ACTIVE' && meter.meterClass === 'SUB' && meter.assetId
        && String(meter.meterNo).startsWith('SYN-SUB-'))
  const iotMeter = workbench.meters.find((meter: Record<string, any>) =>
    meter.id !== chargeMeter?.id && meter.status === 'ACTIVE' && meter.meterClass === 'SUB'
      && String(meter.meterNo).startsWith('SYN-SUB-') && meter.lastReading == null)
  const shareRule = workbench.shareRules.find((rule: Record<string, any>) => rule.status === 'ACTIVE' && rule.activeVersionId)
  expect(chargeMeter, '至少需要一个已分配计量费用的启用合成分表').toBeTruthy()
  expect(iotMeter, '至少需要第二个无已审核读数的启用合成分表').toBeTruthy()
  expect(shareRule, '至少需要一个启用的公摊规则版本').toBeTruthy()

  const firstPeriod = chargeMeter.lastReadingPeriod ? addMonth(chargeMeter.lastReadingPeriod, 1) : '2030-01'
  const secondPeriod = addMonth(firstPeriod, 1)
  const expectedPrevious = Number(chargeMeter.lastReading || 0)
  const firstCurrent = expectedPrevious + 10

  const firstBatchRequest = {
    communityId: project, batchNo: `E2E-G7-NORMAL-${suffix}`,
    readingPeriod: firstPeriod, sourceType: 'MANUAL',
  }
  const firstKey = `e2e-g7-normal-${suffix}`
  const firstBatch = await apiJson(page.request, '/api/v1/meter-reading-batches', headers, firstBatchRequest, firstKey)
  const replayedBatch = await apiJson(page.request, '/api/v1/meter-reading-batches', headers, firstBatchRequest, firstKey)
  expect(replayedBatch.id).toBe(firstBatch.id)
  expect(replayedBatch.replayed).toBeTruthy()
  await expectApiError(page.request, '/api/v1/meter-reading-batches', headers, 409, 'IDEMPOTENCY_REQUEST_CONFLICT', {
    ...firstBatchRequest, batchNo: `E2E-G7-CONFLICT-${suffix}`,
  }, firstKey)

  const reading = {
    communityId: project, batchId: firstBatch.id,
    readings: [{ meterId: chargeMeter.id, previousReading: null, currentReading: firstCurrent,
      correction: null, allocatedShare: 0, readingAt: `${firstPeriod}-15T12:00:00` }],
  }
  await expectApiError(page.request, '/api/v1/meter-readings:input', headers, 400, 'METER_READING_CROSS_PERIOD', {
    ...reading, readings: [{ ...reading.readings[0], readingAt: `${addMonth(firstPeriod, -1)}-15T12:00:00` }],
  })
  const firstInput = await apiJson(page.request, '/api/v1/meter-readings:input', headers, reading)
  expect(firstInput).toEqual(expect.objectContaining({ created: 1, anomalies: 0 }))
  const replayedInput = await apiJson(page.request, '/api/v1/meter-readings:input', headers, reading)
  expect(replayedInput.replayed).toBe(1)
  const approvedFirst = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${firstBatch.id}:approve?communityId=${project}`, headers, {})
  expect(approvedFirst.status).toBe('APPROVED')
  expect(approvedFirst.dataChecksum).toMatch(/^[a-f0-9]{64}$/)

  const secondBatch = await apiJson(page.request, '/api/v1/meter-reading-batches', headers, {
    communityId: project, batchNo: `E2E-G7-ANOMALY-${suffix}`,
    readingPeriod: secondPeriod, sourceType: 'MIXED',
  }, `e2e-g7-anomaly-${suffix}`)
  const secondInput = await apiJson(page.request, '/api/v1/meter-readings:input', headers, {
    communityId: project, batchId: secondBatch.id,
    readings: [{ meterId: chargeMeter.id, previousReading: firstCurrent - 1, currentReading: firstCurrent + 10,
      correction: null, allocatedShare: 0, readingAt: `${secondPeriod}-15T12:00:00` }],
  })
  expect(secondInput.anomalies).toBe(1)
  await expectApiError(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}:approve?communityId=${project}`,
    headers, 409, 'METER_ANOMALY_REVIEW_REQUIRED', {})

  const shareRequest = { communityId: project, ruleId: shareRule.id, batchId: secondBatch.id, totalUsage: 3 }
  const preview = await apiJson(page.request, '/api/v1/meter-share-rules:preview', headers, shareRequest)
  expect(preview).toEqual(expect.objectContaining({ assumptionRule: true, ruleVersionNo: 1 }))
  expect(preview.items).toHaveLength(1)
  const appliedShare = await apiJson(page.request, '/api/v1/meter-share-rules:apply', headers, shareRequest)
  expect(appliedShare).toEqual(expect.objectContaining({ inserted: 1, assumptionRule: true }))
  const replayedShare = await apiJson(page.request, '/api/v1/meter-share-rules:apply', headers, shareRequest)
  expect(replayedShare.replayed).toBe(1)

  let secondDetail = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}?communityId=${project}`, headers)
  const anomaly = secondDetail.readings[0]
  expect(anomaly).toEqual(expect.objectContaining({ validationStatus: 'REVIEW_REQUIRED', anomalyCode: 'PREVIOUS_MISMATCH' }))
  const reviewed = await apiJson(page.request, `/api/v1/meter-readings/${anomaly.id}:review`, headers, {
    communityId: project, reason: 'G7 浏览器验收：现场表单与照片记录一致', expectedVersion: anomaly.version,
  })
  expect(reviewed.validation_status).toBe('REVIEWED')
  const approvedSecond = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}:approve?communityId=${project}`, headers, {})
  expect(approvedSecond.status).toBe('APPROVED')

  const charged = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}:generate-charges`, headers,
    { communityId: project, feeStandardId: meterStandard })
  expect(charged).toEqual(expect.objectContaining({ generated: 1, reconciliationMatched: true, assumptionRule: true }))
  expect(charged.reconciliation).toHaveLength(3)
  expect(charged.reconciliation.every((row: Record<string, any>) =>
    row.status === 'MATCHED' && Number(row.differenceValue) === 0)).toBeTruthy()
  const replayedCharge = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}:generate-charges`, headers,
    { communityId: project, feeStandardId: meterStandard })
  expect(replayedCharge.replayed).toBe(1)
  secondDetail = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${secondBatch.id}?communityId=${project}`, headers)
  expect(secondDetail.batch.status).toBe('APPROVED')
  expect(secondDetail.reconciliation).toHaveLength(3)

  const iotBatch = await apiJson(page.request, '/api/v1/meter-reading-batches', headers, {
    communityId: project, batchNo: `E2E-G7-IOT-${suffix}`,
    readingPeriod: currentMonth(), sourceType: 'IOT_SIMULATOR',
  }, `e2e-g7-iot-${suffix}`)
  const iotImport = await apiJson(page.request,
    `/api/v1/meter-readings:import-simulated?communityId=${project}&batchId=${iotBatch.id}`,
    headers, [iotMeter.id])
  expect(iotImport.created).toBe(1)
  const iotReplay = await apiJson(page.request,
    `/api/v1/meter-readings:import-simulated?communityId=${project}&batchId=${iotBatch.id}`,
    headers, [iotMeter.id])
  expect(iotReplay.replayed).toBe(1)
  const iotDetail = await apiJson(page.request,
    `/api/v1/meter-reading-batches/${iotBatch.id}?communityId=${project}`, headers)
  expect(iotDetail.iotEvidence[0]).toEqual(expect.objectContaining({ simulated: true, status: 'REPLAYED' }))
  expect(iotDetail.iotEvidence[0].payloadChecksum).toMatch(/^[a-f0-9]{64}$/)

  const temporaryMeter = await apiJson(page.request, `/api/v1/data/meters?communityId=${project}`, headers, {
    asset_id: null, parent_meter_id: null, meter_no: `E2E-G7-OLD-${suffix}`,
    meter_type: 'WATER', meter_class: 'SUB', status: 'ACTIVE', range_value: 9999,
    multiplier: 1, loss_rate: 0, correction: 0, installed_at: '2030-01-01T00:00:00',
  })
  const replacementRequest = {
    communityId: project, newMeterNo: `E2E-G7-NEW-${suffix}`,
    oldFinalReading: 0, newInitialReading: 0.5, reason: 'G7 浏览器验收换表连续性',
  }
  const replacementKey = `e2e-g7-replace-${suffix}`
  const replacement = await apiJson(page.request, `/api/v1/meters/${temporaryMeter.id}:replace`,
    headers, replacementRequest, replacementKey)
  expect(replacement.evidenceNo).toMatch(/^MR-/)
  expect(replacement.snapshotChecksum).toMatch(/^[a-f0-9]{64}$/)
  const replacementReplay = await apiJson(page.request, `/api/v1/meters/${temporaryMeter.id}:replace`,
    headers, replacementRequest, replacementKey)
  expect(replacementReplay.replayed).toBeTruthy()
  await expectApiError(page.request, `/api/v1/meters/${temporaryMeter.id}:replace`, headers, 409,
    'IDEMPOTENCY_REQUEST_CONFLICT', { ...replacementRequest, newInitialReading: 1 }, replacementKey)

  await page.goto('/archives/meters')
  await expect(page.getByRole('heading', { name: '仪表主档与资产绑定' })).toBeVisible()
  await expect(page.getByText('IoT 当前为本地可替换模拟适配器')).toBeVisible()
  await page.goto('/metering/batches')
  await expect(page.getByRole('heading', { name: '批次建档与状态统计' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: secondBatch.batch_no }).first()).toContainText('APPROVED')
  await page.goto('/metering/readings')
  await expect(page.getByRole('heading', { name: '读数录入、IoT 证据与异常复核' })).toBeVisible()
  await page.goto('/metering/share-preview')
  await expect(page.getByRole('heading', { name: '公摊规则版本试算' })).toBeVisible()
  await expect(page.getByText('合成假设口径')).toBeVisible()
  await page.goto('/metering/replacements')
  await expect(page.getByRole('heading', { name: '换表连续性与凭证' })).toBeVisible()
  await page.goto('/metering/charges')
  await expect(page.getByRole('heading', { name: '计量账单与零差异对账' })).toBeVisible()
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

async function expectApiError(request: APIRequestContext, url: string, headers: Record<string, string>,
                              status: number, code: string, data: unknown, idempotencyKey?: string) {
  const response = await request.post(url, {
    headers: idempotencyKey ? { ...headers, 'Idempotency-Key': idempotencyKey } : headers, data,
  })
  expect(response.status(), `${url}: ${await response.text()}`).toBe(status)
  expect((await response.json()).code).toBe(code)
}

function addMonth(period: string, amount: number) {
  const [year, month] = period.split('-').map(Number)
  const value = new Date(Date.UTC(year, month - 1 + amount, 1))
  return `${value.getUTCFullYear()}-${String(value.getUTCMonth() + 1).padStart(2, '0')}`
}

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

async function login(page: Page) {
  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(process.env.PMS_E2E_USERNAME || 'admin')
  await page.getByRole('textbox', { name: '密码' }).fill(password || '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}
