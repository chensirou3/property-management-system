<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Connection, DocumentAdd, EditPen, Refresh, Warning } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const mode = computed(() => route.path.split('/').at(-1))
const loading = ref(false)
const submitting = ref(false)
const workbench = ref<any>({ summary: {}, meters: [], batches: [], shareRules: [], feeStandards: [] })
const assets = ref<any[]>([])
const batchDetail = ref<any>({ batch: null, readings: [], iotEvidence: [], reconciliation: [] })
const lastResult = ref<any>(null)
const shareItems = ref<any[]>([])
const meterDialog = ref(false)

const meters = computed<any[]>(() => workbench.value.meters || [])
const batches = computed<any[]>(() => workbench.value.batches || [])
const rules = computed<any[]>(() => workbench.value.shareRules || [])
const standards = computed<any[]>(() => workbench.value.feeStandards || [])
const draftBatches = computed(() => batches.value.filter((item) => item.status === 'DRAFT'))
const approvedBatches = computed(() => batches.value.filter((item) => item.status === 'APPROVED'))
const activeMeters = computed(() => meters.value.filter((item) => item.status === 'ACTIVE'))
const subMeters = computed(() => activeMeters.value.filter((item) => item.meterClass === 'SUB' && item.assetId))
const pendingReadings = computed(() => (batchDetail.value.readings || []).filter((item: any) => item.validationStatus === 'REVIEW_REQUIRED'))

const batchForm = reactive({ batchNo: `MR-${dayjs().format('YYYYMMDD-HHmm')}`, readingPeriod: dayjs().format('YYYY-MM'), sourceType: 'MANUAL' })
const readingForm = reactive({ batchId: '', meterId: '', previousReading: '', currentReading: '', correction: '', readingAt: '' })
const shareForm = reactive({ batchId: '', ruleId: '', totalUsage: '' })
const replacementForm = reactive({ oldMeterId: '', newMeterNo: '', oldFinalReading: '', newInitialReading: '0', reason: '' })
const chargeForm = reactive({ batchId: '', feeStandardId: '' })
const meterForm = reactive({ id: '', version: 0, meter_no: '', meter_type: 'WATER', meter_class: 'SUB', asset_id: '', parent_meter_id: '', range_value: '99999', multiplier: '1', loss_rate: '0', correction: '0', status: 'ACTIVE' })

function syncDefaults() {
  readingForm.batchId ||= draftBatches.value[0]?.id || ''
  shareForm.batchId ||= draftBatches.value[0]?.id || ''
  shareForm.ruleId ||= rules.value[0]?.id || ''
  chargeForm.batchId ||= approvedBatches.value[0]?.id || ''
  chargeForm.feeStandardId ||= standards.value[0]?.id || ''
  replacementForm.oldMeterId ||= subMeters.value[0]?.id || ''
  readingForm.meterId ||= subMeters.value[0]?.id || ''
  syncReadingAt()
  syncReplacementReading()
}

async function loadWorkbench() {
  if (!communityId.value) return
  loading.value = true
  try {
    const [context, assetPageOne, assetPageTwo] = await Promise.all([
      http.get('/meter-workbench', { params: { communityId: communityId.value } }),
      http.get('/data/assets', { params: { communityId: communityId.value, category: 'ROOM', page: 1, size: 200 } }),
      http.get('/data/assets', { params: { communityId: communityId.value, category: 'ROOM', page: 2, size: 200 } }),
    ])
    workbench.value = context.data
    assets.value = [...(assetPageOne.data.items || []), ...(assetPageTwo.data.items || [])]
    syncDefaults()
    if (readingForm.batchId) await loadBatchDetail(readingForm.batchId)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '计量工作台数据加载失败')
  } finally {
    loading.value = false
  }
}

async function loadBatchDetail(batchId: string) {
  if (!batchId || !communityId.value) {
    batchDetail.value = { batch: null, readings: [], iotEvidence: [], reconciliation: [] }
    return
  }
  const { data } = await http.get(`/meter-reading-batches/${batchId}`, { params: { communityId: communityId.value } })
  batchDetail.value = data
}

