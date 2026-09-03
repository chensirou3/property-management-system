<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Download, Refresh, Upload } from '@element-plus/icons-vue'
import { http } from '../api/http'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

interface SourceRow { resourceType: 'PROJECT' | 'BUILDING' | 'ASSET' | 'CUSTOMER' | 'RELATION'; sourceId: string; data: Record<string, unknown> }

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const canImport = computed(() => auth.hasPermission('migration:import'))
const canWrite = computed(() => auth.hasPermission('migration:write'))
const canExport = computed(() => auth.hasPermission('migration:export'))
const keyword = ref('')
const statusFilter = ref('')
const page = ref(1)
const pageSize = ref(20)
const rows = ref<Record<string, any>[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<any>(null)
const createVisible = ref(false)
const createSubmitting = ref(false)
const createRows = ref<SourceRow[]>([])
const rollbackVisible = ref(false)
const rollbackSubmitting = ref(false)
const rollbackForm = reactive({ token: '', reason: '' })
const createForm = reactive({ sourceName: '', mappingVersion: 'property-v1' })

const columns: DataGridColumn[] = [
  { prop: 'batchNo', label: '批次号', minWidth: 205 },
  { prop: 'sourceName', label: '来源文件', minWidth: 220 },
  { prop: 'mappingVersion', label: '映射版本', width: 125 },
  { prop: 'totalCount', label: 'Raw', width: 82 },
  { prop: 'canonicalCount', label: '合格', width: 82 },
  { prop: 'quarantineCount', label: '隔离', width: 82 },
  { prop: 'importedCount', label: '写入', width: 82 },
  { prop: 'status', label: '批次状态', width: 112, type: 'status' },
  { prop: 'reviewStatus', label: '审批状态', width: 105, type: 'status' },
  { prop: 'createdAt', label: '创建时间', minWidth: 175 },
]

const batch = computed(() => detail.value?.batch || null)
const stageCards = computed(() => batch.value ? [
  { key: 'Raw', value: batch.value.totalCount, note: '原始证据只读保留', state: 'evidence' },
  { key: 'Quarantine', value: batch.value.quarantineCount, note: '错误行隔离', state: batch.value.quarantineCount ? 'warning' : 'ok' },
  { key: 'Canonical', value: batch.value.canonicalCount, note: `映射 ${batch.value.mappingVersion}`, state: 'ok' },
  { key: 'Staging', value: batch.value.stagedCount, note: '业务规则已校验', state: 'ok' },
  { key: 'Production', value: batch.value.importedCount, note: `跳过映射 ${batch.value.skippedCount}`, state: batch.value.importedCount ? 'ok' : 'pending' },
] : [])

const resourceCounts = computed(() => createRows.value.reduce<Record<string, number>>((result, row) => {
  result[row.resourceType] = (result[row.resourceType] || 0) + 1
  return result
}, {}))

async function load() {
  if (!communityId.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await http.get('/migrations/batches', { params: {
      communityId: communityId.value, status: statusFilter.value || undefined,
      keyword: keyword.value || undefined, page: page.value, size: pageSize.value,
    } })
    rows.value = data.items
    total.value = data.total
  } catch (error: any) {
    errorMessage.value = error.response?.data?.message || '迁移批次加载失败'
  } finally {
    loading.value = false
  }
}

async function openDetail(row: Record<string, any>) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await http.get(`/migrations/batches/${row.id}`, { params: { communityId: communityId.value } })
    detail.value = data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '迁移批次详情加载失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function handleFile(file: UploadFile) {
  if (!file.raw) return
  try {
    const parsed = JSON.parse(await file.raw.text())
    const imported = Array.isArray(parsed) ? parsed : parsed.rows
    if (!Array.isArray(imported) || !imported.length) throw new Error('rows 不能为空')
    createRows.value = imported
    createForm.sourceName = parsed.sourceName || file.name
    createForm.mappingVersion = parsed.mappingVersion || 'property-v1'
    createVisible.value = true
  } catch (error: any) {
    ElMessage.error(`迁移文件读取失败：${error.message || 'JSON 格式无效'}`)
  }
}

