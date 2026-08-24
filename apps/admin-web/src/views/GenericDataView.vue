<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Edit, Plus, Refresh, Search, Setting, Upload } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { schemaFor, type FormField } from '../config/pageSchemas'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const keyword = ref(String(route.query.keyword || ''))
const status = ref(String(route.query.status || ''))
const page = ref(Number(route.query.page || 1))
const pageSize = ref(Number(route.query.size || 20))
const rows = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const drawerVisible = ref(false)
const submitting = ref(false)
const editing = ref<Record<string, unknown> | null>(null)
const form = reactive<Record<string, unknown>>({})
let controller: AbortController | null = null

const title = computed(() => String(route.meta.title || '数据管理'))
const schema = computed(() => schemaFor(route.path))
const visibleColumns = ref<string[]>([])
const canWrite = computed(() => Boolean(schema.value?.form && auth.hasPermission(schema.value.writePermission)))
const currentCommunityId = computed(() => schema.value?.resource === 'communities' ? undefined : auth.currentProjectId)

function resetColumns() {
  visibleColumns.value = schema.value?.columns.map((column) => column.prop) || []
}

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

function statusType(value: unknown) {
  const code = String(value || '')
  if (['ACTIVE', 'NORMAL', 'PAID', 'SUCCESS', 'COMPLETED'].includes(code)) return 'success'
  if (['INACTIVE', 'FAILED', 'CANCELLED'].includes(code)) return 'danger'
  if (['PARTIAL', 'PROCESSING', 'PENDING'].includes(code)) return 'warning'
  return 'info'
}

function isStatus(prop: string) {
  return ['status', 'operation_status', 'result_status', 'enabled'].includes(prop)
}

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
  if (!saved) return
  try {
    const value = JSON.parse(saved)
    keyword.value = value.keyword || ''
    status.value = value.status || ''
    pageSize.value = value.pageSize || 20
    applyQuery()
  } catch {
    localStorage.removeItem(`pms-filter:${route.path}`)
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
    ElMessage.error(error.response?.data?.message || '保存失败')
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

function taskNotice(kind: 'import' | 'export') {
  ElMessage.info(`${kind === 'import' ? '导入' : '导出'}将通过异步任务执行；当前页面保留任务入口。`)
}

watch([() => route.path, () => auth.currentProjectId], () => {
  keyword.value = String(route.query.keyword || '')
  status.value = String(route.query.status || '')
  page.value = 1
  resetColumns()
  void load()
}, { immediate: true })
watch([page, pageSize], () => void load())
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="data-page">
    <el-alert v-if="schema?.description" type="warning" :closable="false" show-icon class="page-note" :title="schema.description" />
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" label-position="left" @submit.prevent="applyQuery">
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
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="applyQuery">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
          <el-button text @click="saveFilter">保存筛选</el-button>
          <el-button text @click="restoreFilter">恢复筛选</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <div class="table-toolbar">
        <div>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增{{ title }}</el-button>
          <el-button :icon="Upload" @click="taskNotice('import')">导入任务</el-button>
          <el-button :icon="Download" @click="taskNotice('export')">导出任务</el-button>
        </div>
        <div class="toolbar-right">
          <span class="record-summary">共 <strong>{{ total }}</strong> 条合成记录</span>
          <el-popover placement="bottom-end" :width="220" trigger="click">
            <template #reference><el-button circle :icon="Setting" aria-label="列设置" /></template>
            <el-checkbox-group v-model="visibleColumns" class="column-settings">
              <el-checkbox v-for="column in schema?.columns" :key="column.prop" :value="column.prop">{{ column.label }}</el-checkbox>
            </el-checkbox-group>
          </el-popover>
        </div>
      </div>
      <el-alert v-if="errorMessage" type="error" show-icon :closable="false" :title="errorMessage">
        <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
      </el-alert>
      <el-table v-loading="loading" :data="rows" stripe row-key="id" height="calc(100vh - 410px)" empty-text="暂无符合条件的数据" @sort-change="onSort">
        <el-table-column type="selection" width="46" fixed="left" />
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column v-for="column in schema?.columns.filter((item) => visibleColumns.includes(item.prop))" :key="column.prop"
          :prop="column.prop" :label="column.label" :width="column.width" :min-width="column.minWidth"
          :sortable="column.sortable ? 'custom' : false" show-overflow-tooltip>
          <template #default="scope">
            <el-tag v-if="isStatus(column.prop)" :type="statusType(scope.row[column.prop])" effect="light">{{ display(scope.row[column.prop]) }}</el-tag>
            <span v-else>{{ display(scope.row[column.prop]) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="openEdit(scope.row)">查看</el-button>
            <el-button v-if="canWrite" link type="primary" :icon="Edit" @click="openEdit(scope.row)">编辑</el-button>
            <el-button v-if="canWrite" link type="danger" @click="archive(scope.row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-row">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper" :total="total" />
      </div>
    </el-card>

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
