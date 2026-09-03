<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Download, Printer, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import BatchActionBar from '../components/shared/BatchActionBar.vue'
import FinancialOperationsView from './FinancialOperationsView.vue'
import { pageFor, type PageField } from '../config/pageCatalog'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { reportCodeByPath } from '../config/reporting'
import { serializeReportFilters } from '../config/reportFilters'

type Row = Record<string, any>
type FilterOption = { value: string; label: string }

const operationalPaths = new Set([
  '/reports/transaction-summary', '/reports/transaction-details', '/finance/payments', '/finance/arrears',
  '/finance/bills', '/reports/prepayments', '/finance/deposits', '/reports/daily-settlement-details', '/finance/adjustments',
])
const route = useRoute()
const auth = useAuthStore()
const catalogPage = computed(() => pageFor(route.path))
const reportCode = computed(() => reportCodeByPath[route.path])
const communityId = computed(() => auth.currentProjectId)
const values = reactive<Record<string, any>>({})
const report = ref<Row>({})
const rows = ref<Row[]>([])
const selectedRows = ref<Row[]>([])
const visibleColumnKeys = ref<string[]>([])
const exportJobs = ref<Row[]>([])
const printJobs = ref<Row[]>([])
const notificationBatches = ref<Row[]>([])
const remoteFilterOptions = ref<Record<string, FilterOption[]>>({})
const loading = ref(false)
const taskLoading = ref(false)
const page = ref(1)
const pageSize = ref(50)
const exportFormat = ref('XLSX')
const receiptFormat = ref('PDF')
const showOperations = ref(false)
let pollTimer: number | undefined
let reportLoadGeneration = 0
let taskLoadGeneration = 0
let filterOptionLoadGeneration = 0

const columns = computed<DataGridColumn[]>(() => (catalogPage.value?.columns || []).filter((field) => field.type !== 'selection').map((field) => ({
  prop: field.key,
  label: field.label,
  type: field.type,
  minWidth: ['text', 'masked', 'datetime'].includes(field.type) ? 155 : 116,
  sortable: true,
  sortKey: field.key,
})))
const selectedExportColumns = computed(() => {
  const renderable = new Set(columns.value.map((column) => column.prop))
  const visible = visibleColumnKeys.value.filter((key) => renderable.has(key))
  return visible.length ? visible : [...renderable]
})
const filterStorageKey = computed(() => `pms-report-filter:${communityId.value || 'no-project'}:${route.path}`)
const numericTotals = computed(() => report.value.summary?.numericTotals || {})
const canExport = computed(() => auth.hasPermission(catalogPage.value?.permissions.export))
const canPrint = computed(() => auth.hasPermission(catalogPage.value?.permissions.print))
const canNotify = computed(() => auth.hasPermission('notification:send'))
const canOperate = computed(() => operationalPaths.has(route.path))
const isReceiptPrint = computed(() => reportCode.value === 'RECEIPT_BATCH_PRINT')
const isNotification = computed(() => reportCode.value === 'BILL_NOTIFICATIONS')
const isBankSimulator = computed(() => reportCode.value === 'BANK_TRUST')
const hasUnavailableOrganizationScope = computed(() => new Set([
  '/reports/collection-rate', '/reports/arrears-clearance-rate',
  '/reports/collection-clearance-summary', '/reports/comprehensive-query',
]).has(route.path))
const reportRowKey = computed(() => {
  const available = report.value.columns || []
  return available.find((key: string) => /Id$/.test(key)) || available.find((key: string) => /No$/.test(key)) || available[0] || 'id'
})
const filterOptions: Record<string, string[]> = {
  paymentChannel: ['CASH', 'BANK_TRANSFER', 'QR_SIMULATOR', 'CASH_SIMULATOR', 'SIMULATOR', 'PREPAYMENT'],
  receiptStatus: ['ISSUED', 'VOIDED', 'REPLACED', 'RED_CORRECTED'],
  adjustmentType: ['DISCOUNT', 'WAIVER', 'CREDIT', 'DEBIT', 'VOID'],
  discountType: ['DISCOUNT', 'WAIVER', 'CREDIT'],
  entryType: ['TOP_UP', 'DEDUCT', 'REVERSAL'],
  channel: ['SMS_SIMULATOR', 'WECHAT_SIMULATOR', 'EMAIL_SIMULATOR'],
  deliveryStatus: ['SENT_SIMULATED', 'FAILED'],
  reminderType: ['ARREARS'],
  subjectType: ['ALL', 'BILL', 'PAYMENT'],
  invoiceType: ['ISSUE', 'REPLACE', 'RED'],
  invoiceStatus: ['SIMULATED', 'REPLACED', 'RED_CORRECTED', 'FAILED'],
}
const statusOptionsByReport: Record<string, string[]> = {
  TRANSACTION_DETAILS: ['SUCCESS', 'FAILED'],
  PAYMENTS: ['SUCCESS', 'FAILED'],
  BILLS: ['UNPAID', 'PARTIAL', 'PAID', 'VOID', 'VOIDED'],
  DEPOSITS: ['ACTIVE', 'REFUNDED', 'LOCKED'],
  ADJUSTMENTS: ['PENDING', 'APPLIED', 'REJECTED'],
}