async function createBatch() {
  if (!createRows.value.length || !createForm.sourceName || !createForm.mappingVersion) return ElMessage.warning('请补全迁移来源与映射版本')
  createSubmitting.value = true
  try {
    const { data } = await http.post('/migrations/batches', {
      communityId: communityId.value, sourceName: createForm.sourceName,
      mappingVersion: createForm.mappingVersion, rows: createRows.value,
    })
    ElMessage.success(data.replayed ? '相同来源批次已存在，已安全返回原批次' : 'Raw 层已落库，尚未写入生产数据')
    createVisible.value = false
    await load()
    await openDetail(data.batch)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '迁移批次创建失败')
  } finally {
    createSubmitting.value = false
  }
}

async function refreshDetail() {
  if (!batch.value) return
  await Promise.all([openDetail(batch.value), load()])
}

async function validateBatch() {
  try {
    await http.post(`/migrations/batches/${batch.value.id}:validate`, {
      communityId: communityId.value, expectedVersion: batch.value.version,
    })
    ElMessage.success('五层校验完成，错误行未进入暂存层')
    await refreshDetail()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '批次校验失败') }
}

async function approveBatch() {
  const partial = batch.value.status === 'PARTIAL_FAILED'
  await ElMessageBox.confirm(
    partial ? `当前有 ${batch.value.errorCount} 条错误被隔离，将只审批 ${batch.value.stagedCount} 条合格记录。` : `确认审批 ${batch.value.stagedCount} 条暂存记录？`,
    '迁移审批', { type: partial ? 'warning' : 'info', confirmButtonText: '确认审批' })
  try {
    await http.post(`/migrations/batches/${batch.value.id}:approve`, {
      communityId: communityId.value, confirmPartial: partial,
      comment: partial ? '前端确认仅执行合格记录' : '校验通过', expectedVersion: batch.value.version,
    })
    ElMessage.success('批次已审批，生产层仍未写入')
    await refreshDetail()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '批次审批失败') }
}

async function executeBatch() {
  await ElMessageBox.confirm('执行会将已审批暂存记录写入生产表，并同步生成逐条反向变更日志。', '写入生产层', {
    type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '暂不执行',
  })
  try {
    const { data } = await http.post(`/migrations/batches/${batch.value.id}:execute`, {
      communityId: communityId.value, expectedVersion: batch.value.version,
    })
    ElMessage.success(data.replayed ? '该批次已执行，本次未产生重复数据' : `已写入 ${data.importedCount} 条，映射跳过 ${data.skippedCount} 条`)
    await refreshDetail()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '生产执行失败，事务已回滚') }
}

async function reconcileBatch() {
  try {
    await http.post(`/migrations/batches/${batch.value.id}:reconcile`, {
      communityId: communityId.value, expectedVersion: batch.value.version,
    })
    ElMessage.success('数量、关系、面积与完整性对账已完成')
    await refreshDetail()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '批次对账失败') }
}

function openRollback() {
  rollbackForm.token = ''
  rollbackForm.reason = ''
  rollbackVisible.value = true
}

async function rollbackBatch() {
  if (!rollbackForm.token || !rollbackForm.reason) return ElMessage.warning('请输入回滚凭证和原因')
  rollbackSubmitting.value = true
  try {
    await http.post(`/migrations/batches/${batch.value.id}:rollback`, {
      communityId: communityId.value, rollbackToken: rollbackForm.token,
      reason: rollbackForm.reason, expectedVersion: batch.value.version,
    })
    ElMessage.success('生产写入已逆序回滚，迁移证据仍完整保留')
    rollbackVisible.value = false
    await refreshDetail()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '回滚失败')
  } finally { rollbackSubmitting.value = false }
}

async function downloadTemplate() {
  try {
    const { data } = await http.get('/migrations/template', { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'migration-template.csv'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '模板下载失败') }
}

function reset() { keyword.value = ''; statusFilter.value = ''; page.value = 1; void load() }
function formatTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }

watch([communityId, page, pageSize], () => void load())
onMounted(() => void load())
</script>

