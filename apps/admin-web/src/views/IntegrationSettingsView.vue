<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Lock, Refresh, RefreshRight, SetUp, Warning } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

interface AdapterPolicy {
  adapterCode: string
  providerType: string
  providerName: string
  mode: 'SIMULATOR' | 'DISABLED'
  enabled: boolean
  productionReady: boolean
  endpointMasked?: string
  credentialStatus: string
  signingRequired: boolean
  timeoutMs: number
  maxAttempts: number
  retryBaseSeconds: number
  lastCheckedAt?: string
}

interface Workbench {
  communityId?: string
  adapters: AdapterPolicy[]
  outboxSummary: Array<{ status: string, itemCount: number }>
  callbacks: Record<string, any>[]
  attempts: Record<string, any>[]
  deadLetters: Record<string, any>[]
  security: Record<string, any>
  observability: Record<string, any>
}

const emptyWorkbench = (): Workbench => ({
  adapters: [], outboxSummary: [], callbacks: [], attempts: [], deadLetters: [], security: {}, observability: {},
})

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const canWrite = computed(() => auth.hasPermission('integration:write'))
const canDrill = computed(() => canWrite.value && Boolean(auth.user?.roles.includes('PLATFORM_ADMIN')))
const loading = ref(false)
const acting = ref('')
const providerType = ref('')
const status = ref('')
const activeEvidence = ref('callbacks')
const workbench = ref<Workbench>(emptyWorkbench())
const lastResult = ref<Record<string, any> | null>(null)

const adapters = computed(() => workbench.value.adapters.filter((item) => {
  if (providerType.value && item.providerType !== providerType.value) return false
  if (status.value === 'ENABLED' && !item.enabled) return false
  if (status.value === 'DISABLED' && item.enabled) return false
  return true
}))
const providerTypes = computed(() => [...new Set(workbench.value.adapters.map((item) => item.providerType))])
const openDeadLetters = computed(() => workbench.value.deadLetters.filter((item) => item.status === 'OPEN'))
const outboxTotal = computed(() => workbench.value.outboxSummary.reduce((sum, item) => sum + Number(item.itemCount || 0), 0))
const retrying = computed(() => workbench.value.outboxSummary
  .filter((item) => ['PENDING', 'RETRY', 'DEAD_LETTER'].includes(item.status))
  .reduce((sum, item) => sum + Number(item.itemCount || 0), 0))

function statusType(value: string) {
  if (['SUCCEEDED', 'PROCESSED', 'PUBLISHED', 'RESOLVED', 'UP'].includes(value)) return 'success'
  if (['RETRY', 'REPLAYED', 'DISABLED'].includes(value)) return 'warning'
  if (['OPEN', 'DEAD_LETTER', 'RETRYABLE_FAILURE', 'FAILED'].includes(value)) return 'danger'
  return 'info'
}

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '尚未检测'
}

async function loadWorkbench() {
  if (!communityId.value) return
  loading.value = true
  try {
    const { data } = await http.get('/integrations/workbench', { params: { communityId: communityId.value } })
    workbench.value = data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '第三方集成治理数据加载失败')
  } finally {
    loading.value = false
  }
}

async function runAction(key: string, action: () => Promise<any>, success: string) {
  acting.value = key
  try {
    lastResult.value = (await action()).data
    ElMessage.success(success)
    await loadWorkbench()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '集成治理操作失败')
  } finally {
    acting.value = ''
  }
}

function testAdapter(row: AdapterPolicy) {
  return runAction(`test-${row.adapterCode}`, () => http.post(
    `/integrations/adapters/${row.adapterCode}:test`, null, { params: { communityId: communityId.value } },
  ), `${row.providerName}本地契约检测完成`)
}

async function testAllAdapters() {
  acting.value = 'test-all'
  try {
    const results = []
    for (const adapter of workbench.value.adapters) {
      results.push((await http.post(`/integrations/adapters/${adapter.adapterCode}:test`, null, {
        params: { communityId: communityId.value },
      })).data)
    }
    lastResult.value = { adapterCode: 'ALL_FAIL_CLOSED_ADAPTERS', outcome: 'SUCCEEDED', count: results.length }
    ElMessage.success(`${results.length} 个模拟/禁用适配器的本地契约检测全部完成`)
    await loadWorkbench()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '适配器批量检测失败')
  } finally {
    acting.value = ''
  }
}

async function drainOutbox() {
  await ElMessageBox.confirm('仅会通过本地支付模拟器投递当前到期事件，不会访问任何真实外部通道。', '确认模拟投递', { type: 'warning' })
  return runAction('drain', () => http.post('/integrations/outbox-events:drain-simulated', {}, {
    params: { communityId: communityId.value },
  }), '到期事件模拟投递完成')
}

