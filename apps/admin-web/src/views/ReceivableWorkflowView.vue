<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentChecked, Refresh, Search, View } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

interface AssetRow { id: string; code: string; display_name: string; building_area: string; occupancy_status: string }
interface PreviewLine {
  rowNo: number; targetType: string; targetId: string; assetId: string; assetName: string; itemName: string
  quantity: string; unitPrice: string; coefficient: string; preRoundAmount: string; amount: string
  configurationChecksum: string; snapshot: Record<string, any>
}

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const canWrite = computed(() => auth.hasPermission('fee:write'))
const period = ref(dayjs().format('YYYY-MM'))
const keyword = ref('')
const assets = ref<AssetRow[]>([])
const selected = ref<AssetRow[]>([])
const previewItems = ref<PreviewLine[]>([])
const previewErrors = ref<Record<string, any>[]>([])
const previewTotal = ref('0.00')
const previewChecksum = ref('')
const jobs = ref<Record<string, any>[]>([])
const jobDetail = ref<any>(null)
const detailVisible = ref(false)
const loadingAssets = ref(false)
const running = ref(false)

async function loadAssets() {
  if (!communityId.value) return
  loadingAssets.value = true
  try {
    const { data } = await http.get('/data/assets', { params: {
      communityId: communityId.value, category: 'ROOM', keyword: keyword.value || undefined,
      page: 1, size: 100, sort: 'code,asc',
    } })
    assets.value = data.items
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '资产加载失败') }
  finally { loadingAssets.value = false }
}

async function loadJobs() {
  if (!communityId.value) return
  try {
    const { data } = await http.get('/receivable-jobs', { params: { communityId: communityId.value, jobType: 'PERIODIC' } })
    jobs.value = data
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '应收任务加载失败') }
}

function requestBody() {
  return { communityId: communityId.value, billingPeriod: period.value, assetIds: selected.value.map((item) => item.id) }
}

async function preview() {
  if (!period.value || selected.value.length === 0) return ElMessage.warning('请选择账期和至少一项资产')
  running.value = true
  try {
    const { data } = await http.post('/receivables:preview', requestBody())
    previewItems.value = data.items
    previewErrors.value = data.errors
    previewTotal.value = data.totalAmount
    previewChecksum.value = data.configurationChecksum
    jobDetail.value = null
    if (data.errorCount) ElMessage.warning(`试算完成：${data.lineCount} 条有效，${data.errorCount} 条错误`)
    else ElMessage.success(`试算完成，共 ${data.lineCount} 条费用明细`)
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '应收试算失败') }
  finally { running.value = false }
}

async function generate() {
  if (!previewItems.value.length || previewErrors.value.length) return ElMessage.warning('请先完成无错误的试算并核对结果')
  running.value = true
  try {
    const idempotencyKey = `periodic-${period.value}-${previewChecksum.value.slice(0, 40)}`
    const { data } = await http.post('/receivable-jobs', requestBody(), { headers: { 'Idempotency-Key': idempotencyKey } })
    jobDetail.value = await awaitJob(data.jobId)
    detailVisible.value = true
    if (jobDetail.value.status === 'COMPLETED') ElMessage.success(`任务完成：生成 ${jobDetail.value.generatedCount} 条，跳过 ${jobDetail.value.skippedCount} 条`)
    else ElMessage.warning(`任务结束：${jobDetail.value.status}，错误 ${jobDetail.value.errorCount} 条`)
    await loadJobs()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || error.message || '应收生成失败') }
  finally { running.value = false }
}

async function awaitJob(jobId: string) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const { data } = await http.get(`/receivable-jobs/${jobId}`, { params: { communityId: communityId.value } })
    if (['COMPLETED', 'PARTIAL', 'FAILED'].includes(data.status)) return data
    await new Promise((resolve) => window.setTimeout(resolve, 250))
  }
  throw new Error('任务仍在后台执行，请稍后在任务列表查看')
}

