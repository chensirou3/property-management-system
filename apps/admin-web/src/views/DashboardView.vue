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

const stats = computed(() => [
  { label: '房屋资产', value: data.value.counts.rooms || '0', suffix: '套', icon: House, tone: 'blue', change: '服务端实时统计' },
  { label: '客户档案', value: data.value.counts.customers || '0', suffix: '位', icon: User, tone: 'green', change: '合成隐私数据' },
  { label: '车位资产', value: data.value.counts.parking_spaces || '0', suffix: '个', icon: House, tone: 'amber', change: `${data.value.counts.allocations || 0} 条费用分配` },
  { label: '费用定义', value: data.value.counts.fee_definitions || '0', suffix: '项', icon: Money, tone: 'violet', change: `${data.value.counts.fee_standards || 0} 个计费标准` },
])
const quality = computed(() => [
  { label: '房屋与项目数量对账', value: Number(data.value.counts.rooms) === 359 ? 100 : 0, status: Number(data.value.counts.rooms) === 359 ? '已通过' : '需复核' },
  { label: '客户关系无孤儿键', value: data.value.quality.orphan_customer_relations === 0 ? 100 : 0, status: data.value.quality.orphan_customer_relations === 0 ? '已通过' : '存在异常' },
  { label: '费用分配无孤儿键', value: data.value.quality.orphan_allocations === 0 ? 100 : 0, status: data.value.quality.orphan_allocations === 0 ? '已通过' : '存在异常' },
  { label: '仪表扩展样本', value: Math.min(100, Number(data.value.counts.meters || 0) / 31 * 100), status: `${data.value.counts.meters || 0} 条` },
])
const meterNote = computed(() => `${activePeriod.value}使用模拟仪表与合成读数，不代表真实设备状态`)

async function load() {
  if (!auth.currentProjectId) return
  loading.value = true
  error.value = ''
  try {
    const response = await http.get('/dashboard', { params: { communityId: auth.currentProjectId } })
    data.value = response.data
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '看板加载失败'
  } finally {
    loading.value = false
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
        <h2>你好，{{ auth.currentProject?.name || '合成项目' }}的数据已经准备就绪</h2>
        <p>当前为合成试点项目。核心资产规模按调查统计生成，个人信息均为新造测试值。</p>
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
          <div><span>应收金额</span><strong>¥ {{ data.finance.receivable || '0.00' }}</strong><small>合成账期</small></div>
          <div><span>实收金额</span><strong>¥ {{ data.finance.received || '0.00' }}</strong><small>本地流水</small></div>
          <div><span>待收金额</span><strong>¥ {{ data.finance.outstanding || '0.00' }}</strong><small>余额守恒</small></div>
          <div><span>收缴率</span><strong>{{ data.finance.collection_rate || '0.00' }}%</strong><small>演示口径</small></div>
        </div>
        <div class="chart-placeholder">
          <div v-for="height in [42, 65, 54, 82, 73, 92, 68, 78, 88, 64, 74, 86]" :key="height" class="bar" :style="{ height: `${height}%` }"></div>
        </div>
        <p class="simulation-caption"><el-icon><Stopwatch /></el-icon>{{ meterNote }}</p>
      </article>

      <article class="panel-card quality-card">
        <header class="panel-header"><div><span class="section-kicker">DATA QUALITY</span><h3>基础数据质量</h3></div></header>
        <div class="quality-list">
          <div v-for="item in quality" :key="item.label" class="quality-row">
            <div class="quality-label"><span>{{ item.label }}</span><strong>{{ item.status }}</strong></div>
            <el-progress :percentage="item.value" :stroke-width="7" :show-text="false" :color="item.value < 50 ? '#d29a38' : '#2f7b65'" />
          </div>
        </div>
      </article>
    </section>
  </div>
</template>