async function replayDeadLetter(row: Record<string, any>) {
  await ElMessageBox.confirm(`将死信 ${row.id} 放回本地模拟队列，原始证据和重试记录会继续保留。`, '确认回放死信', { type: 'warning' })
  return runAction(`replay-${row.id}`, () => http.post(`/integrations/dead-letters/${row.id}:replay`, {}), '死信已进入模拟回放队列')
}

async function runRecoveryDrill() {
  await ElMessageBox.confirm('演练会创建一条明确标记为 SIMULATED 的事件，制造三次可重试失败，转入死信后回放并成功投递。', '执行完整恢复演练', { type: 'warning', confirmButtonText: '开始演练' })
  acting.value = 'drill'
  try {
    const created = (await http.post('/integrations/outbox-events:simulate', {
      communityId: communityId.value, eventType: 'UI_RECOVERY_DRILL', payload: { source: 'integration-settings' },
    })).data
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await http.post(`/integrations/outbox-events/${created.id}:simulate-delivery`, {
        adapterCode: 'PAYMENT_SIMULATOR', outcome: 'RETRYABLE_FAILURE',
      })
    }
    await loadWorkbench()
    const deadLetter = workbench.value.deadLetters.find((item) => item.referenceId === created.id && item.status === 'OPEN')
    if (!deadLetter) throw new Error('演练事件未进入死信队列')
    await http.post(`/integrations/dead-letters/${deadLetter.id}:replay`, {})
    lastResult.value = (await http.post(`/integrations/outbox-events/${created.id}:simulate-delivery`, {
      adapterCode: 'PAYMENT_SIMULATOR', outcome: 'SUCCEEDED',
    })).data
    ElMessage.success('模拟故障、死信、回放与恢复已形成完整证据链')
    await loadWorkbench()
    activeEvidence.value = 'attempts'
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || error.message || '恢复演练失败')
  } finally {
    acting.value = ''
  }
}

watch(communityId, loadWorkbench)
onMounted(loadWorkbench)
</script>

