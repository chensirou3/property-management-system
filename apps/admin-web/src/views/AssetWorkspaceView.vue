<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Download, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { http } from '../api/http'
import DataGrid, { type DataGridColumn } from '../components/shared/DataGrid.vue'
import QueryPanel from '../components/shared/QueryPanel.vue'
import TreeWorkspace from '../components/shared/TreeWorkspace.vue'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

interface GridNode { id: string; parentId?: string; code: string; name: string; status: string }
interface BuildingNode { id: string; gridId?: string; code: string; name: string; status: string }
interface UnitNode { id: string; buildingId: string; code: string; name: string; status: string }
interface AssetNode { id: string; gridId?: string; buildingId?: string; unitId?: string; assetType: string; code: string; name: string; enabled: boolean }
interface TreePayload { grids: GridNode[]; buildings: BuildingNode[]; units: UnitNode[]; assets: AssetNode[]; assetCount: number }
interface TreeItem { id: string; label: string; nodeType: string; assetId?: string; children?: TreeItem[] }
interface ValidationRow { rowNumber: number; valid: boolean; errors: string[]; warnings: string[] }
interface ValidationReport { totalRows: number; validRows: number; invalidRows: number; readyToImport: boolean; rows: ValidationRow[] }

const route = useRoute()
const auth = useAuthStore()
const keyword = ref('')
const page = ref(1)
const pageSize = ref(20)
const rows = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const treeLoading = ref(false)
const tree = ref<TreePayload>({ grids: [], buildings: [], units: [], assets: [], assetCount: 0 })
const profileVisible = ref(false)
const profileLoading = ref(false)
const profile = ref<any>(null)
const assetDialogVisible = ref(false)
const assetSubmitting = ref(false)
const editingAsset = ref(false)
const relationVisible = ref(false)
const relationSubmitting = ref(false)
const transferVisible = ref(false)
const transferSubmitting = ref(false)
const customerOptions = ref<any[]>([])
const importVisible = ref(false)
const importLoading = ref(false)
const importFileName = ref('')
const importRows = ref<Record<string, unknown>[]>([])
const importReport = ref<ValidationReport | null>(null)

const assetForm = reactive<Record<string, any>>({})
const relationForm = reactive({ customerId: '', relationType: 'OCCUPANT', primaryRelation: false, startDate: '', reason: '' })
const transferForm = reactive({ newOwnerCustomerId: '', effectiveDate: '', reason: '' })
const assetType = computed(() => route.path === '/archives/parking-spaces' ? 'PARKING' : 'ROOM')
const title = computed(() => assetType.value === 'ROOM' ? '房产信息' : '车位信息')
const communityId = computed(() => auth.currentProjectId)
const canWrite = computed(() => auth.hasPermission('property:write'))

const columns = computed<DataGridColumn[]>(() => assetType.value === 'ROOM' ? [
  { prop: 'code', label: '房屋编码', width: 130 },
  { prop: 'displayName', label: '房屋名称', minWidth: 180 },
  { prop: 'buildingName', label: '楼栋', minWidth: 130 },
  { prop: 'unitName', label: '单元', width: 110 },
  { prop: 'floorNo', label: '楼层', width: 80 },
  { prop: 'buildingArea', label: '建筑面积', width: 115, type: 'area' },
  { prop: 'primaryCustomerName', label: '当前客户', minWidth: 150, type: 'masked' },
  { prop: 'occupancyStatus', label: '入住状态', width: 110, type: 'status' },
  { prop: 'operationStatus', label: '运营状态', width: 110, type: 'status' },
] : [
  { prop: 'code', label: '车位编号', width: 140 },
  { prop: 'displayName', label: '车位名称', minWidth: 180 },
  { prop: 'floorNo', label: '停车区域', width: 120 },
  { prop: 'primaryCustomerName', label: '关联客户', minWidth: 150, type: 'masked' },
  { prop: 'activeRelationCount', label: '有效关系', width: 100 },
  { prop: 'occupancyStatus', label: '使用状态', width: 110, type: 'status' },
  { prop: 'operationStatus', label: '运营状态', width: 110, type: 'status' },
])