function optionsFor(key: string): FilterOption[] {
  const remote = remoteFilterOptions.value[key]
  const values = key === 'status' ? statusOptionsByReport[reportCode.value] || [] : filterOptions[key] || []
  return remote || values.map((value) => ({ value, label: value }))
}

function inputType(field: PageField) {
  if (field.type === 'date-range') return 'daterange'
  if (field.type === 'month-range') return 'monthrange'
  if (field.type === 'month') return 'month'
  if (field.type === 'date') return 'date'
  return ''
}

function filters() {
  return serializeReportFilters(catalogPage.value?.query || [], values)
}

function runQuery() {
  page.value = 1
  void load()
}

async function load() {
  const generation = ++reportLoadGeneration
  const requestedCommunityId = communityId.value
  const requestedReportCode = reportCode.value
  if (!requestedCommunityId || !requestedReportCode) return
  loading.value = true
  try {
    const { data } = await http.get(`/reports/${requestedReportCode}`, {
      params: { communityId: requestedCommunityId, ...filters(), page: page.value, size: pageSize.value },
    })
    if (generation !== reportLoadGeneration || communityId.value !== requestedCommunityId || reportCode.value !== requestedReportCode) return
    report.value = data
    rows.value = data.rows
  } catch (error: any) {
    if (generation !== reportLoadGeneration || communityId.value !== requestedCommunityId || reportCode.value !== requestedReportCode) return
    ElMessage.error(error.response?.data?.message || '报表查询失败')
  } finally {
    if (generation === reportLoadGeneration) loading.value = false
  }
}

function reset() {
  Object.keys(values).forEach((key) => delete values[key])
  page.value = 1
  void load()
}

function changePage(value: number) {
  page.value = value
  void load()
}

function changePageSize(value: number) {
  pageSize.value = value
  page.value = 1
  void load()
}

function saveFilter() {
  localStorage.setItem(filterStorageKey.value, JSON.stringify(values))
  ElMessage.success('筛选条件已保存到本机')
}

function restoreFilter() {
  try {
    const restored = JSON.parse(localStorage.getItem(filterStorageKey.value) || '{}')
    if (!restored || Array.isArray(restored) || typeof restored !== 'object') throw new Error('invalid filter snapshot')
    const allowedKeys = new Set((catalogPage.value?.query || []).map((field) => field.key))
    Object.keys(values).forEach((key) => delete values[key])
    Object.entries(restored).forEach(([key, value]) => {
      if (allowedKeys.has(key)) values[key] = value
    })
    page.value = 1
    void load()
  } catch {
    localStorage.removeItem(filterStorageKey.value)
    Object.keys(values).forEach((key) => delete values[key])
    ElMessage.warning('已清除损坏的筛选条件')
  }
}

