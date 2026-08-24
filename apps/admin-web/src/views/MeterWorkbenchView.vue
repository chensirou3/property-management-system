<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, DocumentAdd, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const mode = computed(() => route.path.split('/').at(-1))
const loading = ref(false)
const submitting = ref(false)
const batches = ref<any[]>([])
const meters = ref<any[]>([])
const rules = ref<any[]>([])
const standards = ref<any[]>([])
const lastResult = ref<any>(null)
const shareItems = ref<any[]>([])

const batchForm = reactive({ batchNo: `MR-${dayjs().format('YYYYMMDD-HHmm')}`, readingPeriod: dayjs().format('YYYY-MM'), sourceType: 'MANUAL' })
const readingForm = reactive({ batchId: '', meterId: '', previousReading: '0', currentReading: '', correction: '0' })
const shareForm = reactive({ batchId: '', ruleId: '', totalUsage: '' })
const replacementForm = reactive({ oldMeterId: '', newMeterNo: '', oldFinalReading: '', newInitialReading: '0', reason: '' })
const chargeForm = reactive({ batchId: '', feeStandardId: '' })

const draftBatches = computed(() => batches.value.filter((item) => item.status === 'DRAFT'))
const approvedBatches = computed(() => batches.value.filter((item) => item.status === 'APPROVED'))
const activeMeters = computed(() => meters.value.filter((item) => item.status === 'ACTIVE'))

async function getItems(resource: string) {
  const { data } = await http.get(`/data/${resource}`, { params: { communityId: communityId.value, page: 1, size: 100 } })
  return data.items
}

async function loadSupport() {
  if (!communityId.value) return
  loading.value = true
  try {
    const [batchRows, meterRows, ruleRows, standardRows] = await Promise.all([
      getItems('meter-reading-batches'), getItems('meters'), getItems('meter-share-rules'), getItems('fee-standards'),
    ])
    batches.value = batchRows
    meters.value = meterRows
    rules.value = ruleRows
    standards.value = standardRows
    readingForm.batchId ||= draftBatches.value[0]?.id || ''
    shareForm.batchId ||= draftBatches.value[0]?.id || ''
    shareForm.ruleId ||= rules.value[0]?.id || ''
    chargeForm.batchId ||= approvedBatches.value[0]?.id || ''
    chargeForm.feeStandardId ||= standards.value.find((item) => String(item.code).startsWith('METER-'))?.id || standards.value[0]?.id || ''
    replacementForm.oldMeterId ||= activeMeters.value.find((item) => item.meter_class === 'SUB')?.id || ''
    readingForm.meterId ||= activeMeters.value.find((item) => item.asset_id)?.id || ''
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '仪表业务数据加载失败')
  } finally {
    loading.value = false
  }
}

