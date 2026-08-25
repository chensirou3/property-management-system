<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, DocumentChecked, Plus, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
interface TemporaryLine { key: string; feeDefinitionId: string; itemName: string; quantity: string; unitPrice: string; coefficient: string }

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const canWrite = computed(() => auth.hasPermission('fee:write'))
const assets = ref<Row[]>([])
const customers = ref<Row[]>([])
const definitions = ref<Row[]>([])
const jobs = ref<Row[]>([])
const loading = ref(false)
const running = ref(false)
const previewResult = ref<any>(null)
const jobDetail = ref<any>(null)
const detailVisible = ref(false)
const form = reactive({ assetId: '', customerId: '', chargeDate: dayjs().format('YYYY-MM-DD'), dueDate: dayjs().endOf('month').format('YYYY-MM-DD') })
const lines = ref<TemporaryLine[]>([newLine()])

function newLine(): TemporaryLine {
  return { key: crypto.randomUUID(), feeDefinitionId: '', itemName: '', quantity: '1', unitPrice: '0.00', coefficient: '1' }
}

const eligibleDefinitions = computed(() => definitions.value.filter((item) => item.enabled && item.temporaryAllowed))
const total = computed(() => previewResult.value?.totalAmount || '0.00')

async function load() {
  if (!communityId.value) return
  loading.value = true
  try {
    const [assetResponse, customerResponse, definitionResponse, jobResponse] = await Promise.all([
      http.get('/data/assets', { params: { communityId: communityId.value, page: 1, size: 100, sort: 'code,asc' } }),
      http.get('/data/customers', { params: { communityId: communityId.value, page: 1, size: 100, sort: 'displayName,asc' } }),
      http.get('/fees/definitions', { params: { communityId: communityId.value } }),
      http.get('/receivable-jobs', { params: { communityId: communityId.value, jobType: 'TEMPORARY' } }),
    ])
    assets.value = assetResponse.data.items
    customers.value = customerResponse.data.items
    definitions.value = definitionResponse.data
    jobs.value = jobResponse.data
    if (!form.assetId) form.assetId = assets.value[0]?.id || ''
    if (!lines.value[0].feeDefinitionId) lines.value[0].feeDefinitionId = eligibleDefinitions.value[0]?.id || ''
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '临时应收工作区加载失败') }
  finally { loading.value = false }
}

function requestBody() {
  return { communityId: communityId.value, assetId: form.assetId, customerId: form.customerId || null,
    chargeDate: form.chargeDate, dueDate: form.dueDate, lines: lines.value.map(({ feeDefinitionId, itemName, quantity, unitPrice, coefficient }) => ({ feeDefinitionId, itemName, quantity, unitPrice, coefficient })) }
}

function addLine() {
  const line = newLine()
  line.feeDefinitionId = eligibleDefinitions.value[0]?.id || ''
  lines.value.push(line)
  previewResult.value = null
}

function removeLine(index: number) {
  if (lines.value.length === 1) return ElMessage.warning('至少保留一条临时费用')
  lines.value.splice(index, 1)
  previewResult.value = null
}

function validateInput() {
  if (!form.assetId || !form.chargeDate || !form.dueDate) { ElMessage.warning('请选择资产和计费日期'); return false }
  if (lines.value.some((line) => !line.feeDefinitionId || !line.itemName || Number(line.quantity) <= 0 || Number(line.unitPrice) < 0 || Number(line.coefficient) <= 0)) {
    ElMessage.warning('请完整填写每条费用的定义、说明、数量、单价和系数'); return false
  }
  return true
}

async function preview() {
  if (!validateInput()) return
  running.value = true
  try {
    const { data } = await http.post('/temporary-receivables:preview', requestBody())
    previewResult.value = data
    jobDetail.value = null
    if (data.errorCount) ElMessage.warning(`校验完成，${data.errorCount} 条存在错误`)
    else ElMessage.success(`校验通过，共 ${data.lineCount} 条临时应收`)
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '临时应收校验失败') }
  finally { running.value = false }
}

