<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Download, Plus, Search, Upload } from '@element-plus/icons-vue'
import { http } from '../api/http'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const canWrite = computed(() => auth.hasPermission('property:write'))
const title = computed(() => route.path === '/archives/customer-assets' ? '客户资产关系' : '客户信息')
const keyword = ref('')
const customerType = ref('')
const statusFilter = ref('ACTIVE')
const page = ref(1)
const pageSize = ref(20)
const rows = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const profileVisible = ref(false)
const profileLoading = ref(false)
const profile = ref<any>(null)
const customerDialogVisible = ref(false)
const customerSubmitting = ref(false)
const editing = ref(false)
const relationVisible = ref(false)
const relationSubmitting = ref(false)
const assetOptions = ref<any[]>([])
const importVisible = ref(false)
const importLoading = ref(false)
const importFileName = ref('')
const importRows = ref<Record<string, unknown>[]>([])
const importReport = ref<any>(null)
const customerForm = reactive<Record<string, any>>({})
const relationForm = reactive({ assetId: '', relationType: 'OWNER', primaryRelation: false, startDate: '', reason: '' })

const columns: DataGridColumn[] = [
  { prop: 'customerNo', label: '客户编号', width: 150 },
  { prop: 'displayName', label: '客户名称', minWidth: 180 },
  { prop: 'customerType', label: '客户类型', width: 115, type: 'status' },
  { prop: 'customerClass', label: '客户分类', width: 110, type: 'status' },
  { prop: 'mobileMasked', label: '脱敏联系方式', width: 155, type: 'masked' },
  { prop: 'activeAssetCount', label: '有效资产', width: 100 },
  { prop: 'activeVehicleCount', label: '有效车辆', width: 100 },
  { prop: 'status', label: '状态', width: 95, type: 'status' },
]

async function load() {
  if (!communityId.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await http.get('/property/customers', { params: {
      communityId: communityId.value, keyword: keyword.value || undefined,
      customerType: customerType.value || undefined, status: statusFilter.value || undefined,
      page: page.value, size: pageSize.value,
    } })
    rows.value = data.items
    total.value = data.total
  } catch (error: any) {
    errorMessage.value = error.response?.data?.message || '客户列表加载失败'
  } finally {
    loading.value = false
  }
}

async function loadProfile(customerId: string) {
  profileVisible.value = true
  profileLoading.value = true
  try {
    const { data } = await http.get(`/property/customers/${customerId}`, { params: { communityId: communityId.value } })
    profile.value = data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '客户档案加载失败')
    profileVisible.value = false
  } finally {
    profileLoading.value = false
  }
}

function blankCustomer() {
  Object.keys(customerForm).forEach((key) => delete customerForm[key])
  Object.assign(customerForm, {
    customer_no: '', display_name: '', customer_type: 'PERSON', customer_class: 'OWNER',
    mobile_masked: '', certificate_type: '', certificate_masked: '', gender: 'UNKNOWN',
    birthday: null, remarks: '', status: 'ACTIVE', version: 0,
  })
}

function openCreate() {
  editing.value = false
  blankCustomer()
  customerDialogVisible.value = true
}

async function openEdit(row: Record<string, any>) {
  editing.value = true
  await loadProfile(String(row.id))
  const customer = profile.value.customer
  blankCustomer()
  Object.assign(customerForm, {
    id: customer.id, customer_no: customer.customerNo, display_name: customer.displayName,
    customer_type: customer.customerType, customer_class: customer.customerClass,
    mobile_masked: customer.mobileMasked, certificate_type: customer.certificateType,
    certificate_masked: customer.certificateMasked, gender: customer.gender,
    birthday: customer.birthday, remarks: customer.remarks, status: customer.status, version: customer.version,
  })
  profileVisible.value = false
  customerDialogVisible.value = true
}

async function saveCustomer() {
  if (!customerForm.customer_no || !customerForm.display_name) return ElMessage.warning('请填写客户编号和名称')
  customerSubmitting.value = true
  try {
    const body = { ...customerForm }
    delete body.id
    delete body.version
    if (editing.value) {
      await http.put(`/data/customers/${customerForm.id}`, body, { params: { communityId: communityId.value, version: customerForm.version } })
    } else {
      await http.post('/data/customers', body, { params: { communityId: communityId.value } })
    }
    ElMessage.success(editing.value ? '客户档案已更新' : '客户档案已创建')
    customerDialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '客户档案保存失败')
  } finally {
    customerSubmitting.value = false
  }
}