async function openJob(row: Record<string, any>) {
  try {
    const { data } = await http.get(`/receivable-jobs/${row.id}`, { params: { communityId: communityId.value } })
    jobDetail.value = data
    detailVisible.value = true
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '任务详情加载失败') }
}

function resetPreview() {
  previewItems.value = []
  previewErrors.value = []
  previewTotal.value = '0.00'
  previewChecksum.value = ''
}

watch(communityId, () => { selected.value = []; resetPreview(); void Promise.all([loadAssets(), loadJobs()]) })
watch(period, resetPreview)
onMounted(() => void Promise.all([loadAssets(), loadJobs()]))
</script>

<template>
  <div class="workflow-page">
    <el-alert title="页面 10/49 · 周期应收采用“先试算、后入队、异步落账、自动对账”；金额使用 Decimal，历史账单保存完整版本快照。" type="info" show-icon :closable="false" />
    <el-card class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">RECEIVABLE WORKFLOW</span><h3>选择账期与计费资产</h3></div><el-tag effect="plain">已选择 {{ selected.length }} 项</el-tag></div></template>
      <el-form inline>
        <el-form-item label="计费账期"><el-date-picker v-model="period" type="month" value-format="YYYY-MM" format="YYYY 年 MM 月" :clearable="false" /></el-form-item>
        <el-form-item label="房屋检索"><el-input v-model="keyword" placeholder="房号或房屋名称" clearable @keyup.enter="loadAssets"><template #prefix><el-icon><Search /></el-icon></template></el-input></el-form-item>
        <el-form-item><el-button :icon="Refresh" :loading="loadingAssets" @click="loadAssets">查询</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loadingAssets" :data="assets" height="280" @selection-change="selected = $event; resetPreview()">
        <el-table-column type="selection" width="48" /><el-table-column prop="code" label="房屋编码" width="150" /><el-table-column prop="display_name" label="房屋名称" min-width="220" /><el-table-column prop="building_area" label="建筑面积" width="120" /><el-table-column prop="occupancy_status" label="入住状态" width="120" />
      </el-table>
      <div class="workflow-actions"><el-button type="primary" plain :loading="running" :disabled="selected.length === 0" @click="preview">计算应收预览</el-button><el-button v-if="canWrite" type="primary" :icon="DocumentChecked" :loading="running" :disabled="!previewItems.length || previewErrors.length > 0" @click="generate">创建异步生成任务</el-button></div>
    </el-card>

    <el-card v-if="previewItems.length || previewErrors.length" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">CALCULATION SNAPSHOT</span><h3>试算明细与版本证据</h3></div><div class="amount-summary"><span>试算合计</span><strong>¥ {{ previewTotal }}</strong></div></div></template>
      <el-table :data="previewItems" max-height="370"><el-table-column prop="assetName" label="资产" min-width="160" /><el-table-column prop="itemName" label="费用项目" min-width="160" /><el-table-column prop="quantity" label="数量" width="95" /><el-table-column prop="unitPrice" label="单价" width="95" /><el-table-column prop="coefficient" label="系数" width="85" /><el-table-column prop="amount" label="金额" width="105" /><el-table-column label="版本" width="90"><template #default="scope">V{{ scope.row.snapshot.feeStandardVersionNo }}</template></el-table-column><el-table-column label="舍入" width="120"><template #default="scope">{{ scope.row.snapshot.decimalScale }}位 / {{ scope.row.snapshot.roundingMode }}</template></el-table-column><el-table-column label="快照 SHA-256" min-width="155"><template #default="scope"><code>{{ scope.row.configurationChecksum.slice(0, 14) }}…</code></template></el-table-column></el-table>
      <el-alert v-if="previewErrors.length" type="error" :closable="false" :title="`${previewErrors.length} 条配置错误阻止生成`" />
      <el-table v-if="previewErrors.length" :data="previewErrors" max-height="180"><el-table-column prop="rowNo" label="行" width="60" /><el-table-column prop="targetId" label="对象" min-width="210" /><el-table-column prop="errorCode" label="错误码" width="220" /><el-table-column prop="errorMessage" label="原因" min-width="220" /></el-table>
      <div class="checksum-bar"><span>整批配置校验值</span><code>{{ previewChecksum }}</code></div>
    </el-card>

    <el-card class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">ASYNCHRONOUS JOBS</span><h3>周期应收任务与幂等重放</h3></div><el-button :icon="Refresh" @click="loadJobs">刷新</el-button></div></template>
      <el-table :data="jobs" max-height="270" empty-text="暂无周期应收任务"><el-table-column prop="createdAt" label="创建时间" width="180" /><el-table-column prop="billingPeriod" label="账期" width="95" /><el-table-column prop="requestKey" label="幂等请求键" min-width="210" show-overflow-tooltip /><el-table-column prop="requestedCount" label="请求行" width="85" /><el-table-column prop="generatedCount" label="生成" width="75" /><el-table-column prop="skippedCount" label="跳过" width="75" /><el-table-column prop="errorCount" label="错误" width="75" /><el-table-column prop="totalAmount" label="金额" width="105" /><el-table-column label="状态" width="105"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column><el-table-column label="操作" width="85" fixed="right"><template #default="scope"><el-button link type="primary" :icon="View" @click="openJob(scope.row)">详情</el-button></template></el-table-column></el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="应收生成任务证据" size="72%">
      <div v-if="jobDetail" class="job-detail">
        <el-descriptions :column="4" border><el-descriptions-item label="任务状态"><StatusTag :value="jobDetail.status" /></el-descriptions-item><el-descriptions-item label="任务类型">{{ jobDetail.jobType }}</el-descriptions-item><el-descriptions-item label="请求行">{{ jobDetail.requestedCount }}</el-descriptions-item><el-descriptions-item label="金额">¥ {{ jobDetail.totalAmount }}</el-descriptions-item><el-descriptions-item label="生成">{{ jobDetail.generatedCount }}</el-descriptions-item><el-descriptions-item label="跳过">{{ jobDetail.skippedCount }}</el-descriptions-item><el-descriptions-item label="错误">{{ jobDetail.errorCount }}</el-descriptions-item><el-descriptions-item label="账期">{{ jobDetail.billingPeriod }}</el-descriptions-item></el-descriptions>
        <h4>逐行生成结果</h4><el-table :data="jobDetail.items" max-height="260"><el-table-column prop="rowNo" label="行" width="60" /><el-table-column prop="itemName" label="费用项目" min-width="180" /><el-table-column prop="amount" label="金额" width="110" /><el-table-column prop="billId" label="账单 ID" min-width="240" /><el-table-column label="状态" width="105"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column></el-table>
        <h4>配置—应收—账单对账</h4><el-table :data="jobDetail.reconciliation"><el-table-column prop="metricName" label="指标" min-width="220" /><el-table-column prop="sourceValue" label="来源" width="120" /><el-table-column prop="targetValue" label="目标" width="120" /><el-table-column prop="differenceValue" label="差异" width="100" /><el-table-column label="状态" width="105"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column></el-table>
        <template v-if="jobDetail.errors?.length"><h4>错误明细</h4><el-table :data="jobDetail.errors"><el-table-column prop="rowNo" label="行" width="60" /><el-table-column prop="errorCode" label="错误码" width="230" /><el-table-column prop="errorMessage" label="错误原因" min-width="260" /></el-table></template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
code { color: #53667c; font-size: 11px; }
.checksum-bar { margin-top: 12px; padding: 9px 12px; display: flex; gap: 14px; align-items: center; border-radius: 6px; color: #708096; background: #f5f8fb; font-size: 11px; }.checksum-bar code { word-break: break-all; }
.job-detail { display: flex; flex-direction: column; gap: 14px; }.job-detail h4 { margin: 5px 0 -6px; color: #34435a; }
</style>
