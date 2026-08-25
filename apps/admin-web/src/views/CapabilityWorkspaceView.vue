<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type UploadFile } from 'element-plus'
import { Download, Printer, Search, Upload } from '@element-plus/icons-vue'
import BatchActionBar from '../components/shared/BatchActionBar.vue'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import TreeWorkspace from '../components/shared/TreeWorkspace.vue'
import { pageFor, type PageField, type PageState } from '../config/pageCatalog'
import { useAuthStore } from '../stores/auth'
import { useTaskStore } from '../stores/tasks'
import { firstAuthorizedPath } from '../config/access'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const taskStore = useTaskStore()
const values = reactive<Record<string, any>>({})
const rows = ref<Record<string, unknown>[]>([])
const selectedRows = ref<Record<string, unknown>[]>([])
const page = ref(1)
const pageSize = ref(20)
const localLoading = ref(false)
const gridRef = ref<InstanceType<typeof DataGrid>>()
let queryTimer: number | undefined

const catalogPage = computed(() => pageFor(route.path))
const title = computed(() => catalogPage.value?.title || String(route.meta.title || '业务工作台'))
const columns = computed<DataGridColumn[]>(() => catalogPage.value?.columns.map((field) => ({
  prop: field.key, label: field.label, type: field.type, minWidth: ['text', 'masked'].includes(field.type) ? 150 : 112, sortable: true, sortKey: field.key,
})) || [])
const acceptanceState = computed<PageState>(() => {
  const requested = String(route.query.__state || 'normal') as PageState
  return catalogPage.value?.requiredStates.includes(requested) ? requested : 'normal'
})
const usesTree = computed(() => /tree|asset|relationship/.test(catalogPage.value?.implementation || ''))
const isExternal = computed(() => /external|integration|invoice|notification/.test(catalogPage.value?.implementation || ''))
const canImport = computed(() => auth.hasPermission(catalogPage.value?.permissions.import))
const canExport = computed(() => auth.hasPermission(catalogPage.value?.permissions.export))
const canPrint = computed(() => auth.hasPermission(catalogPage.value?.permissions.print))
const returnPath = computed(() => firstAuthorizedPath(auth.user?.permissions))
const loading = computed(() => localLoading.value || acceptanceState.value === 'loading')
const errorMessage = computed(() => acceptanceState.value === 'request-error' ? '请求失败：验收状态演练已触发，可使用“重新加载”恢复。' : '')
const displayRows = computed(() => ['normal'].includes(acceptanceState.value) ? rows.value : [])
const pagedRows = computed(() => displayRows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))

function exampleValue(field: PageField, index: number) {
  if (field.type === 'money') return (index + 1) * 128.35
  if (['number', 'area'].includes(field.type)) return (index + 1) * 10
  if (field.type === 'percent') return 72.5 + index * 3.1
  if (field.type === 'date') return `2026-08-${String(index + 10).padStart(2, '0')}`
  if (field.type === 'datetime') return `2026-08-${String(index + 10).padStart(2, '0')} 09:${String(index * 7).padStart(2, '0')}:00`
  if (field.type === 'month') return '2026-08'
  if (field.type === 'status') return ['PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED'][index % 4]
  if (field.type === 'masked') return `合成***${String(index + 1).padStart(2, '0')}`
  if (field.type === 'selection') return false
  return `${field.label}·结构样例${index + 1}`
}

function buildStructuralRows() {
  rows.value = Array.from({ length: 7 }, (_, index) => Object.fromEntries([
    ['id', `${catalogPage.value?.pageNo || 0}-${index + 1}`],
    ...(catalogPage.value?.columns || []).map((field) => [field.key, exampleValue(field, index)]),
  ]))
}

function inputType(field: PageField) {
  if (field.type === 'date-range') return 'daterange'
  if (field.type === 'month-range') return 'monthrange'
  if (field.type === 'month') return 'month'
  if (field.type === 'date') return 'date'
  return ''
}

