<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type Column = { prop: string; label: string; width?: number; money?: boolean; status?: boolean }

const route = useRoute()
const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const title = computed(() => String(route.meta.title || '财务工作台'))
const mode = computed(() => {
  if (route.path.includes('daily-settlement')) return 'settlement'
  if (route.path.includes('adjustments')) return 'adjustments'
  if (route.path.includes('instruments')) return 'instruments'
  if (route.path.includes('discounts')) return 'discounts'
  if (route.path.includes('prepayment') || route.path.includes('deposits')) return 'balances'
  if (route.path.includes('invoice-replacements')) return 'invoices'
  if (route.path.includes('arrears')) return 'arrears'
  if (route.path.includes('/finance/bills')) return 'bills'
  return 'transactions'
})
const loading = ref(false)
const rows = ref<Row[]>([])
const auxiliaryRows = ref<Row[]>([])
const keyword = ref('')
const status = ref('')
const settlementDate = ref(new Date().toISOString().slice(0, 10))
const dateRange = ref<[string, string]>([new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10), new Date().toISOString().slice(0, 10)])
const summary = ref<Row>({})
const reconciliation = ref<Row>({})
const page = ref(1)
const pageSize = ref(30)
const dialog = ref('')
const activeRow = ref<Row>({})

const adjustmentForm = reactive({ billId: '', adjustmentType: 'WAIVER', amount: 0, reason: '' })
const discountForm = reactive({ policyCode: '', displayName: '', discountType: 'PERCENT', discountValue: 0.05, maximumAmount: 50, effectiveFrom: new Date().toISOString().slice(0, 10), approvalRequired: true })
const segmentForm = reactive({ segmentCode: '', numberPrefix: '', startNo: 1, endNo: 9999 })
const depositForm = reactive({ customerId: '', assetId: '', depositType: 'ACCESS_CARD', amount: 0, reason: '' })
const prepaymentForm = reactive({ accountId: '', billId: '', amount: 0 })