const availableUnits = computed(() => tree.value.units.filter((unit) => unit.buildingId === assetForm.building_id))
const treeData = computed<TreeItem[]>(() => {
  const assetsFor = (predicate: (asset: AssetNode) => boolean) => tree.value.assets.filter(predicate).map((asset) => ({
    id: `asset:${asset.id}`, label: `${asset.code} ${asset.name}`, nodeType: 'asset', assetId: asset.id,
  }))
  const buildingItems = tree.value.buildings.map((building) => {
    const units = tree.value.units.filter((unit) => unit.buildingId === building.id).map((unit) => ({
      id: `unit:${unit.id}`, label: `${unit.code} ${unit.name}`, nodeType: 'unit',
      children: assetsFor((asset) => asset.unitId === unit.id),
    }))
    const directAssets = assetsFor((asset) => asset.buildingId === building.id && !asset.unitId)
    return { id: `building:${building.id}`, label: `${building.code} ${building.name}`, nodeType: 'building', children: [...units, ...directAssets] }
  })
  const gridItems = tree.value.grids.map((grid) => ({
    id: `grid:${grid.id}`, label: `${grid.code} ${grid.name}`, nodeType: 'grid',
    children: buildingItems.filter((item) => tree.value.buildings.find((building) => `building:${building.id}` === item.id)?.gridId === grid.id),
  }))
  const ungroupedBuildings = buildingItems.filter((item) => !tree.value.buildings.find((building) => `building:${building.id}` === item.id)?.gridId)
  const looseAssets = assetsFor((asset) => !asset.buildingId)
  const children: TreeItem[] = [...gridItems, ...ungroupedBuildings]
  if (looseAssets.length) children.push({ id: 'loose', label: assetType.value === 'PARKING' ? '独立车位' : '未归属楼栋', nodeType: 'group', children: looseAssets })
  return [{ id: 'project', label: auth.currentProject?.name || '当前项目', nodeType: 'project', children }]
})

async function loadTree() {
  if (!communityId.value) return
  treeLoading.value = true
  try {
    const { data } = await http.get('/property/tree', { params: { communityId: communityId.value, assetType: assetType.value } })
    tree.value = data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '资产树加载失败')
  } finally {
    treeLoading.value = false
  }
}

async function load() {
  if (!communityId.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await http.get('/property/assets', { params: {
      communityId: communityId.value, assetType: assetType.value, keyword: keyword.value || undefined,
      page: page.value, size: pageSize.value,
    } })
    rows.value = data.items
    total.value = data.total
  } catch (error: any) {
    errorMessage.value = error.response?.data?.message || '资产列表加载失败'
  } finally {
    loading.value = false
  }
}

async function loadProfile(assetId: string) {
  profileVisible.value = true
  profileLoading.value = true
  try {
    const { data } = await http.get(`/property/assets/${assetId}`, { params: { communityId: communityId.value } })
    profile.value = data
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '资产档案加载失败')
    profileVisible.value = false
  } finally {
    profileLoading.value = false
  }
}

function onTreeNode(item: TreeItem) {
  if (item.assetId) void loadProfile(item.assetId)
}

function blankAsset() {
  Object.keys(assetForm).forEach((key) => delete assetForm[key])
  Object.assign(assetForm, {
    asset_type: assetType.value, code: '', display_name: '', grid_id: null, building_id: null, unit_id: null,
    floor_no: '', building_area: assetType.value === 'PARKING' ? 12.5 : 0,
    usable_area: assetType.value === 'PARKING' ? 12.5 : 0, occupancy_status: 'VACANT',
    operation_status: 'NORMAL', enabled: true, valid_from: new Date().toISOString().slice(0, 10), version: 0,
  })
}

function openCreate() {
  editingAsset.value = false
  blankAsset()
  assetDialogVisible.value = true
}