function applyQuery() {
  window.clearTimeout(queryTimer)
  localLoading.value = true
  page.value = 1
  queryTimer = window.setTimeout(() => {
    localLoading.value = false
    const keyword = Object.values(values).filter(Boolean).join(' ').trim().toLowerCase()
    buildStructuralRows()
    if (keyword) rows.value = rows.value.filter((row) => JSON.stringify(row).toLowerCase().includes(keyword))
  }, 120)
}

function reset() {
  Object.keys(values).forEach((key) => delete values[key])
  page.value = 1
  buildStructuralRows()
  void router.replace({ query: Object.fromEntries(Object.entries(route.query).filter(([key]) => key === '__state')) })
}

function saveFilter() {
  localStorage.setItem(`pms-filter:${route.path}`, JSON.stringify(values))
  ElMessage.success('当前非敏感筛选条件已保存到本机')
}

function restoreFilter() {
  try {
    Object.assign(values, JSON.parse(localStorage.getItem(`pms-filter:${route.path}`) || '{}'))
    applyQuery()
  } catch {
    localStorage.removeItem(`pms-filter:${route.path}`)
    ElMessage.warning('已清除损坏的本地筛选条件')
  }
}

function onSort({ prop, order }: { prop: string; order: string | null }) {
  if (!order) return buildStructuralRows()
  rows.value = [...rows.value].sort((left, right) => String(left[prop] ?? '').localeCompare(String(right[prop] ?? ''), 'zh-CN') * (order === 'descending' ? -1 : 1))
}

function taskColumns() {
  return columns.value.map((column) => ({ key: column.prop, label: column.label }))
}