async function generate() {
  if (!previewResult.value || previewResult.value.errorCount) return ElMessage.warning('请先完成无错误的校验预览')
  running.value = true
  try {
    const key = `temporary-${form.assetId.slice(-12)}-${form.chargeDate}-${previewResult.value.configurationChecksum.slice(0, 24)}`
    const { data } = await http.post('/temporary-receivable-jobs', requestBody(), { headers: { 'Idempotency-Key': key } })
    jobDetail.value = await awaitJob(data.jobId)
    detailVisible.value = true
    ElMessage.success(jobDetail.value.status === 'COMPLETED' ? '临时应收已生成并完成零差异对账' : `任务结束：${jobDetail.value.status}`)
    await loadJobs()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '临时应收生成失败') }
  finally { running.value = false }
}

async function awaitJob(jobId: string) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const { data } = await http.get(`/receivable-jobs/${jobId}`, { params: { communityId: communityId.value } })
    if (['COMPLETED', 'PARTIAL', 'FAILED'].includes(data.status)) return data
    await new Promise((resolve) => window.setTimeout(resolve, 250))
  }
  throw new Error('任务仍在后台执行，请稍后在任务列表查看')
}

async function loadJobs() {
  const { data } = await http.get('/receivable-jobs', { params: { communityId: communityId.value, jobType: 'TEMPORARY' } })
  jobs.value = data
}

async function openJob(row: Row) {
  try {
    const { data } = await http.get(`/receivable-jobs/${row.id}`, { params: { communityId: communityId.value } })
    jobDetail.value = data
    detailVisible.value = true
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '任务详情加载失败') }
}

watch(communityId, () => { previewResult.value = null; void load() })
watch(lines, () => { previewResult.value = null }, { deep: true })
onMounted(() => void load())
</script>