async function openEdit(row: Record<string, any>) {
  editingAsset.value = true
  await loadProfile(String(row.id))
  const asset = profile.value.asset
  blankAsset()
  Object.assign(assetForm, {
    id: asset.id, asset_type: asset.assetType, code: asset.code, display_name: asset.displayName,
    grid_id: asset.gridId, building_id: asset.buildingId, unit_id: asset.unitId, floor_no: asset.floorNo,
    building_area: asset.buildingArea, usable_area: asset.usableArea, occupancy_status: asset.occupancyStatus,
    operation_status: asset.operationStatus, enabled: asset.enabled, valid_from: asset.validFrom,
    valid_to: asset.validTo, version: asset.version,
  })
  profileVisible.value = false
  assetDialogVisible.value = true
}

function onBuildingChanged() {
  if (!availableUnits.value.some((unit) => unit.id === assetForm.unit_id)) assetForm.unit_id = null
  assetForm.grid_id = tree.value.buildings.find((building) => building.id === assetForm.building_id)?.gridId || null
}

async function saveAsset() {
  if (!assetForm.code || !assetForm.display_name) return ElMessage.warning('请填写资产编码和名称')
  if (assetType.value === 'ROOM' && !assetForm.building_id) return ElMessage.warning('房屋必须选择楼栋')
  if (Number(assetForm.usable_area) > Number(assetForm.building_area)) return ElMessage.warning('使用面积不能大于建筑面积')
  assetSubmitting.value = true
  try {
    const body = { ...assetForm }
    delete body.id
    delete body.version
    if (editingAsset.value) {
      await http.put(`/data/assets/${assetForm.id}`, body, { params: { communityId: communityId.value, version: assetForm.version } })
    } else {
      await http.post('/data/assets', body, { params: { communityId: communityId.value } })
    }
    ElMessage.success(editingAsset.value ? '资产档案已更新' : '资产档案已创建')
    assetDialogVisible.value = false
    await Promise.all([load(), loadTree()])
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '资产档案保存失败')
  } finally {
    assetSubmitting.value = false
  }
}

async function searchCustomers(query = '') {
  if (!communityId.value) return
  const { data } = await http.get('/property/customers', { params: { communityId: communityId.value, keyword: query || undefined, status: 'ACTIVE', page: 1, size: 100 } })
  customerOptions.value = data.items
}

function openRelation() {
  Object.assign(relationForm, { customerId: '', relationType: assetType.value === 'ROOM' ? 'OCCUPANT' : 'OWNER', primaryRelation: false, startDate: new Date().toISOString().slice(0, 10), reason: '' })
  relationVisible.value = true
  void searchCustomers()
}