async function execute(action: () => Promise<any>, success: string) {
  submitting.value = true
  try {
    const data = await action()
    lastResult.value = data
    ElMessage.success(success)
    await loadSupport()
    return data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

function createBatch() {
  if (!batchForm.batchNo || !batchForm.readingPeriod) return ElMessage.warning('请填写批次号和抄表周期')
  return execute(async () => (await http.post('/meter-reading-batches', { communityId: communityId.value, ...batchForm })).data, '抄表批次创建成功')
}

function inputReading() {
  if (!readingForm.batchId || !readingForm.meterId || readingForm.currentReading === '') return ElMessage.warning('请选择批次、仪表并填写本期读数')
  return execute(async () => (await http.post('/meter-readings:input', {
    communityId: communityId.value, batchId: readingForm.batchId,
    readings: [{ meterId: readingForm.meterId, previousReading: readingForm.previousReading, currentReading: readingForm.currentReading, correction: readingForm.correction, allocatedShare: '0' }],
  })).data, '抄表读数已录入')
}

async function approveBatch() {
  if (!readingForm.batchId) return ElMessage.warning('请选择待审核批次')
  await ElMessageBox.confirm('审核后读数不可继续修改，确认提交？', '审核抄表批次', { type: 'warning' })
  return execute(async () => (await http.post(`/meter-reading-batches/${readingForm.batchId}:approve`, null, { params: { communityId: communityId.value } })).data, '抄表批次审核通过')
}

async function previewShare() {
  if (!shareForm.batchId || !shareForm.ruleId || !shareForm.totalUsage) return ElMessage.warning('请完整填写公摊参数')
  submitting.value = true
  try {
    const { data } = await http.post('/meter-share-rules:preview', { communityId: communityId.value, ...shareForm })
    lastResult.value = data
    shareItems.value = data.items
    ElMessage.success(`公摊试算完成，共 ${data.items.length} 户`)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '公摊试算失败')
  } finally {
    submitting.value = false
  }
}

function applyShare() {
  if (!shareItems.value.length) return ElMessage.warning('请先完成公摊试算')
  return execute(async () => (await http.post('/meter-share-rules:apply', { communityId: communityId.value, ...shareForm })).data, '公摊结果已写入抄表批次')
}

function replaceMeter() {
  if (!replacementForm.oldMeterId || !replacementForm.newMeterNo || !replacementForm.oldFinalReading || !replacementForm.reason) return ElMessage.warning('请完整填写换表信息')
  return execute(async () => (await http.post(`/meters/${replacementForm.oldMeterId}:replace`, { communityId: communityId.value, ...replacementForm })).data, '换表完成，新旧仪表状态已联动更新')
}

function generateCharges() {
  if (!chargeForm.batchId || !chargeForm.feeStandardId) return ElMessage.warning('请选择已审核批次和计量费用标准')
  return execute(async () => (await http.post(`/meter-reading-batches/${chargeForm.batchId}:generate-charges`, { communityId: communityId.value, feeStandardId: chargeForm.feeStandardId })).data, '计量费用生成完成')
}

watch(communityId, () => void loadSupport())
watch(() => route.path, () => { lastResult.value = null; shareItems.value = [] })
onMounted(loadSupport)
</script>

<template>
  <div v-loading="loading" class="workflow-page">
    <el-alert title="公摊公式和 IoT 数据均为可替换演示口径；页面会明确标记假设规则，不冒充生产结算口径。" type="warning" show-icon :closable="false" />

    <el-card v-if="mode === 'batches'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">METER BATCH</span><h3>新建抄表批次</h3></div><el-button :icon="Refresh" @click="loadSupport">刷新</el-button></div></template>
      <el-form :model="batchForm" label-width="110px" class="workflow-form">
        <el-form-item label="批次号"><el-input v-model="batchForm.batchNo" /></el-form-item>
        <el-form-item label="抄表周期"><el-date-picker v-model="batchForm.readingPeriod" type="month" value-format="YYYY-MM" format="YYYY 年 MM 月" /></el-form-item>
        <el-form-item label="数据来源"><el-select v-model="batchForm.sourceType"><el-option label="人工录入" value="MANUAL" /><el-option label="IoT 模拟器" value="IOT_SIMULATOR" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" :icon="DocumentAdd" :loading="submitting" @click="createBatch">创建批次</el-button></el-form-item>
      </el-form>
      <el-table :data="batches" max-height="390"><el-table-column prop="batch_no" label="批次号" min-width="220" /><el-table-column prop="reading_period" label="周期" width="120" /><el-table-column prop="source_type" label="来源" width="150" /><el-table-column prop="status" label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.status === 'APPROVED' ? 'success' : 'info'">{{ scope.row.status }}</el-tag></template></el-table-column><el-table-column prop="updated_at" label="更新时间" width="190" /></el-table>
    </el-card>

    <el-card v-else-if="mode === 'readings'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">READING INPUT</span><h3>抄表录入与审核</h3></div><el-tag effect="plain">草稿批次 {{ draftBatches.length }}</el-tag></div></template>
      <el-form :model="readingForm" label-width="110px" class="workflow-form two-column-form">
        <el-form-item label="抄表批次"><el-select v-model="readingForm.batchId" filterable><el-option v-for="item in draftBatches" :key="item.id" :label="`${item.batch_no} · ${item.reading_period}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="仪表"><el-select v-model="readingForm.meterId" filterable><el-option v-for="item in activeMeters" :key="item.id" :label="`${item.meter_no} · ${item.meter_type}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="上期读数"><el-input v-model="readingForm.previousReading" type="number" /></el-form-item>
        <el-form-item label="本期读数"><el-input v-model="readingForm.currentReading" type="number" /></el-form-item>
        <el-form-item label="修正值"><el-input v-model="readingForm.correction" type="number" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="submitting" @click="inputReading">保存读数</el-button><el-button :icon="Check" :loading="submitting" @click="approveBatch">审核批次</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card v-else-if="mode === 'share-preview'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">SHARED USAGE</span><h3>公摊试算与应用</h3></div><el-tag type="warning" effect="plain">假设规则</el-tag></div></template>
      <el-form :model="shareForm" inline>
        <el-form-item label="草稿批次"><el-select v-model="shareForm.batchId" style="width: 240px"><el-option v-for="item in draftBatches" :key="item.id" :label="item.batch_no" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="公摊规则"><el-select v-model="shareForm.ruleId" style="width: 220px"><el-option v-for="item in rules" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="待分摊用量"><el-input v-model="shareForm.totalUsage" type="number" style="width: 160px" /></el-form-item>
        <el-form-item><el-button type="primary" plain :loading="submitting" @click="previewShare">试算</el-button><el-button type="primary" :disabled="!shareItems.length" :loading="submitting" @click="applyShare">应用结果</el-button></el-form-item>
      </el-form>
      <el-table v-if="shareItems.length" :data="shareItems" height="430"><el-table-column prop="assetName" label="房屋" min-width="200" /><el-table-column prop="area" label="建筑面积" width="140" /><el-table-column prop="allocatedUsage" label="分摊用量" width="160" /></el-table>
    </el-card>

    <el-card v-else-if="mode === 'replacements'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">METER REPLACEMENT</span><h3>换表处理</h3></div></div></template>
      <el-form :model="replacementForm" label-width="125px" class="workflow-form two-column-form">
        <el-form-item label="旧表"><el-select v-model="replacementForm.oldMeterId" filterable><el-option v-for="item in activeMeters" :key="item.id" :label="item.meter_no" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="新表编号"><el-input v-model="replacementForm.newMeterNo" /></el-form-item>
        <el-form-item label="旧表末次读数"><el-input v-model="replacementForm.oldFinalReading" type="number" /></el-form-item>
        <el-form-item label="新表初始读数"><el-input v-model="replacementForm.newInitialReading" type="number" /></el-form-item>
        <el-form-item label="换表原因" class="span-two"><el-input v-model="replacementForm.reason" type="textarea" :rows="3" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="submitting" @click="replaceMeter">确认换表</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card v-else-if="mode === 'charges'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">METER CHARGES</span><h3>计量费用生成</h3></div></div></template>
      <el-form :model="chargeForm" label-width="130px" class="workflow-form">
        <el-form-item label="已审核批次"><el-select v-model="chargeForm.batchId" filterable><el-option v-for="item in approvedBatches" :key="item.id" :label="`${item.batch_no} · ${item.reading_period}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="计量费用标准"><el-select v-model="chargeForm.feeStandardId" filterable><el-option v-for="item in standards" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" :loading="submitting" @click="generateCharges">生成计量账单</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="lastResult && mode !== 'share-preview'" class="workflow-card result-card" shadow="never">
      <template #header><strong>最近操作结果</strong></template>
      <pre>{{ JSON.stringify(lastResult, null, 2) }}</pre>
    </el-card>
  </div>
</template>