const columns = computed<Column[]>(() => ({
  bills: [
    { prop: 'bill_no', label: '账单号', width: 225 }, { prop: 'asset_code', label: '房产', width: 130 },
    { prop: 'customer_name', label: '客户', width: 130 }, { prop: 'billing_period', label: '账期', width: 90 },
    { prop: 'original_amount', label: '原应收', money: true }, { prop: 'adjustment_amount', label: '调整', money: true },
    { prop: 'paid_amount', label: '已收', money: true }, { prop: 'outstanding_amount', label: '未收', money: true },
    { prop: 'due_date', label: '到期日', width: 112 }, { prop: 'status', label: '状态', status: true, width: 105 },
  ],
  arrears: [
    { prop: 'bill_no', label: '账单号', width: 225 }, { prop: 'asset_code', label: '房产', width: 130 },
    { prop: 'customer_name', label: '客户', width: 130 }, { prop: 'billing_period', label: '账期', width: 90 },
    { prop: 'outstanding_amount', label: '欠费', money: true }, { prop: 'overdue_days', label: '逾期天数' },
    { prop: 'due_date', label: '到期日', width: 112 }, { prop: 'status', label: '状态', status: true, width: 105 },
  ],
  transactions: [
    { prop: 'transaction_no', label: '交易号', width: 250 }, { prop: 'order_no', label: '订单号', width: 250 },
    { prop: 'payment_channel', label: '渠道', width: 135 }, { prop: 'transaction_type', label: '类型', status: true, width: 110 },
    { prop: 'amount', label: '金额', money: true }, { prop: 'cashier_name', label: '经办人', width: 120 },
    { prop: 'settlement_id', label: '日结', width: 160 }, { prop: 'occurred_at', label: '发生时间', width: 180 },
  ],
  settlement: [
    { prop: 'settlement_date', label: '日结日', width: 115 }, { prop: 'transaction_count', label: '交易数' },
    { prop: 'gross_amount', label: '收款', money: true }, { prop: 'reversal_amount', label: '冲正', money: true },
    { prop: 'net_amount', label: '净额', money: true }, { prop: 'attached_transaction_count', label: '已归集' },
    { prop: 'status', label: '状态', status: true }, { prop: 'created_by_name', label: '日结人' },
  ],
  adjustments: [
    { prop: 'adjustment_no', label: '调账单号', width: 240 }, { prop: 'bill_no', label: '账单号', width: 220 },
    { prop: 'asset_code', label: '房产' }, { prop: 'adjustment_type', label: '类型', status: true },
    { prop: 'amount', label: '金额', money: true }, { prop: 'reason', label: '原因', width: 180 },
    { prop: 'status', label: '状态', status: true }, { prop: 'requested_by_name', label: '申请人' },
  ],
  instruments: [
    { prop: 'receipt_no', label: '收据号', width: 180 }, { prop: 'order_no', label: '收款订单', width: 240 },
    { prop: 'confirmed_amount', label: '金额', money: true }, { prop: 'payment_channel', label: '渠道' },
    { prop: 'original_receipt_no', label: '原收据号', width: 180 }, { prop: 'status', label: '状态', status: true },
    { prop: 'issued_at', label: '签发时间', width: 180 }, { prop: 'event_reason', label: '事件原因', width: 180 },
  ],
  discounts: [
    { prop: 'policy_code', label: '策略编码', width: 180 }, { prop: 'display_name', label: '名称', width: 180 },
    { prop: 'discount_type', label: '类型', status: true }, { prop: 'discount_value', label: '折扣值' },
    { prop: 'maximum_amount', label: '上限', money: true }, { prop: 'effective_from', label: '生效日' },
    { prop: 'effective_to', label: '失效日' }, { prop: 'status', label: '状态', status: true },
  ],
  balances: route.path.includes('deposits') ? [
    { prop: 'customer_no', label: '客户号', width: 160 }, { prop: 'customer_name', label: '客户' },
    { prop: 'asset_code', label: '房产' }, { prop: 'deposit_type', label: '押金类型' },
    { prop: 'balance', label: '余额', money: true }, { prop: 'transaction_count', label: '流水数' },
    { prop: 'status', label: '状态', status: true },
  ] : [
    { prop: 'customer_no', label: '客户号', width: 160 }, { prop: 'customer_name', label: '客户' },
    { prop: 'balance', label: '可用余额', money: true }, { prop: 'frozen_balance', label: '冻结', money: true },
    { prop: 'transaction_count', label: '流水数' }, { prop: 'version', label: '版本' },
  ],
  invoices: [
    { prop: 'request_no', label: '发票请求号', width: 260 }, { prop: 'receipt_no', label: '收据号', width: 180 },
    { prop: 'operation_type', label: '操作', status: true }, { prop: 'original_request_no', label: '原请求号', width: 220 },
    { prop: 'amount', label: '金额', money: true }, { prop: 'title_snapshot', label: '抬头', width: 180 },
    { prop: 'status', label: '状态', status: true }, { prop: 'created_at', label: '时间', width: 180 },
  ],
} as Record<string, Column[]>)[mode.value] || [])