async function createRelation() {
  if (!relationForm.customerId || !relationForm.startDate) return ElMessage.warning('请选择客户和生效日期')
  relationSubmitting.value = true
  try {
    await http.post('/property/relations', { communityId: communityId.value, assetId: profile.value.asset.id, ...relationForm }, {
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    ElMessage.success('客户资产关系已生效并写入历史')
    relationVisible.value = false
    await Promise.all([loadProfile(profile.value.asset.id), load()])
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
    await Promise.all([loadProfile(profile.value.asset.id), load()])
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error.response?.data?.message || '结束关系失败')
  }
}

function openTransfer() {
  Object.assign(transferForm, { newOwnerCustomerId: '', effectiveDate: new Date(Date.now() + 86400000).toISOString().slice(0, 10), reason: '' })
  transferVisible.value = true
  void searchCustomers()
}

async function transferOwnership() {
  if (!transferForm.newOwnerCustomerId || !transferForm.effectiveDate || !transferForm.reason.trim()) return ElMessage.warning('请完整填写新产权人、生效日和原因')
  transferSubmitting.value = true
  try {
    await http.post(`/property/assets/${profile.value.asset.id}:transfer`, {
      communityId: communityId.value, ...transferForm, expectedAssetVersion: profile.value.asset.version,
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success('产权变更已完成，原产权关系已转入历史')
    transferVisible.value = false
    await Promise.all([loadProfile(profile.value.asset.id), load()])
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '产权变更失败')
  } finally {
    transferSubmitting.value = false
  }
}

async function archiveAsset(row: Record<string, any>) {
  await ElMessageBox.confirm('停用前将检查有效客户、车辆、仪表和未结清账单引用。是否继续？', `停用${title.value}`, { type: 'warning' })
  try {
    await http.delete(`/data/assets/${row.id}`, { params: { communityId: communityId.value, version: row.version } })
    ElMessage.success('资产已停用')
    await Promise.all([load(), loadTree()])
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '资产仍被业务数据引用，无法停用')
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
  importRows.value = lines.map((line) => Object.fromEntries(headers.map((header, index) => [header, parseCsvLine(line)[index] ?? ''])))
  importReport.value = null
}

async function validateImport() {
  if (!importRows.value.length) return ElMessage.warning('请选择包含数据行的 CSV 文件')
  importLoading.value = true
  try {
    const { data } = await http.post('/property/imports:validate', { communityId: communityId.value, resource: 'ASSET', rows: importRows.value })
    importReport.value = data
    if (data.readyToImport) ElMessage.success('全部数据通过校验，可进入后续导入审批')
    else ElMessage.warning('存在校验错误，本次不会写入业务数据')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '导入校验失败')
  } finally {
    importLoading.value = false
  }
}

async function downloadTemplate() {
  const response = await http.get('/property/imports/template', { params: { resource: 'ASSET' }, responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `${assetType.value.toLowerCase()}-import-template.csv`
  link.click()
  URL.revokeObjectURL(url)
}

function openImport() {
  importRows.value = []
  importReport.value = null
  importFileName.value = ''
  importVisible.value = true
}

function applyQuery() { page.value = 1; void load() }
function reset() { keyword.value = ''; page.value = 1; void load() }
function saveFilter() { localStorage.setItem(`pms-filter:${route.path}`, keyword.value); ElMessage.success('筛选条件已保存') }
function restoreFilter() { keyword.value = localStorage.getItem(`pms-filter:${route.path}`) || ''; applyQuery() }

watch([communityId, assetType], () => { page.value = 1; void Promise.all([load(), loadTree()]) })
watch([page, pageSize], load)
onMounted(() => void Promise.all([load(), loadTree()]))
</script>

<template>
  <section class="asset-workspace">
    <el-alert type="info" :closable="false" show-icon
      :title="`${title}采用统一资产主档；客户关系、车辆、仪表与产权历史分别记录，敏感信息仅展示脱敏值。`" />
    <QueryPanel :loading="loading" @query="applyQuery" @reset="reset" @save="saveFilter" @restore="restoreFilter">
      <el-form-item label="关键字">
        <el-input v-model="keyword" clearable :placeholder="assetType === 'ROOM' ? '房号/房屋名称/楼栋' : '车位编号/名称'" @keyup.enter="applyQuery">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </el-form-item>
    </QueryPanel>

    <TreeWorkspace :tree-title="`${title}资产树`" tree-width="280px">
      <template #tree-actions><el-button text :icon="Refresh" :loading="treeLoading" @click="loadTree" /></template>
      <template #tree>
        <el-tree v-loading="treeLoading" :data="treeData" node-key="id" :props="{ label: 'label', children: 'children' }"
          default-expand-all highlight-current @node-click="onTreeNode" />
      </template>
      <DataGrid v-model:page="page" v-model:page-size="pageSize" :rows="rows" :columns="columns"
        :loading="loading" :error-message="errorMessage" :total="total" :storage-key="route.path"
        height="calc(100vh - 390px)" @reload="load">
        <template #toolbar>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增{{ title }}</el-button>
          <el-button v-if="canWrite" :icon="Upload" @click="openImport">导入校验</el-button>
        </template>
        <template #operations="{ row }">
          <el-button link type="primary" @click="loadProfile(String(row.id))">档案与关系</el-button>
          <el-button v-if="canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canWrite" link type="danger" @click="archiveAsset(row)">停用</el-button>
        </template>
      </DataGrid>
    </TreeWorkspace>

    <el-drawer v-model="profileVisible" :title="`${title}完整档案`" size="780px">
      <div v-loading="profileLoading" class="profile-body" v-if="profile">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="编码">{{ profile.asset.code }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ profile.asset.displayName }}</el-descriptions-item>
          <el-descriptions-item label="层级">{{ [profile.asset.gridName, profile.asset.buildingName, profile.asset.unitName].filter(Boolean).join(' / ') || '独立资产' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :value="profile.asset.operationStatus" /></el-descriptions-item>
          <el-descriptions-item label="建筑面积">{{ profile.asset.buildingArea }} ㎡</el-descriptions-item>
          <el-descriptions-item label="使用面积">{{ profile.asset.usableArea }} ㎡</el-descriptions-item>
        </el-descriptions>
        <div class="profile-actions" v-if="canWrite">
          <el-button type="primary" @click="openRelation">登记客户关系</el-button>
          <el-button v-if="profile.relations.some((item: any) => item.status === 'ACTIVE' && ['OWNER','CO_OWNER'].includes(item.relationType))"
            type="warning" @click="openTransfer">产权变更</el-button>
        </div>
        <el-tabs>
          <el-tab-pane label="客户关系">
            <el-table :data="profile.relations" size="small" border>
              <el-table-column prop="customerName" label="客户" min-width="150" />
              <el-table-column prop="relationType" label="关系" width="110" />
              <el-table-column prop="startDate" label="生效日" width="115" />
              <el-table-column prop="endDate" label="结束日" width="115" />
              <el-table-column prop="status" label="状态" width="95"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column>
              <el-table-column v-if="canWrite" label="操作" width="90"><template #default="scope"><el-button v-if="scope.row.status === 'ACTIVE'" link type="danger" @click="endRelation(scope.row)">结束</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="车辆与车位"><el-table :data="profile.vehicles" size="small" border><el-table-column prop="plateNoMasked" label="脱敏车牌" /><el-table-column prop="parkingName" label="车位" /><el-table-column prop="status" label="状态" /></el-table></el-tab-pane>
          <el-tab-pane label="关联仪表"><el-table :data="profile.meters" size="small" border><el-table-column prop="meterNo" label="表号" /><el-table-column prop="meterType" label="类型" /><el-table-column prop="status" label="状态" /></el-table></el-tab-pane>
          <el-tab-pane label="关系历史">
            <el-timeline><el-timeline-item v-for="event in profile.timeline" :key="event.id" :timestamp="event.effectiveDate" placement="top"><strong>{{ event.eventType }}</strong><p>{{ event.customerName || '—' }} · {{ event.reason || '未填写原因' }}</p></el-timeline-item></el-timeline>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog v-model="assetDialogVisible" :title="`${editingAsset ? '编辑' : '新增'}${title}`" width="680px">
      <el-form label-position="top" class="asset-form">
        <el-form-item label="编码" required><el-input v-model="assetForm.code" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="assetForm.display_name" /></el-form-item>
        <el-form-item label="楼栋" :required="assetType === 'ROOM'"><el-select v-model="assetForm.building_id" clearable filterable @change="onBuildingChanged"><el-option v-for="item in tree.buildings" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="单元"><el-select v-model="assetForm.unit_id" clearable><el-option v-for="item in availableUnits" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="楼层/区域"><el-input v-model="assetForm.floor_no" /></el-form-item>
        <el-form-item label="建筑面积" required><el-input-number v-model="assetForm.building_area" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="使用面积" required><el-input-number v-model="assetForm.usable_area" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="使用状态"><el-select v-model="assetForm.occupancy_status"><el-option label="已使用/入住" value="OCCUPIED" /><el-option label="空置" value="VACANT" /></el-select></el-form-item>
        <el-form-item label="运营状态"><el-select v-model="assetForm.operation_status"><el-option label="正常" value="NORMAL" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="assetDialogVisible = false">取消</el-button><el-button type="primary" :loading="assetSubmitting" @click="saveAsset">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="relationVisible" title="登记客户资产关系" width="520px">
      <el-form label-position="top">
        <el-form-item label="客户" required><el-select v-model="relationForm.customerId" filterable remote :remote-method="searchCustomers"><el-option v-for="item in customerOptions" :key="item.id" :label="`${item.customerNo} ${item.displayName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="关系类型"><el-select v-model="relationForm.relationType"><el-option label="产权人" value="OWNER" /><el-option label="共有产权人" value="CO_OWNER" /><el-option label="租户" value="TENANT" /><el-option label="居住人" value="OCCUPANT" /></el-select></el-form-item>
        <el-form-item label="生效日期"><el-date-picker v-model="relationForm.startDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="变更原因"><el-input v-model="relationForm.reason" type="textarea" /></el-form-item>
        <el-checkbox v-model="relationForm.primaryRelation">设为主关系</el-checkbox>
      </el-form>
      <template #footer><el-button @click="relationVisible = false">取消</el-button><el-button type="primary" :loading="relationSubmitting" @click="createRelation">确认生效</el-button></template>
    </el-dialog>

    <el-dialog v-model="transferVisible" title="产权变更" width="520px">
      <el-alert type="warning" :closable="false" title="生效后，当前产权人和共有产权人关系将于生效日前一天结束。" />
      <el-form label-position="top">
        <el-form-item label="新产权人" required><el-select v-model="transferForm.newOwnerCustomerId" filterable remote :remote-method="searchCustomers"><el-option v-for="item in customerOptions" :key="item.id" :label="`${item.customerNo} ${item.displayName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="生效日期" required><el-date-picker v-model="transferForm.effectiveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="变更原因" required><el-input v-model="transferForm.reason" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="transferVisible = false">取消</el-button><el-button type="warning" :loading="transferSubmitting" @click="transferOwnership">确认变更</el-button></template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="资产 CSV 导入校验" width="720px">
      <el-alert type="info" :closable="false" title="本步骤只校验，不写入业务数据；全部通过后才可进入后续导入审批。" />
      <div class="import-actions">
        <el-button :icon="Download" @click="downloadTemplate">下载模板</el-button>
        <el-upload action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="selectImport"><el-button :icon="Upload">选择 CSV</el-button></el-upload>
        <span>{{ importFileName || '尚未选择文件' }}</span>
        <el-button type="primary" :loading="importLoading" @click="validateImport">开始校验</el-button>
      </div>
      <el-result v-if="importReport" :icon="importReport.readyToImport ? 'success' : 'warning'" :title="importReport.readyToImport ? '全部通过校验' : '存在校验错误'" :sub-title="`共 ${importReport.totalRows} 行，通过 ${importReport.validRows} 行，失败 ${importReport.invalidRows} 行`" />
      <el-table v-if="importReport?.rows.length" :data="importReport.rows" size="small" max-height="280"><el-table-column prop="rowNumber" label="CSV 行" width="90" /><el-table-column label="结果" width="90"><template #default="scope"><StatusTag :value="scope.row.valid ? 'COMPLETED' : 'FAILED'" /></template></el-table-column><el-table-column label="错误"><template #default="scope">{{ scope.row.errors.join('；') || '—' }}</template></el-table-column></el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.asset-workspace { display: flex; flex-direction: column; gap: 12px; }
.profile-body { min-height: 260px; }
.profile-actions, .import-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.profile-body :deep(.el-tabs) { margin-top: 14px; }
.profile-body p { margin: 4px 0; color: var(--muted); }
.asset-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.asset-form :deep(.el-select), .asset-form :deep(.el-input-number), .el-dialog :deep(.el-select) { width: 100%; }
.import-actions > span { color: var(--muted); font-size: 12px; }
</style>
