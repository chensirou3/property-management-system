<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Download, Edit, Plus, Printer, Search, Upload } from '@element-plus/icons-vue'
import { http } from '../api/http'
import BatchActionBar from '../components/shared/BatchActionBar.vue'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import { pageFor } from '../config/pageCatalog'
import { schemaFor, type FormField } from '../config/pageSchemas'
import { useAuthStore } from '../stores/auth'
import { useTaskStore } from '../stores/tasks'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const taskStore = useTaskStore()
const keyword = ref(String(route.query.keyword || ''))
const status = ref(String(route.query.status || ''))
const page = ref(Number(route.query.page || 1))
const pageSize = ref(Number(route.query.size || 20))
const rows = ref<Record<string, unknown>[]>([])
const selectedRows = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const drawerVisible = ref(false)
const submitting = ref(false)
const editing = ref<Record<string, unknown> | null>(null)
const form = reactive<Record<string, unknown>>({})
const gridRef = ref<InstanceType<typeof DataGrid>>()
let controller: AbortController | null = null

const title = computed(() => String(route.meta.title || '数据管理'))
const schema = computed(() => schemaFor(route.path))
const catalogPage = computed(() => pageFor(route.path))
const canWrite = computed(() => Boolean(schema.value?.form && auth.hasPermission(schema.value.writePermission)))
const canImport = computed(() => Boolean(catalogPage.value?.permissions.import && (auth.hasPermission(catalogPage.value.permissions.import) || canWrite.value)))
const canExport = computed(() => Boolean(auth.hasPermission(catalogPage.value?.permissions.export) || auth.hasPermission(schema.value?.readPermission)))
const canPrint = computed(() => Boolean(catalogPage.value?.permissions.print && (auth.hasPermission(catalogPage.value.permissions.print) || auth.hasPermission(schema.value?.readPermission))))
const currentCommunityId = computed(() => schema.value?.resource === 'communities' ? undefined : auth.currentProjectId)
const gridColumns = computed<DataGridColumn[]>(() => schema.value?.columns.map((column) => ({
  ...column,
  type: catalogPage.value?.columns.find((candidate) => candidate.key === column.prop)?.type,
})) || [])

async function load() {
  if (!schema.value) return
  if (schema.value.resource !== 'communities' && !auth.currentProjectId) {
    rows.value = []
    total.value = 0
    return
  }
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await http.get(`/data/${schema.value.resource}`, {
      signal: controller.signal,
      params: {
        communityId: currentCommunityId.value,
        keyword: keyword.value || undefined,
        status: schema.value.status || status.value || undefined,
        category: schema.value.category,
        page: page.value,
        size: pageSize.value,
        sort: String(route.query.sort || '') || undefined,
      },
    })
    rows.value = data.items
    total.value = data.total
  } catch (error: any) {
    if (error.code !== 'ERR_CANCELED') errorMessage.value = error.response?.data?.message || '数据加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function applyQuery() {
  page.value = 1
  void router.replace({ query: { ...route.query, keyword: keyword.value || undefined, status: status.value || undefined, page: 1, size: pageSize.value } })
  void load()
}

function reset() {
  keyword.value = ''
  status.value = ''
  page.value = 1
  void router.replace({ query: {} })
  void load()
}

function saveFilter() {
  localStorage.setItem(`pms-filter:${route.path}`, JSON.stringify({ keyword: keyword.value, status: status.value, pageSize: pageSize.value }))
  ElMessage.success('当前非敏感筛选条件已保存到本机')
}

function restoreFilter() {
  const saved = localStorage.getItem(`pms-filter:${route.path}`)
  if (!saved) {
    ElMessage.info('当前页面没有已保存的筛选条件')
    return
  }
  try {
    const value = JSON.parse(saved)
    keyword.value = value.keyword || ''
    status.value = value.status || ''
    pageSize.value = value.pageSize || 20
    applyQuery()
  } catch {
    localStorage.removeItem(`pms-filter:${route.path}`)
    ElMessage.warning('已清除损坏的本地筛选条件')
  }
}