async function createExport() {
  if (!reportCode.value) return
  taskLoading.value = true
  try {
    await http.post('/report-jobs', {
      communityId: communityId.value, reportCode: reportCode.value, format: exportFormat.value,
      filters: filters(), selectedColumns: selectedExportColumns.value,
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('异步导出任务已入队，可在下方下载完成制品')
    await loadTasks(); startPolling()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '导出任务创建失败') }
  finally { taskLoading.value = false }
}

async function createReceiptPrint() {
  const receiptIds = selectedRows.value.map((row) => row.receiptId).filter(Boolean)
  if (!receiptIds.length) return ElMessage.warning('请至少选择一张已签发收据')
  taskLoading.value = true
  try {
    await http.post('/receipt-print-jobs', { communityId: communityId.value, receiptIds, format: receiptFormat.value },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    selectedRows.value = []
    ElMessage.success('批量打印任务已入队')
    await loadTasks(); startPolling()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '批量打印任务创建失败') }
  finally { taskLoading.value = false }
}

async function sendSimulatedNotification() {
  const period = filters().billingPeriodFrom || new Date().toISOString().slice(0, 7)
  taskLoading.value = true
  try {
    await http.post('/notification-batches', {
      communityId: communityId.value, billingPeriod: period, channel: 'SMS_SIMULATOR', billIds: [],
      contentTemplate: '账单 {billNo}，资产 {asset}，待缴金额 {amount}。本消息由验收模拟器生成。',
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('模拟通知已留痕；未调用真实短信或微信通道')
    await Promise.all([load(), loadTasks()])
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '模拟通知失败') }
  finally { taskLoading.value = false }
}

async function loadTasks() {
  const generation = ++taskLoadGeneration
  const requestedCommunityId = communityId.value
  const requestedReportCode = reportCode.value
  const requestedReceiptPrint = isReceiptPrint.value
  const requestedNotification = isNotification.value
  if (!requestedCommunityId) return
  const requests: Promise<any>[] = [http.get('/report-jobs', { params: { communityId: requestedCommunityId } })]
  if (requestedReceiptPrint) requests.push(http.get('/receipt-print-jobs', { params: { communityId: requestedCommunityId } }))
  if (requestedNotification) requests.push(http.get('/notification-batches', { params: { communityId: requestedCommunityId } }))
  const responses = await Promise.all(requests)
  if (generation !== taskLoadGeneration || communityId.value !== requestedCommunityId || reportCode.value !== requestedReportCode) return
  exportJobs.value = responses[0].data.filter((job: Row) => job.report_code === requestedReportCode)
  printJobs.value = requestedReceiptPrint ? responses[1].data : []
  notificationBatches.value = requestedNotification ? responses.at(-1)?.data || [] : []
}

async function loadFilterOptions() {
  const generation = ++filterOptionLoadGeneration
  const requestedCommunityId = communityId.value
  const requestedReportCode = reportCode.value
  remoteFilterOptions.value = {}
  if (!requestedCommunityId) return
  const keys = new Set((catalogPage.value?.query || []).map((field) => field.key))
  if (!keys.has('feeDefinitionId') && !keys.has('cashierId')) return
  try {
    const { data } = await http.get('/reports/filter-options', {
      params: { communityId: requestedCommunityId, reportCode: requestedReportCode },
    })
    if (generation !== filterOptionLoadGeneration || communityId.value !== requestedCommunityId || reportCode.value !== requestedReportCode) return
    remoteFilterOptions.value = {
      feeDefinitionId: Array.isArray(data.feeDefinitionId) ? data.feeDefinitionId : [],
      cashierId: Array.isArray(data.cashierId) ? data.cashierId : [],
    }
  } catch (error: any) {
    if (generation !== filterOptionLoadGeneration || communityId.value !== requestedCommunityId || reportCode.value !== requestedReportCode) return
    ElMessage.error(error.response?.data?.message || '报表筛选选项加载失败')
  }
}

function startPolling() {
  window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    await loadTasks()
    const pending = [...exportJobs.value, ...printJobs.value].some((job) => ['QUEUED', 'RUNNING'].includes(job.status))
    if (!pending) window.clearInterval(pollTimer)
  }, 700)
}