<template>
  <section v-loading="loading" class="workflow-page temporary-page">
    <el-alert title="页面 11/49 · 临时应收使用独立手工数量与单价，不复用周期费用标准；落账后保存不可变计算快照。" type="info" show-icon :closable="false" />
    <el-card class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">TEMPORARY RECEIVABLE</span><h3>资产、客户与计费日期</h3></div><el-tag type="warning" effect="plain">独立临时口径</el-tag></div></template>
      <el-form inline>
        <el-form-item label="计费资产"><el-select v-model="form.assetId" filterable style="width: 260px"><el-option v-for="item in assets" :key="item.id" :label="`${item.code} · ${item.display_name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="关联客户"><el-select v-model="form.customerId" filterable clearable placeholder="自动取主要关系客户" style="width: 220px"><el-option v-for="item in customers" :key="item.id" :label="item.display_name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="计费日期"><el-date-picker v-model="form.chargeDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" /></el-form-item>
        <el-form-item label="到期日期"><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" /></el-form-item>
      </el-form>
    </el-card>

    <el-card class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">MANUAL LINES</span><h3>临时费用明细</h3></div><el-button :icon="Plus" @click="addLine">增加一行</el-button></div></template>
      <div class="temporary-lines">
        <div v-for="(line, index) in lines" :key="line.key" class="temporary-line">
          <span class="line-no">{{ index + 1 }}</span>
          <el-select v-model="line.feeDefinitionId" filterable placeholder="费用定义"><el-option v-for="item in eligibleDefinitions" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select>
          <el-input v-model="line.itemName" placeholder="临时费用说明" />
          <el-input v-model="line.quantity" placeholder="数量"><template #prepend>数量</template></el-input>
          <el-input v-model="line.unitPrice" placeholder="单价"><template #prepend>¥</template></el-input>
          <el-input v-model="line.coefficient" placeholder="系数"><template #prepend>系数</template></el-input>
          <el-button text type="danger" :icon="Delete" @click="removeLine(index)" />
        </div>
      </div>
      <el-alert v-if="!eligibleDefinitions.length" type="warning" :closable="false" title="当前没有允许临时应收的费用定义，请先在费用定义页面启用“临时应收”。" />
      <div class="workflow-actions"><el-button :icon="Refresh" :loading="running" @click="preview">校验并预览</el-button><el-button v-if="canWrite" type="primary" :icon="DocumentChecked" :loading="running" :disabled="!previewResult || previewResult.errorCount" @click="generate">确认生成临时应收</el-button></div>
    </el-card>

    <el-card v-if="previewResult" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">IMMUTABLE SNAPSHOT</span><h3>校验与金额快照</h3></div><div class="amount-summary"><span>临时应收合计</span><strong>¥ {{ total }}</strong></div></div></template>
      <el-table :data="previewResult.items" max-height="300"><el-table-column prop="itemName" label="费用说明" min-width="200" /><el-table-column prop="quantity" label="数量" width="100" /><el-table-column prop="unitPrice" label="单价" width="110" /><el-table-column prop="coefficient" label="系数" width="90" /><el-table-column prop="amount" label="金额" width="120" /><el-table-column label="快照校验" min-width="190"><template #default="scope"><code>{{ scope.row.configurationChecksum.slice(0, 16) }}…</code></template></el-table-column></el-table>
    </el-card>

    <el-card class="workflow-card" shadow="never"><template #header><div class="workflow-card__header"><div><span class="section-kicker">ASYNC JOBS</span><h3>临时应收任务</h3></div><el-button :icon="Refresh" @click="loadJobs">刷新</el-button></div></template>
      <el-table :data="jobs" max-height="250" empty-text="暂无临时应收任务"><el-table-column prop="createdAt" label="创建时间" width="175" /><el-table-column prop="billingPeriod" label="账期" width="95" /><el-table-column prop="requestedCount" label="请求行" width="85" /><el-table-column prop="generatedCount" label="生成行" width="85" /><el-table-column prop="errorCount" label="错误" width="75" /><el-table-column prop="totalAmount" label="金额" width="110" /><el-table-column label="状态" width="100"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column><el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="openJob(scope.row)">详情</el-button></template></el-table-column></el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="临时应收任务证据" size="68%"><div v-if="jobDetail" class="job-evidence"><el-descriptions :column="4" border><el-descriptions-item label="任务状态"><StatusTag :value="jobDetail.status" /></el-descriptions-item><el-descriptions-item label="请求行">{{ jobDetail.requestedCount }}</el-descriptions-item><el-descriptions-item label="生成/跳过">{{ jobDetail.generatedCount }} / {{ jobDetail.skippedCount }}</el-descriptions-item><el-descriptions-item label="错误">{{ jobDetail.errorCount }}</el-descriptions-item></el-descriptions><h4>对账结果</h4><el-table :data="jobDetail.reconciliation"><el-table-column prop="metricName" label="指标" min-width="220" /><el-table-column prop="sourceValue" label="来源" width="120" /><el-table-column prop="targetValue" label="目标" width="120" /><el-table-column prop="differenceValue" label="差异" width="100" /><el-table-column label="状态" width="105"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column></el-table></div></el-drawer>
  </section>
</template>

<style scoped>
.temporary-lines { display: flex; flex-direction: column; gap: 10px; margin-bottom: 14px; }
.temporary-line { display: grid; grid-template-columns: 30px minmax(190px, 1.2fr) minmax(190px, 1.2fr) 150px 145px 140px 38px; gap: 8px; align-items: center; }
.line-no { width: 26px; height: 26px; display: grid; place-items: center; border-radius: 50%; color: #52708d; background: #edf4fb; font-size: 11px; }
code { color: #53667c; font-size: 11px; }
.job-evidence { display: flex; flex-direction: column; gap: 16px; }.job-evidence h4 { margin: 4px 0 -8px; }
@media (max-width: 1300px) { .temporary-line { grid-template-columns: 30px 1fr 1fr 130px 130px; }.temporary-line > :nth-child(6), .temporary-line > :nth-child(7) { grid-column: auto; } }
</style>
