<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
const loading = ref(false)
const paying = ref(false)
const paymentMethod = ref('CASH_SIMULATOR')
const lastPayment = ref<any>(null)
const invoice = ref<any>(null)
const selectedTotal = computed(() => selected.value.reduce((sum, item) => sum + Number(item.outstanding_amount), 0).toFixed(2))

async function loadContext() {
  if (!communityId.value) return
  loading.value = true
  try {
    const { data } = await http.get('/cashier/context', { params: { communityId: communityId.value, keyword: keyword.value || undefined } })
    bills.value = data.bills
    selected.value = []
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '收银上下文加载失败')
  } finally {
    loading.value = false
  }
}

async function collect() {
  if (!selected.value.length) return ElMessage.warning('请选择至少一张待收账单')
  await ElMessageBox.confirm(`确认通过本地模拟通道收取 ¥ ${selectedTotal.value}？`, '收款确认', {
    confirmButtonText: '确认收款', cancelButtonText: '取消', type: 'warning',
  })
  paying.value = true
  try {
    const { data: order } = await http.post('/payment-orders', {
      communityId: communityId.value,
      paymentMethod: paymentMethod.value,
      bills: selected.value.map((bill) => ({ billId: bill.id, amount: bill.outstanding_amount })),
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    const { data } = await http.post(`/payment-orders/${order.orderId}:confirm-simulated`, null, { params: { communityId: communityId.value } })
    lastPayment.value = data
    invoice.value = null
    ElMessage.success('模拟收款成功，账单和收据已同步更新')
    await loadContext()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '收款失败')
  } finally {
    paying.value = false
  }
}

async function issueInvoice() {
  if (!lastPayment.value?.receiptId) return
  try {
    const { data } = await http.post('/invoices:simulate', {
      communityId: communityId.value, receiptId: lastPayment.value.receiptId, title: '本地演示客户',
    })
    invoice.value = data
    ElMessage.success('模拟开票完成')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '模拟开票失败')
  }
}

watch(communityId, loadContext)
onMounted(loadContext)
</script>

<template>
  <div class="workflow-page">
    <el-alert title="当前仅启用本地支付与开票模拟器，不会连接真实资金或税务通道。" type="warning" show-icon :closable="false" />
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
          <el-select v-model="paymentMethod" style="width: 180px"><el-option label="现金（模拟）" value="CASH_SIMULATOR" /><el-option label="扫码（模拟）" value="QR_SIMULATOR" /></el-select>
        </el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="bills" height="430" @selection-change="selected = $event">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="bill_no" label="账单号" min-width="250" />
        <el-table-column prop="billing_period" label="账期" width="100" />
        <el-table-column prop="total_amount" label="应收" width="110" />
        <el-table-column prop="paid_amount" label="已收" width="110" />
        <el-table-column prop="outstanding_amount" label="待收" width="110" />
        <el-table-column prop="due_date" label="到期日" width="120" />
        <el-table-column prop="status" label="状态" width="105"><template #default="scope"><el-tag :type="scope.row.status === 'PARTIAL' ? 'warning' : 'danger'" effect="plain">{{ scope.row.status }}</el-tag></template></el-table-column>
      </el-table>
      <div class="workflow-actions">
        <el-button type="primary" :icon="CreditCard" :loading="paying" :disabled="!selected.length" @click="collect">模拟确认收款</el-button>
      </div>
    </el-card>
    <el-card v-if="lastPayment" class="workflow-card" shadow="never">
      <el-descriptions title="最近一次收款结果" :column="3" border>
        <el-descriptions-item label="订单号">{{ lastPayment.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="金额">¥ {{ lastPayment.amount }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag type="success">{{ lastPayment.status }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="交易 ID">{{ lastPayment.transactionId }}</el-descriptions-item>
        <el-descriptions-item label="收据 ID">{{ lastPayment.receiptId }}</el-descriptions-item>
        <el-descriptions-item label="发票"><el-button link type="primary" @click="issueInvoice">模拟开票</el-button><span v-if="invoice">{{ invoice.status }}</span></el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>