<template>
  <section class="migration-page">
    <el-alert type="info" :closable="false" show-icon
      title="页面 17/49 · 真实迁移中心：Raw、Quarantine、Canonical、Staging、Production 五层留痕；审批前不会写入生产数据。" />

    <QueryPanel :loading="loading" @query="load" @reset="reset">
      <el-form-item label="批次号/来源"><el-input v-model="keyword" clearable placeholder="输入批次号或来源文件" style="width: 230px" /></el-form-item>
      <el-form-item label="批次状态">
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 170px">
          <el-option v-for="item in ['UPLOADED','READY','PARTIAL_FAILED','APPROVED','COMPLETED','RECONCILED','ROLLED_BACK','FAILED']"
            :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
    </QueryPanel>

    <DataGrid v-model:page="page" v-model:page-size="pageSize" :rows="rows" :columns="columns" :loading="loading"
      :error-message="errorMessage" :total="total" storage-key="migration-center" height="calc(100vh - 405px)" :selectable="false"
      @reload="load">
      <template #toolbar>
        <el-upload v-if="canImport" action="#" accept=".json,application/json" :auto-upload="false" :show-file-list="false" :on-change="handleFile">
          <el-button type="primary" :icon="Upload">上传 JSON</el-button>
        </el-upload>
        <el-button v-if="canExport" :icon="Download" @click="downloadTemplate">下载字段模板</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </template>
      <template #cell="{ column, row, value }">
        <StatusTag v-if="column.type === 'status'" :value="value" />
        <span v-else-if="column.prop === 'createdAt'">{{ formatTime(String(value || '')) }}</span>
        <span v-else>{{ value ?? '—' }}</span>
      </template>
      <template #operations="{ row }"><el-button link type="primary" @click="openDetail(row)">查看与执行</el-button></template>
    </DataGrid>

    <el-dialog v-model="createVisible" title="创建迁移批次" width="720px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon title="创建只写入不可变 Raw 层；请随后单独执行校验、审批与生产写入。" />
      <el-form label-width="100px" class="create-form">
        <el-form-item label="来源名称" required><el-input v-model="createForm.sourceName" /></el-form-item>
        <el-form-item label="映射版本" required><el-input v-model="createForm.mappingVersion" /></el-form-item>
        <el-form-item label="记录预览">
          <div class="resource-preview">
            <el-tag v-for="(count, resource) in resourceCounts" :key="resource" effect="plain">{{ resource }} {{ count }}</el-tag>
            <strong>共 {{ createRows.length }} 条</strong>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="createSubmitting" @click="createBatch">写入 Raw 层</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="78%" destroy-on-close>
      <template #header>
        <div v-if="batch" class="drawer-title"><div><strong>{{ batch.batchNo }}</strong><span>{{ batch.sourceName }}</span></div><StatusTag :value="batch.status" /></div>
      </template>
      <div v-if="batch" v-loading="detailLoading" class="detail-content">
        <div class="stage-grid">
          <article v-for="stage in stageCards" :key="stage.key" :class="['stage-card', `stage-card--${stage.state}`]">
            <span>{{ stage.key }}</span><strong>{{ stage.value }}</strong><small>{{ stage.note }}</small>
          </article>
        </div>
        <div class="command-bar">
          <el-button v-if="['UPLOADED','READY','PARTIAL_FAILED','FAILED'].includes(batch.status) && canImport" type="primary" @click="validateBatch">执行校验</el-button>
          <el-button v-if="['READY','PARTIAL_FAILED'].includes(batch.status) && canWrite" type="primary" @click="approveBatch">审批批次</el-button>
          <el-button v-if="batch.status === 'APPROVED' && canWrite" type="danger" @click="executeBatch">写入生产层</el-button>
          <el-button v-if="['COMPLETED','RECONCILED'].includes(batch.status) && canWrite" @click="reconcileBatch">执行对账</el-button>
          <el-button v-if="['COMPLETED','RECONCILED'].includes(batch.status) && canWrite" type="danger" plain @click="openRollback">逆序回滚</el-button>
          <el-button :icon="Refresh" @click="refreshDetail">刷新详情</el-button>
          <span class="version-note">版本 {{ batch.version }} · SHA-256 {{ batch.sourceSha256.slice(0, 12) }}…</span>
        </div>

        <el-tabs>
          <el-tab-pane :label="`错误隔离 (${detail.quarantine.length})`">
            <el-table :data="detail.quarantine" stripe max-height="340" empty-text="没有隔离错误">
              <el-table-column prop="rowNo" label="行号" width="75" /><el-table-column prop="resourceType" label="资源" width="105" />
              <el-table-column prop="sourceId" label="来源 ID" min-width="190" /><el-table-column prop="message" label="错误原因" min-width="300" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`对象映射 (${detail.mappings.length})`">
            <el-table :data="detail.mappings" stripe max-height="340">
              <el-table-column prop="resourceType" label="资源" width="110" /><el-table-column prop="sourceId" label="来源 ID" min-width="210" />
              <el-table-column prop="targetId" label="目标 UUID" min-width="260" /><el-table-column prop="targetCode" label="目标编码" min-width="150" />
              <el-table-column label="有效" width="80"><template #default="scope"><StatusTag :value="scope.row.active" /></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`对账结果 (${detail.reconciliation.length})`">
            <el-table :data="detail.reconciliation" stripe max-height="340">
              <el-table-column prop="metricName" label="指标" min-width="220" /><el-table-column prop="sourceValue" label="来源值" width="130" />
              <el-table-column prop="targetValue" label="目标值" width="130" /><el-table-column prop="differenceValue" label="差异" width="120" />
              <el-table-column label="状态" width="105"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`状态轨迹 (${detail.events.length})`">
            <el-timeline><el-timeline-item v-for="event in detail.events" :key="`${event.createdAt}-${event.eventType}`" :timestamp="formatTime(event.createdAt)">
              <strong>{{ event.eventType }}</strong><span class="event-transition">{{ event.fromStatus || '—' }} → {{ event.toStatus }}</span>
            </el-timeline-item></el-timeline>
          </el-tab-pane>
        </el-tabs>

        <el-alert v-if="detail.rollbackToken && ['COMPLETED','RECONCILED'].includes(batch.status)" type="warning" :closable="false" show-icon>
          <template #title>回滚凭证仅向具备 migration:write 权限的用户显示：<code>{{ detail.rollbackToken }}</code></template>
        </el-alert>
      </div>
    </el-drawer>

    <el-dialog v-model="rollbackVisible" title="逆序回滚生产写入" width="560px">
      <el-alert type="error" :closable="false" show-icon title="仅删除本批次新建的生产对象；Raw、错误、映射、对账与审计证据不会删除。" />
      <el-form label-width="95px" class="rollback-form">
        <el-form-item label="回滚凭证" required><el-input v-model="rollbackForm.token" placeholder="复制详情底部的完整凭证" /></el-form-item>
        <el-form-item label="回滚原因" required><el-input v-model="rollbackForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="rollbackVisible = false">取消</el-button><el-button type="danger" :loading="rollbackSubmitting" @click="rollbackBatch">确认回滚</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.migration-page { display: flex; flex-direction: column; gap: 12px; }