function onSort({ prop, order }: { prop: string; order: string | null }) {
  const column = schema.value?.columns.find((item) => item.prop === prop)
  const sort = order && column?.sortKey ? `${column.sortKey},${order === 'descending' ? 'desc' : 'asc'}` : undefined
  page.value = 1
  void router.replace({ query: { ...route.query, sort, page: 1 } }).then(load)
}

function clearForm() {
  Object.keys(form).forEach((key) => delete form[key])
  Object.assign(form, schema.value?.defaults || {})
}

function openCreate() {
  editing.value = null
  clearForm()
  drawerVisible.value = true
}

function openEdit(row: Record<string, unknown>) {
  editing.value = row
  clearForm()
  schema.value?.form?.forEach((field) => { form[field.prop] = row[field.prop] })
  drawerVisible.value = true
}

function inputComponent(field: FormField) {
  if (field.type === 'select') return 'el-select'
  if (field.type === 'switch') return 'el-switch'
  if (field.type === 'date' || field.type === 'datetime') return 'el-date-picker'
  return 'el-input'
}

async function submit() {
  if (!schema.value) return
  for (const field of schema.value.form || []) {
    if (field.required && (form[field.prop] === '' || form[field.prop] === undefined || form[field.prop] === null)) {
      ElMessage.warning(`请填写${field.label}`)
      return
    }
  }
  submitting.value = true
  try {
    const body: Record<string, unknown> = { ...schema.value.defaults, ...form }
    if (schema.value.resource === 'vehicles' && !editing.value) body.plate_no_search_hash = crypto.randomUUID().replaceAll('-', '')
    const params = { communityId: currentCommunityId.value }
    if (editing.value) {
      await http.put(`/data/${schema.value.resource}/${editing.value.id}`, body, { params: { ...params, version: editing.value.version } })
      ElMessage.success('修改成功；版本号和审计记录已更新')
    } else {
      await http.post(`/data/${schema.value.resource}`, body, { params })
      ElMessage.success('新增成功；已写入操作审计')
    }
    drawerVisible.value = false
    await load()
  } catch (error: any) {
    if (error.response?.status === 409) ElMessage.error(error.response?.data?.message || '数据已被其他操作修改，请刷新后重试')
    else if (error.response?.status === 422) ElMessage.error(error.response?.data?.message || '提交内容未通过业务校验')
    else ElMessage.error(error.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function archive(row: Record<string, unknown>) {
  if (!schema.value) return
  await ElMessageBox.confirm('该操作为软停用，不会物理删除记录。是否继续？', `停用${title.value}`, { type: 'warning' })
  try {
    await http.delete(`/data/${schema.value.resource}/${row.id}`, { params: { communityId: currentCommunityId.value, version: row.version } })
    ElMessage.success('记录已停用并写入审计')
    await load()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '停用失败')
  }
}

function fileStem() {
  return `${title.value}-${new Date().toISOString().slice(0, 10)}`.replace(/[\\/:*?"<>|]/g, '-')
}

function taskColumns() {
  return schema.value?.columns.map((column) => ({ key: column.prop, label: column.label })) || []
}

function createExport(sourceRows = rows.value) {
  taskStore.createExportTask({
    title: `${title.value}数据导出`, sourcePath: route.path, projectId: auth.currentProjectId,
    fileName: `${fileStem()}.csv`, columns: taskColumns(), rows: sourceRows.map((row) => ({ ...row })),
  })
  taskStore.openDrawer()
  ElMessage.success('导出任务已进入任务中心')
}

function createPrint(sourceRows = rows.value) {
  taskStore.createPrintTask({
    title: `${title.value}打印文件`, sourcePath: route.path, projectId: auth.currentProjectId,
    fileName: `${fileStem()}-打印.html`, columns: taskColumns(), rows: sourceRows.map((row) => ({ ...row })),
  })
  taskStore.openDrawer()
  ElMessage.success('打印任务已进入任务中心')
}

function handleImport(uploadFile: UploadFile) {
  if (!uploadFile.raw) return
  taskStore.createImportValidationTask({
    title: `${title.value}导入校验`, sourcePath: route.path, projectId: auth.currentProjectId,
    file: uploadFile.raw, expectedHeaders: taskColumns().map((column) => column.label),
  })
  taskStore.openDrawer()
  ElMessage.success('导入文件已进入异步校验；通过前不会写入业务数据')
}

function clearSelection() {
  selectedRows.value = []
  gridRef.value?.clearSelection()
}

watch([() => route.path, () => auth.currentProjectId], () => {
  keyword.value = String(route.query.keyword || '')
  status.value = String(route.query.status || '')
  page.value = 1
  selectedRows.value = []
  void load()
}, { immediate: true })
watch([page, pageSize], () => void load())
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="data-page">
    <el-alert v-if="schema?.description" type="warning" :closable="false" show-icon class="page-note" :title="schema.description" />

    <QueryPanel :loading="loading" @query="applyQuery" @reset="reset" @save="saveFilter" @restore="restoreFilter">
      <el-form-item label="关键字">
        <el-input v-model="keyword" clearable :placeholder="`搜索${title}`" @keyup.enter="applyQuery">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 170px">
          <el-option label="启用/正常" value="ACTIVE" /><el-option label="停用" value="INACTIVE" />
          <el-option label="未缴" value="UNPAID" /><el-option label="部分缴费" value="PARTIAL" /><el-option label="已缴" value="PAID" />
        </el-select>
      </el-form-item>
    </QueryPanel>

    <BatchActionBar :selected-count="selectedRows.length" :total="total" @clear="clearSelection">
      <el-button v-if="canExport" size="small" :icon="Download" @click="createExport(selectedRows)">导出所选</el-button>
      <el-button v-if="canPrint" size="small" :icon="Printer" @click="createPrint(selectedRows)">打印所选</el-button>
    </BatchActionBar>

    <DataGrid ref="gridRef" v-model:page="page" v-model:page-size="pageSize" :rows="rows" :columns="gridColumns"
      :loading="loading" :error-message="errorMessage" :total="total" :storage-key="route.path"
      @selection-change="selectedRows = $event" @sort-change="onSort" @reload="load">
      <template #toolbar>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增{{ title }}</el-button>
        <el-upload v-if="canImport" action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="handleImport">
          <el-button :icon="Upload">导入 CSV</el-button>
        </el-upload>
        <el-button v-if="canExport" :icon="Download" @click="createExport()">导出当前页</el-button>
        <el-button v-if="canPrint" :icon="Printer" @click="createPrint()">打印当前页</el-button>
      </template>
      <template #summary><span class="record-summary">共 <strong>{{ total }}</strong> 条合成记录</span></template>
      <template #operations="{ row }">
        <el-button link type="primary" @click="openEdit(row)">查看</el-button>
        <el-button v-if="canWrite" link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
        <el-button v-if="canWrite" link type="danger" @click="archive(row)">停用</el-button>
      </template>
    </DataGrid>

    <el-drawer v-model="drawerVisible" :title="`${editing ? '编辑' : '新增'}${title}`" size="520px">
      <el-alert type="info" :closable="false" show-icon title="本页面仅处理合成数据；修改使用乐观锁并记录审计。" />
      <el-form label-position="top" class="drawer-form">
        <el-form-item v-for="field in schema?.form" :key="field.prop" :label="field.label" :required="field.required">
          <component :is="inputComponent(field)" v-model="form[field.prop]" style="width: 100%"
            :type="field.type === 'textarea' ? 'textarea' : field.type === 'number' ? 'number' : undefined"
            :rows="field.type === 'textarea' ? 3 : undefined"
            :value-format="field.type === 'date' ? 'YYYY-MM-DD' : field.type === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : undefined">
            <template v-if="field.type === 'select'">
              <el-option v-for="option in field.options" :key="option.value" :label="option.label" :value="option.value" />
            </template>
          </component>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>
