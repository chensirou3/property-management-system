<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CreditCard, Refresh, Search } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

interface BillRow {
  id: string
  bill_no: string
  asset_id: string
  customer_id?: string
  billing_period: string
  total_amount: string
  paid_amount: string
  outstanding_amount: string
  due_date: string
  status: string
}

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const keyword = ref('')
const bills = ref<BillRow[]>([])
const selected = ref<BillRow[]>([])
const amounts = reactive<Record<string, number>>({})
const loading = ref(false)
const paying = ref(false)
const paymentMethod = ref('QR_SIMULATOR')
const lastPayment = ref<any>(null)
const invoice = ref<any>(null)
const currentShift = ref<any>(null)
const openingCash = ref(0)
const actualCash = ref(0)
const shiftBusy = ref(false)
const selectedTotal = computed(() => selected.value.reduce((sum, item) => sum + Number(amounts[item.id] || 0), 0).toFixed(2))
const cashRequiresShift = computed(() => paymentMethod.value === 'CASH' && !currentShift.value)

function handleSelection(rows: BillRow[]) {
  selected.value = rows
  rows.forEach((bill) => {
    if (amounts[bill.id] === undefined) amounts[bill.id] = Number(bill.outstanding_amount)
  })
}

async function loadShift() {
  if (!communityId.value) return
  const { data } = await http.get('/cashier/shifts:current', { params: { communityId: communityId.value } })
  currentShift.value = data.open ? data.shift : null
  if (currentShift.value) actualCash.value = Number(currentShift.value.opening_cash) + Number(currentShift.value.cash_movement || 0)
}

async function loadContext() {
  if (!communityId.value) return
  loading.value = true
  try {
    const [{ data }] = await Promise.all([
      http.get('/cashier/context', { params: { communityId: communityId.value, keyword: keyword.value || undefined } }),
      loadShift(),
    ])
    bills.value = data.bills
    selected.value = []
    Object.keys(amounts).forEach((key) => delete amounts[key])
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '收银上下文加载失败')
  } finally {
    loading.value = false
  }
}

async function openShift() {
  shiftBusy.value = true
  try {
    await http.post('/cashier/shifts', { communityId: communityId.value, openingCash: openingCash.value },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('班次已开启，可以办理现金收款')
    await loadShift()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '班次开启失败')
  } finally {
    shiftBusy.value = false
  }
}

async function closeShift() {
  if (!currentShift.value) return
  await ElMessageBox.confirm(`系统应有现金 ¥ ${currentShift.value.opening_cash} + ${currentShift.value.cash_movement || 0}，实盘 ¥ ${actualCash.value.toFixed(2)}。确认交班？`, '交班复核', { type: 'warning' })
  shiftBusy.value = true
  try {
    await http.post(`/cashier/shifts/${currentShift.value.id}:close`, {
      communityId: communityId.value, actualCash: actualCash.value, expectedVersion: currentShift.value.version,
    })
    ElMessage.success('交班完成，班次已关闭')
    await loadShift()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '交班失败')
  } finally {
    shiftBusy.value = false
  }
}

async function collect() {
  if (!selected.value.length) return ElMessage.warning('请选择至少一张待收账单')
  if (cashRequiresShift.value) return ElMessage.warning('现金收款前必须开启班次')
  const invalid = selected.value.find((bill) => !amounts[bill.id] || amounts[bill.id] <= 0 || amounts[bill.id] > Number(bill.outstanding_amount))
  if (invalid) return ElMessage.warning(`账单 ${invalid.bill_no} 的本次收款金额不合法`)
  await ElMessageBox.confirm(`确认通过${channelLabel(paymentMethod.value)}收取 ¥ ${selectedTotal.value}？`, '收款确认', {
    confirmButtonText: '确认收款', cancelButtonText: '取消', type: 'warning',
  })
  paying.value = true
  try {
    const { data: order } = await http.post('/payment-orders', {
      communityId: communityId.value,
      paymentMethod: paymentMethod.value,
      bills: selected.value.map((bill) => ({ billId: bill.id, amount: amounts[bill.id] })),
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    const { data } = await http.post(`/payment-orders/${order.orderId}:confirm-simulated`, null, { params: { communityId: communityId.value } })
    lastPayment.value = data
    invoice.value = null
    ElMessage.success('收款成功，账单、流水与收据已同步记账')
    await loadContext()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '收款失败')
  } finally {
    paying.value = false
  }
}

async function reverseLastPayment() {
  if (!lastPayment.value?.transactionId) return
  const { value: reason } = await ElMessageBox.prompt('请输入冲正原因。若所属日结已锁定，系统会拒绝操作。', '交易冲正', {
    inputPattern: /\S+/, inputErrorMessage: '必须填写原因', type: 'warning',
  })
  try {
    await http.post(`/payment-transactions/${lastPayment.value.transactionId}:reverse`, { communityId: communityId.value, reason })
    ElMessage.success('冲正完成，已生成关联反向流水')
    lastPayment.value = null
    await loadContext()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '冲正失败')
  }
}

async function issueInvoice() {
  if (!lastPayment.value?.receiptId) return
  const { value: title } = await ElMessageBox.prompt('请输入本次开票的购方名称。', '模拟开票', {
    inputPattern: /\S+/, inputErrorMessage: '必须填写购方名称', confirmButtonText: '确认开票',
  })
  try {
    const { data } = await http.post('/invoices:simulate', {
      communityId: communityId.value, receiptId: lastPayment.value.receiptId, title,
    })
    invoice.value = data
    ElMessage.success('模拟开票完成')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '模拟开票失败')
  }
}