function fileStem() {
  return `${title.value}-${new Date().toISOString().slice(0, 10)}`.replace(/[\\/:*?"<>|]/g, '-')
}

function createExport(sourceRows = displayRows.value) {
  taskStore.createExportTask({ title: `${title.value}数据导出`, sourcePath: route.path, projectId: auth.currentProjectId, fileName: `${fileStem()}.csv`, columns: taskColumns(), rows: sourceRows.map((row) => ({ ...row })) })
  taskStore.openDrawer()
}

function createPrint(sourceRows = displayRows.value) {
  taskStore.createPrintTask({ title: `${title.value}打印文件`, sourcePath: route.path, projectId: auth.currentProjectId, fileName: `${fileStem()}-打印.html`, columns: taskColumns(), rows: sourceRows.map((row) => ({ ...row })) })
  taskStore.openDrawer()
}

function handleImport(uploadFile: UploadFile) {
  if (!uploadFile.raw) return
  taskStore.createImportValidationTask({ title: `${title.value}导入校验`, sourcePath: route.path, projectId: auth.currentProjectId, file: uploadFile.raw, expectedHeaders: taskColumns().map((column) => column.label) })
  taskStore.openDrawer()
}

function clearSelection() {
  selectedRows.value = []
  gridRef.value?.clearSelection()
}

function reload() {
  void router.replace({ query: Object.fromEntries(Object.entries(route.query).filter(([key]) => key !== '__state')) })
  buildStructuralRows()
}

watch(() => route.path, () => {
  Object.keys(values).forEach((key) => delete values[key])
  selectedRows.value = []
  page.value = 1
  buildStructuralRows()
}, { immediate: true })
onBeforeUnmount(() => window.clearTimeout(queryTimer))
</script>

<template>
  <section v-if="catalogPage" class="capability-page">
    <el-alert type="info" :closable="false" show-icon class="capability-note"
      :title="`页面 ${catalogPage.pageNo}/49 · ${catalogPage.sourceRef} · 当前显示脱敏结构演练数据，业务闭环按 Goal ${catalogPage.wave} 波次阶段接入。`" />
    <el-alert v-if="isExternal" type="warning" :closable="false" show-icon class="capability-note"
      title="当前仅启用可替换模拟适配器，未连接生产支付、发票、银行、通知或设备通道。" />
    <el-alert v-if="acceptanceState === 'validation-error'" type="error" :closable="false" show-icon class="capability-note" title="校验失败：请检查必填条件和字段格式。" />
    <el-alert v-if="acceptanceState === 'conflict'" type="warning" :closable="false" show-icon class="capability-note" title="数据版本冲突：记录已被其他操作更新，请刷新后重试。" />
    <el-alert v-if="acceptanceState === 'partial-failure'" type="warning" :closable="false" show-icon class="capability-note" title="批量任务部分失败：5 条成功，2 条失败；失败明细可从任务中心下载。" />

    <el-result v-if="acceptanceState === 'forbidden'" icon="warning" title="无权访问" sub-title="当前账号缺少此页面的读取权限。">
      <template #extra><el-button type="primary" @click="router.push(returnPath)">返回可用工作台</el-button></template>
    </el-result>

    <template v-else>
      <QueryPanel :loading="loading" @query="applyQuery" @reset="reset" @save="saveFilter" @restore="restoreFilter">
        <el-form-item v-for="field in catalogPage.query" :key="field.key" :label="field.label">
          <el-date-picker v-if="inputType(field)" v-model="values[field.key]" :type="inputType(field) as any" clearable
            :placeholder="field.label" :start-placeholder="`${field.label}开始`" :end-placeholder="`${field.label}结束`" style="width: 210px" />
          <el-input v-else v-model="values[field.key] as string" clearable :placeholder="field.label" style="width: 190px">
            <template v-if="field.type === 'text'" #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
      </QueryPanel>

      <div class="capability-operations">
        <span>本页主流程</span>
        <el-tag v-for="operation in catalogPage.operations" :key="operation" size="small" effect="plain">{{ operation }}</el-tag>
      </div>

      <BatchActionBar :selected-count="selectedRows.length" :total="displayRows.length" @clear="clearSelection">
        <el-button v-if="canExport" size="small" :icon="Download" @click="createExport(selectedRows)">导出所选</el-button>
        <el-button v-if="canPrint" size="small" :icon="Printer" @click="createPrint(selectedRows)">打印所选</el-button>
      </BatchActionBar>

      <TreeWorkspace v-if="usesTree" tree-title="项目与业务范围">
        <template #tree>
          <el-tree :data="[{ id: 'project', label: auth.currentProject?.name || '当前项目', children: [{ id: 'all', label: `全部${title}` }] }]"
            node-key="id" default-expand-all highlight-current />
        </template>
        <DataGrid ref="gridRef" v-model:page="page" v-model:page-size="pageSize" :rows="pagedRows" :columns="columns"
          :loading="loading" :error-message="errorMessage" :total="displayRows.length" :storage-key="route.path"
          @selection-change="selectedRows = $event" @sort-change="onSort" @reload="reload">
          <template #toolbar>
            <el-upload v-if="canImport" action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="handleImport"><el-button :icon="Upload">导入 CSV</el-button></el-upload>
            <el-button v-if="canExport" :icon="Download" @click="createExport()">导出当前结果</el-button>
            <el-button v-if="canPrint" :icon="Printer" @click="createPrint()">生成打印文件</el-button>
          </template>
        </DataGrid>
      </TreeWorkspace>

      <DataGrid v-else ref="gridRef" v-model:page="page" v-model:page-size="pageSize" :rows="pagedRows" :columns="columns"
        :loading="loading" :error-message="errorMessage" :total="displayRows.length" :storage-key="route.path"
        @selection-change="selectedRows = $event" @sort-change="onSort" @reload="reload">
        <template #toolbar>
          <el-upload v-if="canImport" action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="handleImport"><el-button :icon="Upload">导入 CSV</el-button></el-upload>
          <el-button v-if="canExport" :icon="Download" @click="createExport()">导出当前结果</el-button>
          <el-button v-if="canPrint" :icon="Printer" @click="createPrint()">生成打印文件</el-button>
        </template>
      </DataGrid>
    </template>
  </section>
</template>

<style scoped>
.capability-page { display: flex; flex-direction: column; gap: 12px; }
.capability-note { flex: 0 0 auto; }
.capability-operations { min-height: 38px; padding: 7px 11px; display: flex; align-items: center; flex-wrap: wrap; gap: 7px; border: 1px solid #e2e7ee; border-radius: 7px; background: #fff; }
.capability-operations > span { margin-right: 4px; color: #7e899b; font-size: 11px; }
</style>