.create-form, .rollback-form { margin-top: 18px; }
.resource-preview { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.resource-preview strong { color: var(--forest-700); font-size: 13px; }
.drawer-title { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-title > div { display: flex; flex-direction: column; gap: 4px; }
.drawer-title strong { color: #27364f; font-size: 17px; }
.drawer-title span { color: var(--muted); font-size: 12px; }
.detail-content { min-height: 520px; display: flex; flex-direction: column; gap: 16px; }
.stage-grid { display: grid; grid-template-columns: repeat(5, minmax(130px, 1fr)); gap: 10px; }
.stage-card { min-height: 112px; padding: 14px; display: flex; flex-direction: column; gap: 7px; border: 1px solid #dce4ed; border-radius: 9px; background: #f8fafc; }
.stage-card span { color: #64748b; font-size: 12px; font-weight: 600; text-transform: uppercase; }
.stage-card strong { color: #24334a; font-size: 27px; line-height: 1; }
.stage-card small { color: #8792a3; }
.stage-card--ok { border-color: #bddfcf; background: #f1faf6; }
.stage-card--warning { border-color: #f0d39c; background: #fff8ec; }
.stage-card--evidence { border-color: #bfd4ec; background: #f2f7fc; }
.command-bar { min-height: 52px; padding: 9px 12px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.version-note { margin-left: auto; color: var(--muted); font-size: 11px; }
.event-transition { margin-left: 12px; color: var(--muted); font-size: 12px; }
code { padding: 2px 5px; border-radius: 4px; background: #fff3e1; font-size: 12px; word-break: break-all; }
@media (max-width: 1100px) { .stage-grid { grid-template-columns: repeat(2, minmax(150px, 1fr)); } }
</style>