function channelLabel(channel: string) {
  return ({ CASH: '现金', BANK_TRANSFER: '银行转账（模拟）', QR_SIMULATOR: '扫码支付（模拟）' } as Record<string, string>)[channel] || channel
}

watch(communityId, loadContext)
onMounted(loadContext)
</script>

<template>
  <div class="workflow-page cashier-page">
    <el-alert title="支付与发票均为本地可替换模拟适配器；现金班次、账单余额、流水、收据、冲正与日结使用真实账务规则。" type="warning" show-icon :closable="false" />

    <el-card class="workflow-card shift-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">CASH SHIFT</span><h3>收银班次</h3></div><el-tag :type="currentShift ? 'success' : 'info'">{{ currentShift ? 'OPEN' : '未开班' }}</el-tag></div></template>
      <div v-if="currentShift" class="shift-grid">
        <el-statistic title="备用金" :value="Number(currentShift.opening_cash)" :precision="2" prefix="¥ " />
        <el-statistic title="现金净变动" :value="Number(currentShift.cash_movement || 0)" :precision="2" prefix="¥ " />
        <el-statistic title="交易数" :value="Number(currentShift.transaction_count || 0)" />
        <el-input-number v-model="actualCash" :precision="2" :min="0" :controls="false" /><span class="field-hint">实盘现金</span>
        <el-button type="warning" plain :loading="shiftBusy" @click="closeShift">交班并关闭</el-button>
      </div>
      <div v-else class="shift-open"><span>备用金</span><el-input-number v-model="openingCash" :precision="2" :min="0" /><el-button type="primary" :loading="shiftBusy" @click="openShift">开启班次</el-button><span class="field-hint">仅现金收款强制要求开启班次</span></div>
    </el-card>

    <el-card class="workflow-card" shadow="never">
      <template #header>
        <div class="workflow-card__header">
          <div><span class="section-kicker">CASHIER DESK</span><h3>待收账单</h3></div>
          <div class="amount-summary"><span>本次选择</span><strong>¥ {{ selectedTotal }}</strong></div>
        </div>
      </template>
      <el-form inline>
        <el-form-item label="综合检索">
          <el-input v-model="keyword" clearable placeholder="客户、房号或账单号" @keyup.enter="loadContext"><template #prefix><el-icon><Search /></el-icon></template></el-input>
        </el-form-item>
        <el-form-item><el-button :icon="Refresh" :loading="loading" @click="loadContext">查询</el-button></el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="paymentMethod" style="width: 190px">
            <el-option label="现金" value="CASH" /><el-option label="银行转账（模拟）" value="BANK_TRANSFER" /><el-option label="扫码支付（模拟）" value="QR_SIMULATOR" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="cashRequiresShift" type="error" :closable="false" title="现金收款不可用：请先在上方开启收银班次。" />
      <el-table v-loading="loading" :data="bills" height="430" @selection-change="handleSelection">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="bill_no" label="账单号" min-width="220" />
        <el-table-column prop="billing_period" label="账期" width="90" />
        <el-table-column prop="total_amount" label="应收" width="100" />
        <el-table-column prop="paid_amount" label="已收" width="100" />
        <el-table-column prop="outstanding_amount" label="待收" width="100" />
        <el-table-column label="本次收款" width="150"><template #default="scope"><el-input-number v-model="amounts[scope.row.id]" :min="0.01" :max="Number(scope.row.outstanding_amount)" :precision="2" :controls="false" size="small" style="width: 125px" /></template></el-table-column>
        <el-table-column prop="due_date" label="到期日" width="112" />
        <el-table-column prop="status" label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.status === 'PARTIAL' ? 'warning' : 'danger'" effect="plain">{{ scope.row.status }}</el-tag></template></el-table-column>
      </el-table>
      <div class="workflow-actions">
        <el-button type="primary" :icon="CreditCard" :loading="paying" :disabled="!selected.length || cashRequiresShift" @click="collect">确认收款</el-button>
      </div>
    </el-card>
    <el-card v-if="lastPayment" class="workflow-card" shadow="never">
      <el-descriptions title="最近一次收款结果" :column="3" border>
        <el-descriptions-item label="订单号">{{ lastPayment.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="金额">¥ {{ lastPayment.amount }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag type="success">{{ lastPayment.status }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="渠道">{{ channelLabel(lastPayment.paymentChannel) }}</el-descriptions-item>
        <el-descriptions-item label="交易 ID">{{ lastPayment.transactionId }}</el-descriptions-item>
        <el-descriptions-item label="收据 ID">{{ lastPayment.receiptId }}</el-descriptions-item>
      </el-descriptions>
      <div class="workflow-actions"><el-button type="danger" plain @click="reverseLastPayment">冲正</el-button><el-button type="primary" plain @click="issueInvoice">模拟开票</el-button><el-tag v-if="invoice" type="success">{{ invoice.status }}</el-tag></div>
    </el-card>
  </div>
</template>

<style scoped>
.cashier-page { display: flex; flex-direction: column; gap: 12px; }
.shift-grid, .shift-open { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; }
.shift-grid .el-statistic { min-width: 120px; }
.field-hint { color: #7e899b; font-size: 12px; margin-left: -16px; }
</style>
