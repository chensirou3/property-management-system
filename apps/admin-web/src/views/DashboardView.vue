<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, House, Money, Stopwatch, User } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const activePeriod = ref('本月')
const loading = ref(false)
const error = ref('')
const data = ref<any>({ counts: {}, finance: {}, quality: {}, adapters: {} })
let loadGeneration = 0

const stats = computed(() => [
  { label: '房屋资产', value: data.value.counts.rooms || '0', suffix: '套', icon: House, tone: 'blue', change: '服务端实时统计' },
  { label: '客户档案', value: data.value.counts.customers || '0', suffix: '位', icon: User, tone: 'green', change: '服务端实时统计' },
  { label: '车位资产', value: data.value.counts.parking_spaces || '0', suffix: '个', icon: House, tone: 'amber', change: '服务端实时统计' },
  { label: '费用定义', value: data.value.counts.fee_definitions || '0', suffix: '项', icon: Money, tone: 'violet',
    change: `${data.value.counts.fee_standards || 0} 个计费标准 · ${data.value.counts.asset_allocations || 0} 条资产分配 · ${data.value.counts.meter_allocations || 0} 条仪表分配` },
])

function violationCheck(label: string, rawValue: unknown) {
  if (rawValue === null || rawValue === undefined || rawValue === '') return { label, value: 0, status: '待加载' }
  const violations = Number(rawValue)
  if (!Number.isFinite(violations)) return { label, value: 0, status: '待加载' }
  return {
    label,
    value: violations === 0 ? 100 : 0,
    status: violations === 0 ? '已通过' : `存在异常（${violations} 项）`,
  }
}

const quality = computed(() => [
  violationCheck('房屋主档与明细一致性', data.value.quality.room_detail_mismatches),
  violationCheck('客户关系无孤儿键', data.value.quality.orphan_customer_relations),
  violationCheck('费用分配无孤儿键', data.value.quality.orphan_allocations),
  { label: '仪表主档数量', value: null, status: `${data.value.counts.meters || 0} 条（实时统计）` },
])
const meterNote = computed(() => `${activePeriod.value}计量数据以已审核记录为准；未接入生产 IoT 前不会自动生成读数`)
const hasFinanceData = computed(() => ['receivable', 'received', 'outstanding']
  .some((key) => Number(data.value.finance?.[key] || 0) > 0))
const collectionRate = computed(() => Math.max(0, Math.min(100, Number(data.value.finance?.collection_rate || 0))))

function formatAmount(value: unknown) {
  const amount = Number(value ?? 0)
  return Number.isFinite(amount) ? amount.toFixed(2) : '0.00'
}

async function load() {
  const generation = ++loadGeneration
  const requestedProjectId = auth.currentProjectId
  if (!requestedProjectId) {
    data.value = { counts: {}, finance: {}, quality: {}, adapters: {} }
    error.value = ''
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const response = await http.get('/dashboard', { params: { communityId: requestedProjectId } })
    if (generation !== loadGeneration || auth.currentProjectId !== requestedProjectId) return
    data.value = response.data
  } catch (reason: any) {
    if (generation !== loadGeneration || auth.currentProjectId !== requestedProjectId) return
    error.value = reason.response?.data?.message || '看板加载失败'
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

watch(() => auth.currentProjectId, load, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="dashboard-page">
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <section class="welcome-card">
      <div>
        <span class="section-kicker">项目运营概览</span>
        <h2>{{ auth.currentProject?.name || '当前项目' }}运营数据概览</h2>
        <p>当前项目使用独立数据库；请通过档案维护或数据迁移导入正式业务数据。</p>
      </div>
      <el-button type="primary" @click="load">刷新数据质量 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
    </section>

    <section class="stat-grid">
      <article v-for="item in stats" :key="item.label" class="stat-card">
        <div class="stat-icon" :class="item.tone"><el-icon><component :is="item.icon" /></el-icon></div>
        <div class="stat-copy">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}<small>{{ item.suffix }}</small></strong>
          <p>{{ item.change }}</p>
        </div>
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="panel-card financial-overview">
        <header class="panel-header">
          <div><span class="section-kicker">收费概览</span><h3>账务闭环状态</h3></div>
          <el-radio-group v-model="activePeriod" size="small">
            <el-radio-button value="本月">本月</el-radio-button><el-radio-button value="本年">本年</el-radio-button>
          </el-radio-group>
        </header>
        <div class="finance-strip">
          <div><span>应收金额</span><strong>¥ {{ formatAmount(data.finance.receivable) }}</strong><small>当前统计周期</small></div>
          <div><span>实收金额</span><strong>¥ {{ formatAmount(data.finance.received) }}</strong><small>本地流水</small></div>
          <div><span>待收金额</span><strong>¥ {{ formatAmount(data.finance.outstanding) }}</strong><small>余额守恒</small></div>
          <div><span>收缴率</span><strong>{{ formatAmount(data.finance.collection_rate) }}%</strong><small>实时计算</small></div>
        </div>
        <div class="chart-placeholder">
          <el-progress v-if="hasFinanceData" type="dashboard" :percentage="collectionRate" :width="150" color="#2f7b65" />
          <el-empty v-else description="暂无收费数据" :image-size="58" />
        </div>
        <p class="simulation-caption"><el-icon><Stopwatch /></el-icon>{{ meterNote }}</p>
      </article>

      <article class="panel-card quality-card">
        <header class="panel-header"><div><span class="section-kicker">DATA QUALITY</span><h3>基础数据质量</h3></div></header>
        <div class="quality-list">
          <div v-for="item in quality" :key="item.label" class="quality-row">
            <div class="quality-label"><span>{{ item.label }}</span><strong>{{ item.status }}</strong></div>
            <el-progress v-if="item.value !== null" :percentage="item.value" :stroke-width="7" :show-text="false" :color="item.value < 50 ? '#d29a38' : '#2f7b65'" />
          </div>
        </div>
      </article>
    </section>
  </div>
</template>