const pagedRows = computed(() => rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const billChoices = computed(() => auxiliaryRows.value.filter((row) => Number(row.outstanding_amount) > 0))
const isDeposit = computed(() => route.path.includes('deposits'))
const dialogOpen = computed({ get: () => Boolean(dialog.value), set: (value) => { if (!value) dialog.value = '' } })
const dialogTitle = computed(() => ({ adjustment: '申请调账', discount: '新增折扣策略', segment: '新增票据号段', deposit: '收取押金', prepayment: '预收充值 / 抵扣' } as Record<string, string>)[dialog.value] || '财务操作')

async function load() {
  if (!communityId.value) return
  loading.value = true
  page.value = 1
  try {
    if (mode.value === 'bills') {
      const { data } = await http.get('/finance/bills', { params: { communityId: communityId.value, keyword: keyword.value || undefined, status: status.value || undefined, size: 200 } })
      rows.value = data.items; summary.value = { total: data.total }
    } else if (mode.value === 'arrears') {
      const { data } = await http.get('/finance/arrears', { params: { communityId: communityId.value, keyword: keyword.value || undefined, asOfDate: settlementDate.value } })
      rows.value = data.items; summary.value = data
    } else if (mode.value === 'settlement') {
      const [{ data: settlements }, { data: preview }, { data: recon }] = await Promise.all([
        http.get('/finance/settlements', { params: { communityId: communityId.value } }),
        http.get('/finance/settlements:preview', { params: { communityId: communityId.value, settlementDate: settlementDate.value } }),
        http.get('/finance/reconciliation', { params: { communityId: communityId.value } }),
      ])
      rows.value = settlements; summary.value = preview; reconciliation.value = recon
    } else if (mode.value === 'adjustments') {
      const [{ data: adjustments }, { data: bills }] = await Promise.all([
        http.get('/finance/adjustments', { params: { communityId: communityId.value, status: status.value || undefined } }),
        http.get('/finance/bills', { params: { communityId: communityId.value, size: 200 } }),
      ])
      rows.value = adjustments; auxiliaryRows.value = bills.items
    } else if (mode.value === 'instruments') {
      const [{ data: receipts }, { data: segments }] = await Promise.all([
        http.get('/finance/receipts', { params: { communityId: communityId.value, status: status.value || undefined } }),
        http.get('/finance/receipt-segments', { params: { communityId: communityId.value } }),
      ])
      rows.value = receipts; auxiliaryRows.value = segments
    } else if (mode.value === 'discounts') {
      const { data } = await http.get('/finance/discount-policies', { params: { communityId: communityId.value } })
      rows.value = data
    } else if (mode.value === 'balances') {
      const [{ data }, { data: context }] = await Promise.all([
        http.get('/finance/balances', { params: { communityId: communityId.value } }),
        http.get('/cashier/context', { params: { communityId: communityId.value } }),
      ])
      rows.value = isDeposit.value ? data.deposits : data.prepayments
      auxiliaryRows.value = context.bills
      summary.value = { customers: context.customers, assets: context.assets }
    } else if (mode.value === 'invoices') {
      const { data } = await http.get('/finance/invoices', { params: { communityId: communityId.value } })
      rows.value = data
    } else {
      const [{ data }, { data: recon }] = await Promise.all([
        http.get('/finance/transactions', { params: { communityId: communityId.value, from: dateRange.value?.[0], to: dateRange.value?.[1], channel: status.value || undefined } }),
        http.get('/finance/reconciliation', { params: { communityId: communityId.value } }),
      ])
      rows.value = data; reconciliation.value = recon
    }
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '财务数据加载失败')
  } finally {
    loading.value = false
  }
}

function openDialog(name: string, row: Row = {}) {
  dialog.value = name; activeRow.value = row
  if (name === 'adjustment') Object.assign(adjustmentForm, { billId: row.id || '', adjustmentType: 'WAIVER', amount: 0, reason: '' })
  if (name === 'deposit') Object.assign(depositForm, { customerId: '', assetId: '', depositType: 'ACCESS_CARD', amount: 0, reason: '' })
  if (name === 'prepayment') Object.assign(prepaymentForm, { accountId: row.id || '', billId: '', amount: 0 })
}