async function archiveCustomer(row: Record<string, any>) {
  await ElMessageBox.confirm('停用前将检查有效资产关系、车辆关系和未结清账单。是否继续？', '停用客户', { type: 'warning' })
  try {
    await http.delete(`/data/customers/${row.id}`, { params: { communityId: communityId.value, version: row.version } })
    ElMessage.success('客户已停用')
    await load()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '客户仍被有效业务引用，无法停用')
  }
}

async function searchAssets(query = '') {
  const { data } = await http.get('/property/assets', { params: { communityId: communityId.value, keyword: query || undefined, page: 1, size: 100 } })
  assetOptions.value = data.items
}

function openRelation() {
  Object.assign(relationForm, { assetId: '', relationType: 'OWNER', primaryRelation: false, startDate: new Date().toISOString().slice(0, 10), reason: '' })
  relationVisible.value = true
  void searchAssets()
}

async function createRelation() {
  if (!relationForm.assetId || !relationForm.startDate) return ElMessage.warning('请选择资产和生效日期')
  relationSubmitting.value = true
  try {
    await http.post('/property/relations', {
      communityId: communityId.value, customerId: profile.value.customer.id, ...relationForm,
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('客户资产关系已生效并写入历史')
    relationVisible.value = false
    await Promise.all([loadProfile(profile.value.customer.id), load()])
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '关系登记失败')
  } finally {
    relationSubmitting.value = false
  }
}

async function endRelation(relation: any) {
  try {
    const { value } = await ElMessageBox.prompt('请输入关系结束原因', '结束客户资产关系', { inputValidator: (text) => Boolean(text?.trim()) || '结束原因不能为空' })
    await http.post(`/property/relations/${relation.id}:end`, {
      communityId: communityId.value, effectiveDate: new Date().toISOString().slice(0, 10),
      reason: value, expectedVersion: relation.version,
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('关系已结束，历史记录仍保留')
    await Promise.all([loadProfile(profile.value.customer.id), load()])
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error.response?.data?.message || '结束关系失败')
  }
}

function parseCsvLine(line: string) {
  const values: string[] = []
  let current = ''
  let quoted = false
  for (let index = 0; index < line.length; index++) {
    const char = line[index]
    if (char === '"' && quoted && line[index + 1] === '"') { current += '"'; index++ }
    else if (char === '"') quoted = !quoted
    else if (char === ',' && !quoted) { values.push(current.trim()); current = '' }
    else current += char
  }
  values.push(current.trim())
  return values
}

async function selectImport(file: UploadFile) {
  if (!file.raw) return
  importFileName.value = file.name
  const text = (await file.raw.text()).replace(/^\uFEFF/, '')
  const lines = text.split(/\r?\n/).filter((line) => line.trim())
  const headers = parseCsvLine(lines.shift() || '')
  importRows.value = lines.map((line) => {
    const values = parseCsvLine(line)
    return Object.fromEntries(headers.map((header, index) => [header, values[index] ?? '']))
  })
  importReport.value = null
}

async function downloadTemplate() {
  const response = await http.get('/property/imports/template', { params: { resource: 'CUSTOMER' }, responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = 'customer-import-template.csv'
  link.click()
  URL.revokeObjectURL(url)
}

async function validateImport() {
  if (!importRows.value.length) return ElMessage.warning('请选择包含数据行的 CSV 文件')
  importLoading.value = true
  try {
    const { data } = await http.post('/property/imports:validate', { communityId: communityId.value, resource: 'CUSTOMER', rows: importRows.value })
    importReport.value = data
    if (data.readyToImport) ElMessage.success('全部数据通过校验，可进入后续导入审批')
    else ElMessage.warning('存在校验错误，本次不会写入业务数据')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '导入校验失败')
  } finally {
    importLoading.value = false
  }
}

function openImport() {
  importRows.value = []
  importReport.value = null
  importFileName.value = ''
  importVisible.value = true
}

function applyQuery() { page.value = 1; void load() }
function reset() { keyword.value = ''; customerType.value = ''; statusFilter.value = 'ACTIVE'; page.value = 1; void load() }
function saveFilter() { localStorage.setItem(`pms-filter:${route.path}`, JSON.stringify({ keyword: keyword.value, customerType: customerType.value, status: statusFilter.value })); ElMessage.success('筛选条件已保存') }
function restoreFilter() {
  try {
    const saved = JSON.parse(localStorage.getItem(`pms-filter:${route.path}`) || '{}')
    keyword.value = saved.keyword || ''
    customerType.value = saved.customerType || ''
    statusFilter.value = saved.status || 'ACTIVE'
  } catch { /* ignored */ }
  applyQuery()
}

watch(communityId, () => { page.value = 1; void load() })
watch([page, pageSize], load)
onMounted(load)
</script>

<template>
  <section class="customer-workspace">
    <el-alert type="info" :closable="false" show-icon title="客户主档与资产关系分层管理；手机号、证件号只展示脱敏值，关系启停和产权变更保留完整时间线。" />
    <QueryPanel :loading="loading" @query="applyQuery" @reset="reset" @save="saveFilter" @restore="restoreFilter">
      <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="客户编号/名称/脱敏手机"><template #prefix><el-icon><Search /></el-icon></template></el-input></el-form-item>
      <el-form-item label="客户类型"><el-select v-model="customerType" clearable><el-option label="个人" value="PERSON" /><el-option label="机构" value="ORGANIZATION" /></el-select></el-form-item>
      <el-form-item label="状态"><el-select v-model="statusFilter" clearable><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item>
    </QueryPanel>
    <DataGrid v-model:page="page" v-model:page-size="pageSize" :rows="rows" :columns="columns"
      :loading="loading" :error-message="errorMessage" :total="total" :storage-key="route.path" @reload="load">
      <template #toolbar>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增客户</el-button>
        <el-button v-if="canWrite" :icon="Upload" @click="openImport">导入校验</el-button>
      </template>
      <template #operations="{ row }">
        <el-button link type="primary" @click="loadProfile(String(row.id))">资产与历史</el-button>
        <el-button v-if="canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
        <el-button v-if="canWrite" link type="danger" @click="archiveCustomer(row)">停用</el-button>
      </template>
    </DataGrid>

    <el-drawer v-model="profileVisible" title="客户完整档案" size="800px">
      <div v-if="profile" v-loading="profileLoading" class="profile-body">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="客户编号">{{ profile.customer.customerNo }}</el-descriptions-item>
          <el-descriptions-item label="客户名称">{{ profile.customer.displayName }}</el-descriptions-item>
          <el-descriptions-item label="客户类型">{{ profile.customer.customerType }} / {{ profile.customer.customerClass || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :value="profile.customer.status" /></el-descriptions-item>
          <el-descriptions-item label="脱敏手机">{{ profile.customer.mobileMasked || '—' }}</el-descriptions-item>
          <el-descriptions-item label="脱敏证件">{{ profile.customer.certificateMasked || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="profile-actions"><el-button v-if="canWrite" type="primary" @click="openRelation">绑定资产关系</el-button></div>
        <el-tabs>
          <el-tab-pane label="资产关系">
            <el-table :data="profile.relations" border size="small">
              <el-table-column prop="assetName" label="资产" min-width="170" />
              <el-table-column prop="relationType" label="关系" width="110" />
              <el-table-column prop="startDate" label="生效日" width="115" />
              <el-table-column prop="endDate" label="结束日" width="115" />
              <el-table-column prop="status" label="状态" width="95"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column>
              <el-table-column v-if="canWrite" label="操作" width="90"><template #default="scope"><el-button v-if="scope.row.status === 'ACTIVE'" link type="danger" @click="endRelation(scope.row)">结束</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="车辆"><el-table :data="profile.vehicles" border size="small"><el-table-column prop="plateNoMasked" label="脱敏车牌" /><el-table-column prop="parkingName" label="关联车位" /><el-table-column prop="status" label="状态" /></el-table></el-tab-pane>
          <el-tab-pane label="关系时间线">
            <el-empty v-if="!profile.timeline.length" description="尚无关系变更事件" />
            <el-timeline v-else><el-timeline-item v-for="event in profile.timeline" :key="event.id" :timestamp="event.effectiveDate" placement="top"><strong>{{ event.eventType }}</strong><p>{{ event.assetName || event.assetId }} · {{ event.reason || '未填写原因' }}</p></el-timeline-item></el-timeline>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog v-model="customerDialogVisible" :title="`${editing ? '编辑' : '新增'}客户`" width="680px">
      <el-form label-position="top" class="customer-form">
        <el-form-item label="客户编号" required><el-input v-model="customerForm.customer_no" /></el-form-item>
        <el-form-item label="客户名称" required><el-input v-model="customerForm.display_name" /></el-form-item>
        <el-form-item label="客户类型"><el-select v-model="customerForm.customer_type"><el-option label="个人" value="PERSON" /><el-option label="机构" value="ORGANIZATION" /></el-select></el-form-item>
        <el-form-item label="客户分类"><el-select v-model="customerForm.customer_class" clearable><el-option label="业主" value="OWNER" /><el-option label="租户" value="TENANT" /></el-select></el-form-item>
        <el-form-item label="脱敏手机"><el-input v-model="customerForm.mobile_masked" placeholder="例如 138****0000" /></el-form-item>
        <el-form-item label="性别"><el-select v-model="customerForm.gender"><el-option label="未知" value="UNKNOWN" /><el-option label="男" value="MALE" /><el-option label="女" value="FEMALE" /></el-select></el-form-item>
        <el-form-item label="生日"><el-date-picker v-model="customerForm.birthday" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="customerForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="customerForm.remarks" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="customerDialogVisible = false">取消</el-button><el-button type="primary" :loading="customerSubmitting" @click="saveCustomer">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="relationVisible" title="绑定客户资产关系" width="540px">
      <el-form label-position="top">
        <el-form-item label="资产" required><el-select v-model="relationForm.assetId" filterable remote :remote-method="searchAssets"><el-option v-for="item in assetOptions" :key="item.id" :label="`${item.code} ${item.displayName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="关系类型"><el-select v-model="relationForm.relationType"><el-option label="产权人" value="OWNER" /><el-option label="共有产权人" value="CO_OWNER" /><el-option label="租户" value="TENANT" /><el-option label="居住人" value="OCCUPANT" /></el-select></el-form-item>
        <el-form-item label="生效日期"><el-date-picker v-model="relationForm.startDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="relationForm.reason" type="textarea" /></el-form-item>
        <el-checkbox v-model="relationForm.primaryRelation">设为主关系</el-checkbox>
      </el-form>
      <template #footer><el-button @click="relationVisible = false">取消</el-button><el-button type="primary" :loading="relationSubmitting" @click="createRelation">确认生效</el-button></template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="客户 CSV 导入校验" width="720px">
      <el-alert type="info" :closable="false" title="校验阶段不会写入客户数据；失败行必须修正后重新提交。" />
      <div class="import-actions"><el-button :icon="Download" @click="downloadTemplate">下载模板</el-button><el-upload action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="selectImport"><el-button :icon="Upload">选择 CSV</el-button></el-upload><span>{{ importFileName || '尚未选择文件' }}</span><el-button type="primary" :loading="importLoading" @click="validateImport">开始校验</el-button></div>
      <el-result v-if="importReport" :icon="importReport.readyToImport ? 'success' : 'warning'" :title="importReport.readyToImport ? '全部通过校验' : '存在校验错误'" :sub-title="`共 ${importReport.totalRows} 行，通过 ${importReport.validRows} 行，失败 ${importReport.invalidRows} 行`" />
      <el-table v-if="importReport?.rows.length" :data="importReport.rows" size="small" max-height="280"><el-table-column prop="rowNumber" label="CSV 行" width="90" /><el-table-column label="结果" width="90"><template #default="scope"><StatusTag :value="scope.row.valid ? 'COMPLETED' : 'FAILED'" /></template></el-table-column><el-table-column label="错误"><template #default="scope">{{ scope.row.errors.join('；') || '—' }}</template></el-table-column></el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.customer-workspace { display: flex; flex-direction: column; gap: 12px; }
.profile-body { min-height: 280px; }
.profile-actions, .import-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.profile-body p { margin: 4px 0; color: var(--muted); }
.customer-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.customer-form .full { grid-column: 1 / -1; }
.customer-form :deep(.el-select), .customer-form :deep(.el-date-editor), .el-dialog :deep(.el-select) { width: 100%; }
.import-actions > span { color: var(--muted); font-size: 12px; }
</style>