<template>
  <div v-loading="loading" class="integration-page">
    <div class="integration-hero">
      <div>
        <span class="section-kicker">INTEGRATION GOVERNANCE</span>
        <h2>第三方集成治理台</h2>
        <p>支付、发票、银行、IoT 与 Java110 的模式、签名、重试、死信和审计证据集中在一个受控边界内。</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" @click="loadWorkbench">刷新证据</el-button>
        <el-button v-if="canWrite" :icon="Connection" :loading="acting === 'test-all'" @click="testAllAdapters">检测全部</el-button>
        <el-button v-if="canDrill" :icon="SetUp" :loading="acting === 'drill'" @click="runRecoveryDrill">模拟恢复演练</el-button>
        <el-button v-if="canDrill" type="primary" :icon="Connection" :loading="acting === 'drain'" @click="drainOutbox">投递到期事件</el-button>
      </div>
    </div>

    <el-alert type="warning" show-icon :closable="false">
      <template #title><strong>当前没有任何生产通道</strong></template>
      支付、发票、银行和 IoT 均为本地模拟适配器，Java110 已禁用；页面不读取、不回显密钥，也不会把契约测试描述为真实连接。
    </el-alert>

    <div class="summary-grid">
      <div><strong>{{ workbench.adapters.length }}</strong><span>登记适配器</span></div>
      <div><strong>{{ workbench.adapters.filter((item) => item.mode === 'SIMULATOR').length }}</strong><span>本地模拟</span></div>
      <div><strong>{{ outboxTotal }}</strong><span>Outbox 证据</span></div>
      <div :class="{ danger: retrying }"><strong>{{ retrying }}</strong><span>待处理/重试/死信</span></div>
      <div :class="{ danger: openDeadLetters.length }"><strong>{{ openDeadLetters.length }}</strong><span>开放死信</span></div>
    </div>

    <el-card class="governance-card" shadow="never">
      <template #header>
        <div class="card-heading">
          <div><span class="section-kicker">FAIL-CLOSED ADAPTERS</span><h3>适配器与功能开关</h3></div>
          <div class="filters">
            <el-select v-model="providerType" clearable placeholder="通道类型" style="width: 150px">
              <el-option v-for="item in providerTypes" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-model="status" clearable placeholder="启用状态" style="width: 130px">
              <el-option label="已启用" value="ENABLED" /><el-option label="已禁用" value="DISABLED" />
            </el-select>
          </div>
        </div>
      </template>
      <el-table :data="adapters" stripe empty-text="没有符合条件的适配器">
        <el-table-column prop="providerType" label="通道类型" width="110" />
        <el-table-column prop="providerName" label="通道名称" min-width="170" />
        <el-table-column prop="mode" label="运行模式" width="125">
          <template #default="{ row }"><el-tag :type="row.mode === 'DISABLED' ? 'warning' : 'info'">{{ row.mode }}</el-tag></template>
        </el-table-column>
        <el-table-column label="生产就绪" width="110"><template #default><el-tag type="danger">否</el-tag></template></el-table-column>
        <el-table-column prop="endpointMasked" label="服务边界" min-width="170" show-overflow-tooltip />
        <el-table-column label="凭据" width="120">
          <template #default="{ row }"><span class="locked"><el-icon><Lock /></el-icon>{{ row.credentialStatus }}</span></template>
        </el-table-column>
        <el-table-column label="保护策略" min-width="180">
          <template #default="{ row }">{{ row.timeoutMs }}ms / {{ row.maxAttempts }} 次 / {{ row.signingRequired ? '需签名' : '禁用态' }}</template>
        </el-table-column>
        <el-table-column label="末次检测" width="165"><template #default="{ row }">{{ formatTime(row.lastCheckedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="105" fixed="right">
          <template #default="{ row }"><el-button link type="primary" :disabled="!canWrite" :loading="acting === `test-${row.adapterCode}`" @click="testAdapter(row)">本地检测</el-button></template>
        </el-table-column>
      </el-table>
      <div class="configuration-note"><el-icon><Lock /></el-icon><span>运行模式和密钥只允许通过部署环境变量/密钥管理器变更；密钥仅写入运行时，管理端永不回显。</span></div>
    </el-card>

    <div class="control-grid">
      <el-card shadow="never">
        <template #header><div class="mini-heading"><el-icon><Lock /></el-icon><strong>回调安全</strong></div></template>
        <dl><div><dt>HMAC 签名</dt><dd>{{ workbench.security.signedCallbacks ? '强制校验' : '未启用' }}</dd></div><div><dt>时间偏差</dt><dd>± {{ workbench.security.maxSkewSeconds || 0 }} 秒</dd></div><div><dt>密钥状态</dt><dd>{{ workbench.security.secretConfigured ? '运行时已配置' : '未配置' }}</dd></div><div><dt>密钥可读</dt><dd class="safe">否</dd></div></dl>
      </el-card>
      <el-card shadow="never">
        <template #header><div class="mini-heading"><el-icon><RefreshRight /></el-icon><strong>可观测与探针</strong></div></template>
        <dl><div><dt>健康探针</dt><dd>{{ workbench.observability.health || '—' }}</dd></div><div><dt>就绪探针</dt><dd>{{ workbench.observability.readiness || '—' }}</dd></div><div><dt>指标端点</dt><dd>{{ workbench.observability.metrics || '—' }}</dd></div><div><dt>追踪头</dt><dd>{{ workbench.observability.requestTraceHeader || '—' }}</dd></div></dl>
      </el-card>
      <el-card shadow="never">
        <template #header><div class="mini-heading"><el-icon><Warning /></el-icon><strong>Outbox 状态</strong></div></template>
        <div class="status-cloud"><el-tag v-for="item in workbench.outboxSummary" :key="item.status" :type="statusType(item.status)">{{ item.status }} · {{ item.itemCount }}</el-tag><span v-if="!workbench.outboxSummary.length">暂无事件</span></div>
      </el-card>
    </div>

    <el-card class="governance-card" shadow="never">
      <template #header><div class="card-heading"><div><span class="section-kicker">DELIVERY EVIDENCE</span><h3>入站、投递与死信证据</h3></div><el-tag type="info">仅展示校验值和脱敏元数据</el-tag></div></template>
      <el-tabs v-model="activeEvidence">
        <el-tab-pane label="回调 Inbox" name="callbacks">
          <el-table :data="workbench.callbacks" height="300" empty-text="暂无已签名回调">
            <el-table-column prop="adapterCode" label="适配器" width="180" /><el-table-column prop="callbackId" label="回调编号" min-width="190" show-overflow-tooltip /><el-table-column prop="requestId" label="请求追踪" min-width="180" show-overflow-tooltip /><el-table-column prop="payloadChecksum" label="负载校验值" min-width="210" show-overflow-tooltip /><el-table-column prop="replayCount" label="重复次数" width="95" /><el-table-column prop="status" label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column><el-table-column label="接收时间" width="165"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="投递尝试" name="attempts">
          <el-table :data="workbench.attempts" height="300" empty-text="暂无投递记录">
            <el-table-column prop="direction" label="方向" width="125" /><el-table-column prop="adapterCode" label="适配器" width="180" /><el-table-column prop="referenceId" label="关联编号" min-width="190" show-overflow-tooltip /><el-table-column prop="attemptNo" label="次数" width="75" /><el-table-column prop="outcome" label="结果" width="155"><template #default="{ row }"><el-tag :type="statusType(row.outcome)">{{ row.outcome }}</el-tag></template></el-table-column><el-table-column prop="httpStatus" label="HTTP" width="80" /><el-table-column prop="durationMs" label="耗时(ms)" width="95" /><el-table-column label="发生时间" width="165"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`死信队列 (${openDeadLetters.length})`" name="deadLetters">
          <el-table :data="workbench.deadLetters" height="300" empty-text="暂无死信">
            <el-table-column prop="adapterCode" label="适配器" width="180" /><el-table-column prop="referenceId" label="关联编号" min-width="190" show-overflow-tooltip /><el-table-column prop="payloadChecksum" label="负载校验值" min-width="210" show-overflow-tooltip /><el-table-column prop="reason" label="原因" min-width="180" /><el-table-column prop="retryCount" label="重试" width="75" /><el-table-column prop="status" label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column><el-table-column label="操作" width="95" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'OPEN'" link type="danger" :disabled="!canDrill" :loading="acting === `replay-${row.id}`" @click="replayDeadLetter(row)">模拟回放</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <div v-if="lastResult" class="result-strip"><strong>最近操作证据</strong><span>{{ lastResult.adapterCode || lastResult.eventType || lastResult.id }}</span><el-tag :type="statusType(lastResult.outcome || lastResult.status)">{{ lastResult.outcome || lastResult.status }}</el-tag><span v-if="lastResult.durationMs !== undefined">{{ lastResult.durationMs }} ms</span></div>
  </div>