async function submitAdjustment() {
  await http.post('/finance/adjustments', { communityId: communityId.value, ...adjustmentForm }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  dialog.value = ''; ElMessage.success('调账申请已提交'); await load()
}

async function decideAdjustment(row: Row, decision: 'approve' | 'reject') {
  const { value: reason } = await ElMessageBox.prompt('请输入审批意见', decision === 'approve' ? '通过调账' : '驳回调账', { inputPattern: /\S+/, inputErrorMessage: '必须填写审批意见' })
  await http.post(`/finance/adjustments/${row.id}:${decision}`, { communityId: communityId.value, expectedVersion: row.version, reason })
  ElMessage.success('调账审批已处理'); await load()
}

async function submitDiscount() {
  await http.post('/finance/discount-policies', { communityId: communityId.value, ...discountForm, effectiveTo: null })
  dialog.value = ''; ElMessage.success('折扣策略已创建'); await load()
}

async function submitSegment() {
  await http.post('/finance/receipt-segments', { communityId: communityId.value, ...segmentForm })
  dialog.value = ''; ElMessage.success('票据号段已创建'); await load()
}

async function receiptAction(row: Row, action: 'replace' | 'void') {
  const { value: reason } = await ElMessageBox.prompt('请输入操作原因', action === 'replace' ? '换开收据' : '作废收据', { inputPattern: /\S+/, inputErrorMessage: '必须填写原因' })
  await http.post(`/finance/receipts/${row.id}:${action}`, { communityId: communityId.value, reason })
  ElMessage.success(action === 'replace' ? '收据换开完成' : '收据已作废'); await load()
}

async function closeSettlement() {
  await ElMessageBox.confirm(`确认关闭 ${settlementDate.value} 日结？关闭前必须完成交班。`, '日结确认', { type: 'warning' })
  await http.post('/finance/settlements', { communityId: communityId.value, settlementDate: settlementDate.value }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  ElMessage.success('日结已关闭'); await load()
}

async function lockSettlement(row: Row) {
  const { value: reason } = await ElMessageBox.prompt('锁定后所属交易不可直接冲正，请填写复核结论。', '锁定日结', { inputPattern: /\S+/, inputErrorMessage: '必须填写复核结论', type: 'warning' })
  await http.post(`/finance/settlements/${row.id}:lock`, { communityId: communityId.value, expectedVersion: row.version, reason })
  ElMessage.success('日结已锁定'); await load()
}

async function reverseTransaction(row: Row) {
  const { value: reason } = await ElMessageBox.prompt('请输入冲正原因', '交易冲正', { inputPattern: /\S+/, inputErrorMessage: '必须填写原因', type: 'warning' })
  await http.post(`/payment-transactions/${row.id}:reverse`, { communityId: communityId.value, reason })
  ElMessage.success('已生成关联反向流水'); await load()
}

async function invoiceAction(row: Row, operationType: 'REPLACE' | 'RED') {
  const { value: reason } = await ElMessageBox.prompt('请输入操作原因', operationType === 'RED' ? '发票红冲' : '换开发票', { inputPattern: /\S+/, inputErrorMessage: '必须填写原因', type: 'warning' })
  await http.post(`/finance/invoices/${row.id}:operate`, { communityId: communityId.value, operationType, title: row.title_snapshot, reason })
  ElMessage.success('模拟发票操作完成'); await load()
}

async function submitDeposit() {
  await http.post('/deposits', { communityId: communityId.value, ...depositForm, assetId: depositForm.assetId || null }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  dialog.value = ''; ElMessage.success('押金已收取'); await load()
}

async function refundDeposit(row: Row) {
  const { value } = await ElMessageBox.prompt(`当前余额 ¥ ${row.balance}，请输入退还金额`, '退还押金', { inputPattern: /^\d+(\.\d{1,2})?$/, inputErrorMessage: '请输入合法金额' })
  await http.post(`/deposits/${row.id}:refund`, { communityId: communityId.value, amount: Number(value), reason: '财务工作台押金退还' }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  ElMessage.success('押金退还完成'); await load()
}

async function prepaymentAction(action: 'top-up' | 'apply') {
  if (action === 'top-up') {
    await http.post(`/prepayment-accounts/${prepaymentForm.accountId}:top-up`, { communityId: communityId.value, amount: prepaymentForm.amount, reason: '财务工作台充值' }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  } else {
    await http.post(`/prepayment-accounts/${prepaymentForm.accountId}:apply`, { communityId: communityId.value, bills: [{ billId: prepaymentForm.billId, amount: prepaymentForm.amount }] }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
  }
  dialog.value = ''; ElMessage.success(action === 'top-up' ? '预收充值完成' : '预收抵扣完成'); await load()
}

function statusType(value: string) {
  if (['PAID', 'SUCCESS', 'APPLIED', 'ACTIVE', 'LOCKED', 'ISSUED'].includes(value)) return 'success'
  if (['PARTIAL', 'PENDING', 'CLOSED', 'REPLACE', 'REVERSAL'].includes(value)) return 'warning'
  if (['VOID', 'VOIDED', 'REJECTED', 'REVERSED', 'RED'].includes(value)) return 'danger'
  return 'info'
}

watch([communityId, () => route.path], load)
onMounted(load)
</script>

<template>
  <section class="financial-page">
    <el-alert type="warning" :closable="false" show-icon title="账务、余额、审批、号段和锁定规则均真实落库；支付与发票外部通道为明确标识的本地模拟适配器。" />
    <el-card shadow="never">
      <template #header><div class="page-head"><div><span class="section-kicker">FINANCIAL LEDGER</span><h3>{{ title }}</h3></div><div class="head-tags"><el-tag v-if="Object.keys(reconciliation).length" :type="reconciliation.healthy ? 'success' : 'danger'">对账 {{ reconciliation.healthy ? '一致' : '异常' }}</el-tag><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div></div></template>
      <div class="query-bar">
        <el-input v-if="['bills', 'arrears'].includes(mode)" v-model="keyword" clearable placeholder="账单、房产或客户" style="width: 230px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
        <el-select v-if="['bills', 'adjustments', 'instruments', 'transactions'].includes(mode)" v-model="status" clearable placeholder="状态 / 渠道" style="width: 160px"><el-option v-for="item in ['UNPAID','PARTIAL','PENDING','APPLIED','ISSUED','VOIDED','CASH','BANK_TRANSFER','QR_SIMULATOR']" :key="item" :label="item" :value="item" /></el-select>
        <el-date-picker v-if="['arrears', 'settlement'].includes(mode)" v-model="settlementDate" type="date" value-format="YYYY-MM-DD" />
        <el-date-picker v-if="mode === 'transactions'" v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日" end-placeholder="结束日" />
        <el-button @click="load">查询</el-button>
        <el-button v-if="mode === 'adjustments'" type="primary" @click="openDialog('adjustment')">申请调账</el-button>
        <el-button v-if="mode === 'discounts'" type="primary" @click="openDialog('discount')">新增折扣</el-button>
        <el-button v-if="mode === 'instruments'" type="primary" @click="openDialog('segment')">新增票据号段</el-button>
        <el-button v-if="mode === 'settlement'" type="primary" @click="closeSettlement">执行日结</el-button>
        <el-button v-if="mode === 'balances' && isDeposit" type="primary" @click="openDialog('deposit')">收取押金</el-button>
      </div>

      <div v-if="mode === 'arrears'" class="metric-strip"><el-statistic title="欠费笔数" :value="summary.count || 0" /><el-statistic title="欠费总额" :value="Number(summary.outstandingAmount || 0)" :precision="2" prefix="¥ " /><span>时间切片：{{ summary.asOfDate }}</span></div>
      <div v-if="mode === 'settlement'" class="metric-strip"><el-statistic title="待结交易" :value="summary.transactionCount || 0" /><el-statistic title="收款" :value="Number(summary.grossAmount || 0)" :precision="2" prefix="¥ " /><el-statistic title="冲正" :value="Number(summary.reversalAmount || 0)" :precision="2" prefix="¥ " /><el-statistic title="净额" :value="Number(summary.netAmount || 0)" :precision="2" prefix="¥ " /></div>
      <div v-if="mode === 'instruments'" class="segment-strip"><span>票据号段</span><el-tag v-for="segment in auxiliaryRows" :key="segment.id" :type="segment.status === 'ACTIVE' ? 'success' : 'info'">{{ segment.segment_code }} · 余 {{ segment.remaining_count }}</el-tag></div>

      <el-table v-loading="loading" :data="pagedRows" height="520" stripe>
        <el-table-column v-for="column in columns" :key="column.prop" :prop="column.prop" :label="column.label" :width="column.width" min-width="105" show-overflow-tooltip>
          <template #default="scope"><el-tag v-if="column.status" :type="statusType(String(scope.row[column.prop]))" effect="plain">{{ scope.row[column.prop] || '—' }}</el-tag><span v-else-if="column.money">¥ {{ Number(scope.row[column.prop] || 0).toFixed(2) }}</span><span v-else>{{ scope.row[column.prop] ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column v-if="['adjustments','instruments','settlement','transactions','balances','invoices','bills'].includes(mode)" label="操作" fixed="right" width="190">
          <template #default="scope">
            <template v-if="mode === 'adjustments' && scope.row.status === 'PENDING'"><el-button link type="primary" @click="decideAdjustment(scope.row, 'approve')">通过</el-button><el-button link type="danger" @click="decideAdjustment(scope.row, 'reject')">驳回</el-button></template>
            <template v-else-if="mode === 'instruments' && scope.row.status === 'ISSUED'"><el-button link type="primary" @click="receiptAction(scope.row, 'replace')">换开</el-button><el-button link type="danger" @click="receiptAction(scope.row, 'void')">作废</el-button></template>
            <el-button v-else-if="mode === 'settlement' && scope.row.status === 'CLOSED'" link type="warning" @click="lockSettlement(scope.row)">锁定</el-button>
            <el-button v-else-if="mode === 'transactions' && scope.row.transaction_type === 'PAYMENT' && !scope.row.original_transaction_id" link type="danger" @click="reverseTransaction(scope.row)">冲正</el-button>
            <template v-else-if="mode === 'balances'"><el-button v-if="isDeposit && Number(scope.row.balance) > 0" link type="danger" @click="refundDeposit(scope.row)">退押金</el-button><el-button v-if="!isDeposit" link type="primary" @click="openDialog('prepayment', scope.row)">充值 / 抵扣</el-button></template>
            <template v-else-if="mode === 'invoices' && scope.row.operation_type === 'ISSUE'"><el-button link type="primary" @click="invoiceAction(scope.row, 'REPLACE')">换开</el-button><el-button link type="danger" @click="invoiceAction(scope.row, 'RED')">红冲</el-button></template>
            <el-button v-else-if="mode === 'bills' && Number(scope.row.outstanding_amount) > 0" link type="primary" @click="openDialog('adjustment', scope.row)">调账</el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="rows.length" :page-sizes="[20,30,50,100]" layout="total, sizes, prev, pager, next" class="pager" />
    </el-card>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="560px">
      <el-form v-if="dialog === 'adjustment'" label-width="100px">
        <el-form-item label="账单"><el-select v-model="adjustmentForm.billId" filterable style="width:100%"><el-option v-for="bill in billChoices" :key="bill.id" :label="`${bill.bill_no} · 未收 ¥${bill.outstanding_amount}`" :value="bill.id" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="adjustmentForm.adjustmentType"><el-option v-for="item in ['DISCOUNT','WAIVER','CREDIT','DEBIT','VOID']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item v-if="adjustmentForm.adjustmentType !== 'VOID'" label="金额"><el-input-number v-model="adjustmentForm.amount" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="adjustmentForm.reason" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="dialog === 'discount'" label-width="100px">
        <el-form-item label="编码"><el-input v-model="discountForm.policyCode" /></el-form-item><el-form-item label="名称"><el-input v-model="discountForm.displayName" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="discountForm.discountType"><el-option label="比例" value="PERCENT" /><el-option label="固定金额" value="FIXED" /></el-select></el-form-item>
        <el-form-item label="折扣值"><el-input-number v-model="discountForm.discountValue" :min="0.000001" :max="discountForm.discountType === 'PERCENT' ? 1 : 999999" :precision="6" /></el-form-item>
        <el-form-item label="金额上限"><el-input-number v-model="discountForm.maximumAmount" :min="0" :precision="2" /></el-form-item><el-form-item label="生效日"><el-date-picker v-model="discountForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <el-form v-else-if="dialog === 'segment'" label-width="100px"><el-form-item label="号段编码"><el-input v-model="segmentForm.segmentCode" /></el-form-item><el-form-item label="票号前缀"><el-input v-model="segmentForm.numberPrefix" /></el-form-item><el-form-item label="起始号"><el-input-number v-model="segmentForm.startNo" :min="1" /></el-form-item><el-form-item label="结束号"><el-input-number v-model="segmentForm.endNo" :min="segmentForm.startNo" /></el-form-item></el-form>
      <el-form v-else-if="dialog === 'deposit'" label-width="100px"><el-form-item label="客户"><el-select v-model="depositForm.customerId" filterable style="width:100%"><el-option v-for="customer in summary.customers || []" :key="customer.id" :label="customer.display_name" :value="customer.id" /></el-select></el-form-item><el-form-item label="关联资产"><el-select v-model="depositForm.assetId" clearable filterable style="width:100%"><el-option v-for="asset in summary.assets || []" :key="asset.id" :label="`${asset.code} · ${asset.display_name}`" :value="asset.id" /></el-select></el-form-item><el-form-item label="押金类型"><el-input v-model="depositForm.depositType" /></el-form-item><el-form-item label="金额"><el-input-number v-model="depositForm.amount" :min="0.01" :precision="2" /></el-form-item><el-form-item label="原因"><el-input v-model="depositForm.reason" /></el-form-item></el-form>
      <el-form v-else-if="dialog === 'prepayment'" label-width="100px"><el-form-item label="账单"><el-select v-model="prepaymentForm.billId" filterable clearable style="width:100%"><el-option v-for="bill in billChoices" :key="bill.id" :label="`${bill.bill_no} · 未收 ¥${bill.outstanding_amount}`" :value="bill.id" /></el-select></el-form-item><el-form-item label="金额"><el-input-number v-model="prepaymentForm.amount" :min="0.01" :precision="2" /></el-form-item><el-alert type="info" :closable="false" title="不选择账单时执行充值；选择账单时执行预收抵扣。" /></el-form>
      <template #footer><el-button @click="dialog = ''">取消</el-button><el-button v-if="dialog === 'adjustment'" type="primary" @click="submitAdjustment">提交申请</el-button><el-button v-if="dialog === 'discount'" type="primary" @click="submitDiscount">保存</el-button><el-button v-if="dialog === 'segment'" type="primary" @click="submitSegment">保存</el-button><el-button v-if="dialog === 'deposit'" type="primary" @click="submitDeposit">确认收取</el-button><template v-if="dialog === 'prepayment'"><el-button type="primary" @click="prepaymentAction(prepaymentForm.billId ? 'apply' : 'top-up')">{{ prepaymentForm.billId ? '确认抵扣' : '确认充值' }}</el-button></template></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.financial-page { display: flex; flex-direction: column; gap: 12px; }
.page-head, .query-bar, .head-tags, .metric-strip, .segment-strip { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.page-head { justify-content: space-between; }.page-head h3 { margin: 2px 0 0; }.query-bar { margin-bottom: 12px; }
.metric-strip, .segment-strip { padding: 12px 14px; margin-bottom: 12px; border: 1px solid #e2e7ee; border-radius: 6px; background: #f8fafc; }
.metric-strip .el-statistic { min-width: 145px; }.segment-strip > span { color: #7e899b; font-size: 12px; }.pager { justify-content: flex-end; margin-top: 14px; }
</style>