async function downloadArtifact(job: Row, receipt = false) {
  const path = receipt ? `/receipt-print-jobs/${job.id}/artifact` : `/report-jobs/${job.id}/artifact`
  const { data } = await http.get(path, { params: { communityId: communityId.value }, responseType: 'blob' })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a'); link.href = url; link.download = job.artifact_name || 'report-artifact'
  link.click(); URL.revokeObjectURL(url)
}

function taskType(status: string) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

watch([communityId, () => route.path], async ([, currentPath], previous) => {
  Object.keys(values).forEach((key) => delete values[key])
  selectedRows.value = []; page.value = 1; showOperations.value = false
  report.value = {}; rows.value = []; exportJobs.value = []; printJobs.value = []; notificationBatches.value = []
  if (!previous || previous[1] !== currentPath) visibleColumnKeys.value = []
  await Promise.all([load(), loadTasks(), loadFilterOptions()])
}, { immediate: true })
onMounted(() => startPolling())
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section v-if="catalogPage" class="report-page">
    <el-alert v-if="isBankSimulator" type="warning" :closable="false" show-icon
      title="银行托收协议尚未授权：当前只展示 BANK_TRUST_SIMULATOR 能力状态，尚无可按日期、通道或对账状态查询的托收批次数据。" />
    <el-alert v-else-if="hasUnavailableOrganizationScope" type="warning" :closable="false" show-icon
      title="当前报表严格按顶部所选项目隔离；系统尚无“组织—资产责任范围”模型，因此不提供会被忽略的组织范围筛选。" />
    <el-alert v-else type="info" :closable="false" show-icon
      title="数据来自项目隔离的后端读模型；客户姓名默认脱敏，导出、打印、下载和通知均持久化留痕。" />

    <el-card shadow="never" class="definition-card">
      <template #header>
        <div class="page-head"><div><span class="section-kicker">GOVERNED REPORT · {{ reportCode }}</span><h3>{{ catalogPage.title }}</h3></div>
          <div class="head-actions"><el-tag effect="plain">{{ report.durationMs ?? 0 }} ms</el-tag><el-tag effect="plain">明细 {{ report.total ?? 0 }}</el-tag>
            <el-button v-if="canOperate" @click="showOperations = !showOperations">{{ showOperations ? '返回统计视图' : '业务处理' }}</el-button>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div></div>
      </template>
      <div class="evidence-grid">
        <div><span>行粒度</span><strong>{{ report.rowGrain || '加载中' }}</strong></div>
        <div><span>公式口径</span><strong>{{ report.formulaNote || '加载中' }}</strong></div>
        <div><span>固定算例</span><code>{{ JSON.stringify(report.fixedSample || {}) }}</code></div>
        <div><span>查询凭证</span><code>{{ report.queryChecksum || '—' }}</code></div>
      </div>
    </el-card>

    <FinancialOperationsView v-if="showOperations && canOperate" />

    <template v-else>
      <QueryPanel :loading="loading" @query="runQuery" @reset="reset" @save="saveFilter" @restore="restoreFilter">
        <el-form-item v-for="field in catalogPage.query" :key="field.key" :label="field.label">
          <el-date-picker v-if="inputType(field)" v-model="values[field.key]" :type="inputType(field) as any"
            :value-format="field.type.includes('month') ? 'YYYY-MM' : 'YYYY-MM-DD'" clearable :placeholder="field.label" :start-placeholder="`${field.label}开始`"
            :end-placeholder="`${field.label}结束`" style="width:220px" />
          <el-select v-else-if="field.type === 'select'" v-model="values[field.key]" clearable filterable
            :placeholder="field.label" style="width:190px">
            <el-option v-for="option in optionsFor(field.key)" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-else v-model="values[field.key]" clearable :placeholder="field.label" style="width:190px">
            <template v-if="field.type === 'text'" #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
      </QueryPanel>

      <div v-if="Object.keys(numericTotals).length" class="metric-strip">
        <el-statistic v-for="(value, key) in numericTotals" :key="key" :title="String(key)" :value="Number(value)" :precision="2" />
      </div>

      <BatchActionBar :selected-count="selectedRows.length" :total="report.total || 0" @clear="selectedRows = []">
        <el-select v-if="isReceiptPrint" v-model="receiptFormat" size="small" style="width:100px"><el-option label="PDF" value="PDF" /><el-option label="打印 HTML" value="PRINT" /></el-select>
        <el-button v-if="isReceiptPrint" size="small" type="primary" :icon="Printer" :loading="taskLoading" @click="createReceiptPrint">批量打印所选</el-button>
      </BatchActionBar>

      <DataGrid :page="page" :page-size="pageSize" :rows="rows" :columns="columns" :loading="loading" :row-key="reportRowKey"
        :total="report.total || 0" :storage-key="`governed:${route.path}`" @selection-change="selectedRows = $event"
        @visible-columns-change="visibleColumnKeys = $event"
        @reload="load" @update:page="changePage" @update:page-size="changePageSize">
        <template #toolbar>
          <el-button v-if="isNotification && canNotify" type="primary" :loading="taskLoading" @click="sendSimulatedNotification">发送模拟通知</el-button>
          <el-select v-if="canExport" v-model="exportFormat" style="width:112px"><el-option v-for="item in ['CSV','XLSX','PDF','PRINT']" :key="item" :label="item" :value="item" /></el-select>
          <el-button v-if="canExport" :icon="Download" :loading="taskLoading" @click="createExport">异步导出</el-button>
        </template>
      </DataGrid>

      <el-card v-if="exportJobs.length || printJobs.length || notificationBatches.length" shadow="never" class="task-card">
        <template #header><div class="page-head"><strong>后端任务与审计凭证</strong><el-button link @click="loadTasks">刷新任务</el-button></div></template>
        <el-table :data="[...exportJobs, ...printJobs, ...notificationBatches].slice(0, 20)" size="small">
          <el-table-column prop="created_at" label="创建时间" width="185" /><el-table-column prop="report_code" label="任务" min-width="160"><template #default="scope">{{ scope.row.report_code || scope.row.batch_no || 'RECEIPT_PRINT' }}</template></el-table-column>
          <el-table-column label="类型" width="110"><template #default="scope">{{ scope.row.export_format || scope.row.output_format || scope.row.channel }}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="taskType(scope.row.status)" effect="plain">{{ scope.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="artifact_checksum" label="制品 SHA-256" min-width="245" show-overflow-tooltip />
          <el-table-column label="操作" width="90"><template #default="scope"><el-button v-if="scope.row.status === 'SUCCEEDED' && scope.row.artifact_name" link type="primary" @click="downloadArtifact(scope.row, Boolean(scope.row.output_format))">下载</el-button><span v-else>—</span></template></el-table-column>
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.report-page { display:flex; flex-direction:column; gap:12px; }
.page-head,.head-actions,.metric-strip { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.page-head { justify-content:space-between; }.page-head h3 { margin:2px 0 0; }
.evidence-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.evidence-grid>div { min-height:74px; display:flex; flex-direction:column; gap:6px; padding:11px 13px; border:1px solid #e2e7ee; border-radius:7px; background:#f8fafc; }
.evidence-grid span { color:#7e899b; font-size:11px; }.evidence-grid strong { font-size:13px; font-weight:500; line-height:1.55; }
.evidence-grid code { color:#344054; font-size:11px; word-break:break-all; white-space:normal; }
.metric-strip { padding:12px 14px; border:1px solid #e2e7ee; border-radius:7px; background:#fff; }
.metric-strip .el-statistic { min-width:145px; }.task-card { margin-top:2px; }
@media (max-width:900px) { .evidence-grid { grid-template-columns:1fr; } }
</style>