</template>

<style scoped>
.integration-page { display: grid; gap: 16px; color: #253148; }
.integration-hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; padding: 25px 28px; border: 1px solid #d9e1ec; border-radius: 12px; background: linear-gradient(115deg, #fff 0%, #f5f8fc 64%, #edf3fb 100%); box-shadow: 0 8px 26px rgb(39 58 86 / 7%); }
.integration-hero h2, .card-heading h3 { margin: 4px 0 6px; color: #1f2b40; }
.integration-hero p { margin: 0; color: #66748a; line-height: 1.65; }
.hero-actions, .filters, .card-heading, .mini-heading { display: flex; align-items: center; gap: 10px; }
.hero-actions { flex-wrap: wrap; justify-content: flex-end; }
.section-kicker { color: #3b70b5; font-size: 11px; font-weight: 700; letter-spacing: 1.5px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.summary-grid > div { display: flex; min-height: 75px; padding: 14px 18px; flex-direction: column; justify-content: center; border: 1px solid #e0e6ef; border-radius: 10px; background: #fff; }
.summary-grid strong { color: #264e84; font-size: 25px; font-variant-numeric: tabular-nums; }
.summary-grid span { margin-top: 4px; color: #7a8799; font-size: 12px; }
.summary-grid .danger strong { color: #bd4c45; }
.governance-card { border-color: #dce3ed; }
.card-heading { justify-content: space-between; }
.card-heading h3 { font-size: 17px; }
.locked, .configuration-note { display: flex; align-items: center; gap: 6px; }
.locked { color: #59677b; }
.configuration-note { margin-top: 12px; padding: 10px 13px; border-radius: 6px; color: #68758a; background: #f4f7fb; font-size: 12px; }
.control-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.mini-heading { color: #2c4e78; }
dl { display: grid; gap: 9px; margin: 0; }
dl > div { display: flex; justify-content: space-between; gap: 15px; padding-bottom: 8px; border-bottom: 1px dashed #e3e7ed; }
dt { color: #7a8798; } dd { margin: 0; color: #34445d; font-weight: 500; word-break: break-all; text-align: right; } dd.safe { color: #2e8b63; }
.status-cloud { display: flex; flex-wrap: wrap; gap: 8px; }
.result-strip { display: flex; align-items: center; gap: 14px; padding: 11px 16px; border: 1px solid #d9e6dc; border-radius: 8px; background: #f4faf5; color: #56675b; font-size: 13px; }
@media (max-width: 1100px) { .summary-grid { grid-template-columns: repeat(3, 1fr); } .control-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .integration-hero { flex-direction: column; } .summary-grid { grid-template-columns: repeat(2, 1fr); } .card-heading { align-items: flex-start; flex-direction: column; } }
</style>