async function execute(action: () => Promise<any>, success: string) {
  submitting.value = true
  try {
    const data = await action()
    lastResult.value = data
    ElMessage.success(success)
    await loadWorkbench()
    return data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

function openMeter(row?: any) {
  Object.assign(meterForm, row ? {
    id: row.id, version: row.version, meter_no: row.meterNo, meter_type: row.meterType,
    meter_class: row.meterClass, asset_id: row.assetId || '', parent_meter_id: row.parentMeterId || '',
    range_value: row.rangeValue ?? '', multiplier: row.multiplier, loss_rate: row.lossRate,
    correction: row.correction, status: row.status,
  } : { id: '', version: 0, meter_no: '', meter_type: 'WATER', meter_class: 'SUB', asset_id: '',
    parent_meter_id: '', range_value: '99999', multiplier: '1', loss_rate: '0', correction: '0', status: 'ACTIVE' })
  meterDialog.value = true
}

function saveMeter() {
  if (!meterForm.meter_no || !meterForm.meter_type || !meterForm.meter_class) return ElMessage.warning('请完整填写仪表主档')
  const payload = { ...meterForm, id: undefined, version: undefined,
    asset_id: meterForm.asset_id || null, parent_meter_id: meterForm.parent_meter_id || null,
    range_value: meterForm.range_value || null, installed_at: dayjs().format('YYYY-MM-DDTHH:mm:ss') }
  return execute(async () => {
    if (meterForm.id) {
      await http.put(`/data/meters/${meterForm.id}`, payload, { params: { communityId: communityId.value, version: meterForm.version } })
    } else {
      await http.post('/data/meters', payload, { params: { communityId: communityId.value } })
    }
    meterDialog.value = false
    return { status: 'SAVED', meterNo: meterForm.meter_no }
  }, meterForm.id ? '仪表主档已更新' : '仪表主档已创建')
}

function createBatch() {
  if (!batchForm.batchNo || !batchForm.readingPeriod) return ElMessage.warning('请填写批次号和抄表周期')
  return execute(async () => (await http.post('/meter-reading-batches',
    { communityId: communityId.value, ...batchForm },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data, '抄表批次创建成功')
}

function syncReadingAt() {
  const batch = batches.value.find((item) => item.id === readingForm.batchId)
  if (batch) readingForm.readingAt = `${batch.readingPeriod}-15T12:00:00`
}

function syncReplacementReading() {
  const meter = meters.value.find((item) => item.id === replacementForm.oldMeterId)
  replacementForm.oldFinalReading = String(meter?.lastReading ?? 0)
}

function inputReading() {
  if (!readingForm.batchId || !readingForm.meterId || readingForm.currentReading === '') return ElMessage.warning('请选择批次、仪表并填写本期读数')
  return execute(async () => (await http.post('/meter-readings:input', {
    communityId: communityId.value, batchId: readingForm.batchId,
    readings: [{ meterId: readingForm.meterId,
      previousReading: readingForm.previousReading === '' ? null : readingForm.previousReading,
      currentReading: readingForm.currentReading,
      correction: readingForm.correction === '' ? null : readingForm.correction,
      allocatedShare: '0', readingAt: readingForm.readingAt }],
  })).data, '读数已按连续性规则录入')
}

function importSimulated() {
  if (!readingForm.batchId || !readingForm.meterId) return ElMessage.warning('请选择批次和仪表')
  return execute(async () => (await http.post('/meter-readings:import-simulated', [readingForm.meterId], {
    params: { communityId: communityId.value, batchId: readingForm.batchId },
  })).data, 'IoT 模拟读数及入站证据已保存')
}

async function reviewReading(row: any) {
  const { value } = await ElMessageBox.prompt('填写现场表单、照片或复核说明。复核只确认异常，不改写原始快照。', '异常读数复核', {
    inputType: 'textarea', inputPlaceholder: '请输入复核依据', inputValidator: (text) => Boolean(text?.trim()) || '复核依据不能为空',
  })
  return execute(async () => (await http.post(`/meter-readings/${row.id}:review`, {
    communityId: communityId.value, reason: value, expectedVersion: row.version,
  })).data, '异常读数复核完成')
}

async function approveBatch() {
  if (!readingForm.batchId) return ElMessage.warning('请选择待审核批次')
  await ElMessageBox.confirm(`审核将冻结 ${batchDetail.value.readings?.length || 0} 条读数及其校验值，确认提交？`, '审核抄表批次', { type: 'warning' })
  return execute(async () => (await http.post(`/meter-reading-batches/${readingForm.batchId}:approve`, null,
    { params: { communityId: communityId.value } })).data, '抄表批次审核通过')
}

async function previewShare() {
  if (!shareForm.batchId || !shareForm.ruleId || !shareForm.totalUsage) return ElMessage.warning('请完整填写公摊参数')
  submitting.value = true
  try {
    const { data } = await http.post('/meter-share-rules:preview', { communityId: communityId.value, ...shareForm })
    lastResult.value = data
    shareItems.value = data.items
    ElMessage.success(`已按规则版本 V${data.ruleVersionNo} 完成 ${data.items.length} 户试算`)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '公摊试算失败')
  } finally {
    submitting.value = false
  }
}

function applyShare() {
  if (!shareItems.value.length) return ElMessage.warning('请先完成公摊试算')
  return execute(async () => (await http.post('/meter-share-rules:apply',
    { communityId: communityId.value, ...shareForm })).data, '版本化公摊结果已写入读数快照')
}

function replaceMeter() {
  if (!replacementForm.oldMeterId || !replacementForm.newMeterNo || replacementForm.oldFinalReading === '' || !replacementForm.reason) return ElMessage.warning('请完整填写换表信息')
  return execute(async () => (await http.post(`/meters/${replacementForm.oldMeterId}:replace`,
    { communityId: communityId.value, ...replacementForm },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data, '换表完成，已生成连续性快照和凭证')
}

function generateCharges() {
  if (!chargeForm.batchId || !chargeForm.feeStandardId) return ElMessage.warning('请选择已审核批次和计量费用标准')
  return execute(async () => (await http.post(`/meter-reading-batches/${chargeForm.batchId}:generate-charges`,
    { communityId: communityId.value, feeStandardId: chargeForm.feeStandardId })).data, '计量账单与三项对账已生成')
}

watch(communityId, () => void loadWorkbench())
watch(() => readingForm.batchId, async () => { syncReadingAt(); await loadBatchDetail(readingForm.batchId) })
watch(() => replacementForm.oldMeterId, syncReplacementReading)
watch(() => route.path, () => { lastResult.value = null; shareItems.value = [] })
onMounted(loadWorkbench)
</script>

<template>
  <div v-loading="loading" class="meter-page">
    <div class="meter-hero">
      <div><span class="section-kicker">METER GOVERNANCE</span><h2>计量数据闭环</h2><p>主档、跨期连续读数、异常复核、公摊版本、换表凭证与账单对账使用同一条证据链。</p></div>
      <el-button :icon="Refresh" @click="loadWorkbench">刷新状态</el-button>
    </div>
    <el-alert title="IoT 当前为本地可替换模拟适配器；公摊为明确标记的合成假设规则，不能作为生产口径承诺。" type="warning" show-icon :closable="false" />

    <div class="summary-grid">
      <div><strong>{{ workbench.summary?.meterCount || 0 }}</strong><span>仪表主档</span></div>
      <div><strong>{{ workbench.summary?.batchCount || 0 }}</strong><span>抄表批次</span></div>
      <div :class="{ danger: workbench.summary?.pendingReviewCount }"><strong>{{ workbench.summary?.pendingReviewCount || 0 }}</strong><span>待复核异常</span></div>
      <div :class="{ danger: workbench.summary?.reconciliationMismatchCount }"><strong>{{ workbench.summary?.reconciliationMismatchCount || 0 }}</strong><span>对账差异</span></div>
    </div>

    <el-card v-if="mode === 'meters'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">METER MASTER</span><h3>仪表主档与资产绑定</h3></div><el-button type="primary" :icon="DocumentAdd" @click="openMeter()">新增仪表</el-button></div></template>
      <el-table :data="meters" height="520" stripe>
        <el-table-column prop="meterNo" label="仪表编号" min-width="170" fixed />
        <el-table-column prop="assetName" label="绑定资产" min-width="180"><template #default="{ row }">{{ row.assetName || '总表/未绑定' }}</template></el-table-column>
        <el-table-column prop="parentMeterNo" label="上级表" width="150" />
        <el-table-column prop="meterType" label="类型" width="100" />
        <el-table-column prop="meterClass" label="级别" width="90" />
        <el-table-column prop="multiplier" label="倍率" width="90" />
        <el-table-column prop="lossRate" label="损耗率" width="100" />
        <el-table-column prop="lastReading" label="末次审核读数" width="140"><template #default="{ row }">{{ row.lastReading ?? '—' }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" :icon="EditPen" @click="openMeter(row)">编辑</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-else-if="mode === 'batches'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">READING BATCH</span><h3>批次建档与状态统计</h3></div></div></template>
      <el-form :model="batchForm" inline><el-form-item label="批次号"><el-input v-model="batchForm.batchNo" style="width:220px" /></el-form-item><el-form-item label="周期"><el-date-picker v-model="batchForm.readingPeriod" type="month" value-format="YYYY-MM" /></el-form-item><el-form-item label="来源"><el-select v-model="batchForm.sourceType" style="width:160px"><el-option label="人工录入" value="MANUAL" /><el-option label="IoT 模拟" value="IOT_SIMULATOR" /><el-option label="混合来源" value="MIXED" /></el-select></el-form-item><el-button type="primary" :icon="DocumentAdd" :loading="submitting" @click="createBatch">创建批次</el-button></el-form>
      <el-table :data="batches" height="430"><el-table-column prop="batchNo" label="批次号" min-width="190" /><el-table-column prop="readingPeriod" label="周期" width="100" /><el-table-column prop="sourceType" label="来源" width="140" /><el-table-column prop="totalCount" label="总数" width="80" /><el-table-column prop="normalCount" label="正常" width="80" /><el-table-column prop="anomalyCount" label="异常" width="80" /><el-table-column prop="reviewedCount" label="已复核" width="90" /><el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'APPROVED' ? 'success' : 'info'">{{ row.status }}</el-tag></template></el-table-column><el-table-column prop="dataChecksum" label="数据校验值" min-width="220" show-overflow-tooltip /></el-table>
    </el-card>

    <el-card v-else-if="mode === 'readings'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">READING & REVIEW</span><h3>读数录入、IoT 证据与异常复核</h3></div><el-tag :type="pendingReadings.length ? 'danger' : 'success'">待复核 {{ pendingReadings.length }}</el-tag></div></template>
      <el-form :model="readingForm" inline><el-form-item label="草稿批次"><el-select v-model="readingForm.batchId" filterable style="width:230px"><el-option v-for="item in draftBatches" :key="item.id" :label="`${item.batchNo} · ${item.readingPeriod}`" :value="item.id" /></el-select></el-form-item><el-form-item label="仪表"><el-select v-model="readingForm.meterId" filterable style="width:220px"><el-option v-for="item in subMeters" :key="item.id" :label="`${item.meterNo} · ${item.assetName}`" :value="item.id" /></el-select></el-form-item><el-form-item label="上期读数"><el-input v-model="readingForm.previousReading" placeholder="留空自动承接" style="width:130px" /></el-form-item><el-form-item label="本期读数"><el-input v-model="readingForm.currentReading" type="number" style="width:130px" /></el-form-item><el-form-item label="修正值"><el-input v-model="readingForm.correction" placeholder="主档默认" style="width:110px" /></el-form-item><el-form-item><el-button type="primary" :loading="submitting" @click="inputReading">保存读数</el-button><el-button :icon="Connection" @click="importSimulated">IoT 模拟入站</el-button><el-button :icon="Check" @click="approveBatch">审核批次</el-button></el-form-item></el-form>
      <el-table :data="batchDetail.readings" height="380"><el-table-column prop="meterNo" label="仪表" width="150" /><el-table-column prop="assetName" label="资产" min-width="160" /><el-table-column prop="previousReading" label="上期" width="95" /><el-table-column prop="currentReading" label="本期" width="95" /><el-table-column prop="adjustedUsage" label="含损耗用量" width="120" /><el-table-column prop="allocatedShare" label="公摊" width="90" /><el-table-column prop="billableUsage" label="计费用量" width="110" /><el-table-column prop="validationStatus" label="校验状态" width="140"><template #default="{ row }"><el-tag :type="row.validationStatus === 'REVIEW_REQUIRED' ? 'danger' : row.validationStatus === 'REVIEWED' ? 'warning' : 'success'">{{ row.validationStatus }}</el-tag></template></el-table-column><el-table-column prop="anomalyCode" label="异常原因" width="160" /><el-table-column label="复核" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.validationStatus === 'REVIEW_REQUIRED'" link type="danger" :icon="Warning" @click="reviewReading(row)">复核</el-button></template></el-table-column></el-table>
      <div class="evidence-strip"><span>IoT 入站证据 {{ batchDetail.iotEvidence?.length || 0 }} 条</span><span>批次校验值 {{ batchDetail.batch?.data_checksum || batchDetail.batch?.dataChecksum || '审核后生成' }}</span></div>
    </el-card>

    <el-card v-else-if="mode === 'share-preview'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">VERSIONED SHARE</span><h3>公摊规则版本试算</h3></div><el-tag type="warning">合成假设口径</el-tag></div></template>
      <el-form :model="shareForm" inline><el-form-item label="草稿批次"><el-select v-model="shareForm.batchId" style="width:230px"><el-option v-for="item in draftBatches" :key="item.id" :label="item.batchNo" :value="item.id" /></el-select></el-form-item><el-form-item label="有效规则"><el-select v-model="shareForm.ruleId" style="width:240px"><el-option v-for="item in rules" :key="item.id" :label="`${item.name} · V${item.activeVersionNo}`" :value="item.id" /></el-select></el-form-item><el-form-item label="待分摊用量"><el-input v-model="shareForm.totalUsage" type="number" style="width:150px" /></el-form-item><el-button plain type="primary" @click="previewShare">试算</el-button><el-button type="primary" :disabled="!shareItems.length" @click="applyShare">应用并固化快照</el-button></el-form>
      <el-table :data="shareItems" height="420"><el-table-column prop="assetName" label="资产" min-width="220" /><el-table-column prop="area" label="分摊依据（面积）" width="170" /><el-table-column prop="allocatedUsage" label="分摊用量" width="150" /></el-table>
    </el-card>

    <el-card v-else-if="mode === 'replacements'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">REPLACEMENT EVIDENCE</span><h3>换表连续性与凭证</h3></div></div></template>
      <el-form :model="replacementForm" label-width="130px" class="replacement-form"><el-form-item label="旧表"><el-select v-model="replacementForm.oldMeterId" filterable><el-option v-for="item in subMeters" :key="item.id" :label="`${item.meterNo} · 末次 ${item.lastReading ?? 0}`" :value="item.id" /></el-select></el-form-item><el-form-item label="新表编号"><el-input v-model="replacementForm.newMeterNo" /></el-form-item><el-form-item label="旧表止码"><el-input v-model="replacementForm.oldFinalReading" type="number" /></el-form-item><el-form-item label="新表起码"><el-input v-model="replacementForm.newInitialReading" type="number" /></el-form-item><el-form-item label="换表原因" class="span-two"><el-input v-model="replacementForm.reason" type="textarea" :rows="3" /></el-form-item><el-form-item><el-button type="primary" @click="replaceMeter">校验连续性并换表</el-button></el-form-item></el-form>
    </el-card>

    <el-card v-else-if="mode === 'charges'" class="workflow-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">METER RECEIVABLE</span><h3>计量账单与零差异对账</h3></div></div></template>
      <el-form :model="chargeForm" inline><el-form-item label="已审核批次"><el-select v-model="chargeForm.batchId" style="width:240px"><el-option v-for="item in approvedBatches" :key="item.id" :label="`${item.batchNo} · ${item.readingPeriod}`" :value="item.id" /></el-select></el-form-item><el-form-item label="计量费用标准"><el-select v-model="chargeForm.feeStandardId" style="width:280px"><el-option v-for="item in standards" :key="item.id" :label="`${item.code} · ${item.name} · ${item.unitPrice}`" :value="item.id" /></el-select></el-form-item><el-button type="primary" @click="generateCharges">生成账单并对账</el-button></el-form>
      <el-table :data="lastResult?.reconciliation || batchDetail.reconciliation || []" height="260"><el-table-column prop="metricName" label="指标" /><el-table-column prop="sourceValue" label="计量源值" /><el-table-column prop="targetValue" label="账单目标值" /><el-table-column prop="differenceValue" label="差异" /><el-table-column prop="status" label="状态"><template #default="{ row }"><el-tag :type="row.status === 'MATCHED' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column></el-table>
    </el-card>

    <el-card v-if="lastResult && mode !== 'share-preview' && mode !== 'charges'" class="workflow-card result-card" shadow="never"><template #header><strong>最近操作证据</strong></template><pre>{{ JSON.stringify(lastResult, null, 2) }}</pre></el-card>

    <el-dialog v-model="meterDialog" :title="meterForm.id ? '编辑仪表主档' : '新增仪表主档'" width="650px">
      <el-form :model="meterForm" label-width="105px" class="meter-form"><el-form-item label="仪表编号"><el-input v-model="meterForm.meter_no" /></el-form-item><el-form-item label="仪表类型"><el-select v-model="meterForm.meter_type"><el-option label="水表" value="WATER" /><el-option label="电表" value="ELECTRICITY" /><el-option label="燃气表" value="GAS" /></el-select></el-form-item><el-form-item label="仪表级别"><el-select v-model="meterForm.meter_class"><el-option label="总表" value="MASTER" /><el-option label="分表" value="SUB" /></el-select></el-form-item><el-form-item label="绑定资产"><el-select v-model="meterForm.asset_id" clearable filterable><el-option v-for="item in assets" :key="item.id" :label="`${item.code} · ${item.display_name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="上级仪表"><el-select v-model="meterForm.parent_meter_id" clearable filterable><el-option v-for="item in activeMeters.filter((meter) => meter.id !== meterForm.id)" :key="item.id" :label="item.meterNo" :value="item.id" /></el-select></el-form-item><el-form-item label="量程"><el-input v-model="meterForm.range_value" type="number" /></el-form-item><el-form-item label="倍率"><el-input v-model="meterForm.multiplier" type="number" /></el-form-item><el-form-item label="损耗率"><el-input v-model="meterForm.loss_rate" type="number" /></el-form-item><el-form-item label="修正值"><el-input v-model="meterForm.correction" type="number" /></el-form-item><el-form-item label="状态"><el-select v-model="meterForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="meterDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveMeter">保存主档</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.meter-page{display:grid;gap:16px}.meter-hero{display:flex;align-items:flex-start;justify-content:space-between;padding:22px 26px;color:#fff;background:linear-gradient(125deg,#123b5d,#176f77);border-radius:10px;box-shadow:0 8px 24px rgba(18,59,93,.16)}.meter-hero h2{margin:4px 0 6px;font-size:25px}.meter-hero p{margin:0;color:rgba(255,255,255,.78)}.section-kicker{font-size:11px;letter-spacing:.15em;color:#35a1aa;font-weight:700}.meter-hero .section-kicker{color:#9edee0}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.summary-grid>div{display:flex;flex-direction:column;padding:16px 18px;background:#fff;border:1px solid #dfe8ed;border-radius:8px}.summary-grid strong{font-size:25px;color:#163c56}.summary-grid span{margin-top:4px;color:#6c7d88;font-size:13px}.summary-grid .danger{border-color:#efb4ad;background:#fff7f5}.summary-grid .danger strong{color:#c94c3d}.workflow-card{border:1px solid #dfe8ed}.card-heading{display:flex;align-items:center;justify-content:space-between}.card-heading h3{margin:3px 0 0;color:#173d56}.evidence-strip{display:flex;justify-content:space-between;gap:16px;margin-top:12px;padding:11px 14px;background:#f2f7f8;color:#536a76;border-radius:6px;font-size:12px}.replacement-form,.meter-form{display:grid;grid-template-columns:1fr 1fr;gap:0 12px;max-width:820px}.span-two{grid-column:1/-1}.result-card pre{max-height:260px;overflow:auto;padding:12px;background:#102d3d;color:#d8eef0;border-radius:6px;font-size:12px}@media(max-width:900px){.summary-grid{grid-template-columns:repeat(2,1fr)}.replacement-form,.meter-form{grid-template-columns:1fr}.span-two{grid-column:auto}.meter-hero{flex-direction:column;gap:14px}}
</style>
