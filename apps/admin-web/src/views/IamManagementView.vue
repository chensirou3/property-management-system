<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Key, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

type Mode = 'enterprises' | 'organizations' | 'roles' | 'positions' | 'employees' | 'accounts'
type Row = Record<string, any>
interface TreeNode { id: string; label: string; children: TreeNode[] }

const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const keyword = ref('')
const status = ref('ACTIVE')
const drawerVisible = ref(false)
const editing = ref<Row | null>(null)
const selectedOrganizationId = ref('')

const enterprises = ref<Row[]>([])
const organizations = ref<Row[]>([])
const positions = ref<Row[]>([])
const employees = ref<Row[]>([])
const roles = ref<Row[]>([])
const permissions = ref<Row[]>([])
const accounts = ref<Row[]>([])
const projects = ref<Row[]>([])

const form = reactive<Row>({})
const mode = computed(() => (route.path.split('/').at(-1) || 'enterprises') as Mode)
const canWrite = computed(() => auth.hasPermission('iam:write'))

const modeMeta: Record<Mode, { title: string; singular: string; endpoint: string; description: string }> = {
  enterprises: { title: '企业管理', singular: '企业', endpoint: 'enterprises', description: '企业是组织、项目、岗位和人员的最高数据边界。' },
  organizations: { title: '组织管理', singular: '组织', endpoint: 'organizations', description: '组织树可关联项目；上下级循环和跨企业关联会被后端拒绝。' },
  roles: { title: '角色管理', singular: '角色', endpoint: 'roles', description: '角色决定操作能力，项目范围必须在账号授权中单独配置。' },
  positions: { title: '岗位管理', singular: '岗位', endpoint: 'positions', description: '岗位属于具体组织，用于表达人员职责，不直接替代系统角色。' },
  employees: { title: '人员管理', singular: '人员', endpoint: 'employees', description: '人员档案与登录账号分离，允许未开通账号的在职人员存在。' },
  accounts: { title: '账号与项目权限', singular: '账号', endpoint: 'users', description: '账号必须同时分配角色和项目范围；前端隐藏菜单不构成安全控制。' },
}

const currentMeta = computed(() => modeMeta[mode.value])
const sourceRows = computed(() => ({
  enterprises: enterprises.value,
  organizations: organizations.value,
  roles: roles.value,
  positions: positions.value,
  employees: employees.value,
  accounts: accounts.value,
})[mode.value])

const filteredRows = computed(() => sourceRows.value.filter((row) => {
  if (mode.value === 'organizations' && selectedOrganizationId.value && row.id !== selectedOrganizationId.value) return false
  const rowStatus = String(row.status ?? row.employmentStatus ?? (row.enabled ? 'ACTIVE' : 'INACTIVE'))
  if (status.value && rowStatus !== status.value) return false
  if (!keyword.value.trim()) return true
  const needle = keyword.value.trim().toLowerCase()
  return Object.values(row).some((value) => typeof value === 'string' && value.toLowerCase().includes(needle))
}))

const activeCount = computed(() => sourceRows.value.filter((row) =>
  (row.status ?? row.employmentStatus ?? (row.enabled ? 'ACTIVE' : 'INACTIVE')) === 'ACTIVE').length)

const organizationTree = computed<TreeNode[]>(() => {
  const visibleOrganizations = organizations.value.filter((item) => !status.value || item.status === status.value)
  const nodes = new Map<string, TreeNode>()
  visibleOrganizations.forEach((item) => nodes.set(item.id, { id: item.id, label: item.name, children: [] }))
  const roots: TreeNode[] = []
  visibleOrganizations.forEach((item) => {
    const node = nodes.get(item.id)!
    const parent = item.parentId ? nodes.get(item.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
})

function nameOf(items: Row[], id?: string | null, fallback = '—') {
  if (!id) return fallback
  return items.find((item) => item.id === id)?.name || items.find((item) => item.id === id)?.displayName || id
}

function roleNames(ids: string[]) {
  return ids?.map((id) => nameOf(roles.value, id)).join('、') || '—'
}

function projectNames(ids: string[]) {
  return ids?.map((id) => nameOf(projects.value, id)).join('、') || '未授权项目'
}

function permissionNames(ids: string[]) {
  return ids?.map((id) => permissions.value.find((item) => item.id === id)?.name || id).join('、') || '无权限'
}

function statusType(value: string | boolean) {
  if (value === true || value === 'ACTIVE') return 'success'
  if (value === 'LEFT') return 'warning'
  return 'info'
}

async function loadAll() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [enterpriseRes, organizationRes, positionRes, employeeRes, roleRes, permissionRes, accountRes, projectRes] = await Promise.all([
      http.get('/iam/enterprises'), http.get('/iam/organizations'), http.get('/iam/positions'),
      http.get('/iam/employees'), http.get('/iam/roles'), http.get('/iam/permissions'),
      http.get('/iam/users'), http.get('/iam/projects'),
    ])
    enterprises.value = enterpriseRes.data
    organizations.value = organizationRes.data
    positions.value = positionRes.data
    employees.value = employeeRes.data
    roles.value = roleRes.data
    permissions.value = permissionRes.data
    accounts.value = accountRes.data
    projects.value = projectRes.data
  } catch (error: any) {
    errorMessage.value = error.response?.data?.message || '企业与权限数据加载失败'
  } finally {
    loading.value = false
  }
}

function clearForm() {
  Object.keys(form).forEach((key) => delete form[key])
  Object.assign(form, {
    enterpriseId: enterprises.value[0]?.id || '',
    parentId: '', communityId: '', organizationType: 'DEPARTMENT', sortOrder: 10,
    organizationId: organizations.value[0]?.id || '', positionId: '', description: '',
    status: 'ACTIVE', employmentStatus: 'ACTIVE', enabled: true,
    passwordChangeRequired: true, roleIds: [], projectIds: [], permissionIds: [],
  })
}

function openCreate() {
  editing.value = null
  clearForm()
  drawerVisible.value = true
}

function openEdit(row: Row) {
  editing.value = row
  clearForm()
  Object.assign(form, JSON.parse(JSON.stringify(row)))
  drawerVisible.value = true
}

function requireFields(fields: Array<[string, string]>) {
  for (const [key, label] of fields) {
    const value = form[key]
    if (value === undefined || value === null || value === '' || (Array.isArray(value) && value.length === 0)) {
      ElMessage.warning(`请填写或选择${label}`)
      return false
    }
  }
  return true
}

async function submit() {
  const isEdit = Boolean(editing.value)
  let body: Row
  switch (mode.value) {
    case 'enterprises':
      if (!requireFields(isEdit ? [['name', '企业名称']] : [['code', '企业编码'], ['name', '企业名称']])) return
      body = isEdit
        ? { name: form.name, status: form.status, expectedVersion: editing.value!.version }
        : { code: form.code, name: form.name }
      break
    case 'organizations':
      if (!requireFields([['enterpriseId', '所属企业'], ['code', '组织编码'], ['name', '组织名称'], ['organizationType', '组织类型']])) return
      body = isEdit
        ? { parentId: form.parentId || null, communityId: form.communityId || null, name: form.name,
            organizationType: form.organizationType, sortOrder: Number(form.sortOrder), status: form.status,
            expectedVersion: editing.value!.version }
        : { enterpriseId: form.enterpriseId, parentId: form.parentId || null, communityId: form.communityId || null,
            code: form.code, name: form.name, organizationType: form.organizationType, sortOrder: Number(form.sortOrder) }
      break
    case 'positions':
      if (!requireFields([['enterpriseId', '所属企业'], ['organizationId', '所属组织'], ['code', '岗位编码'], ['name', '岗位名称']])) return
      body = isEdit
        ? { organizationId: form.organizationId, name: form.name, description: form.description || null,
            status: form.status, expectedVersion: editing.value!.version }
        : { enterpriseId: form.enterpriseId, organizationId: form.organizationId, code: form.code,
            name: form.name, description: form.description || null }
      break
    case 'employees':
      if (!requireFields([['enterpriseId', '所属企业'], ['organizationId', '所属组织'], ['employeeNo', '员工编号'], ['displayName', '姓名']])) return
      body = isEdit
        ? { organizationId: form.organizationId, positionId: form.positionId || null, displayName: form.displayName,
            mobileMasked: form.mobileMasked || null, employmentStatus: form.employmentStatus,
            hireDate: form.hireDate || null, leaveDate: form.leaveDate || null, expectedVersion: editing.value!.version }
        : { enterpriseId: form.enterpriseId, organizationId: form.organizationId, positionId: form.positionId || null,
            employeeNo: form.employeeNo, displayName: form.displayName, mobileMasked: form.mobileMasked || null,
            hireDate: form.hireDate || null }
      break
    case 'roles':
      if (!requireFields([['code', '角色编码'], ['name', '角色名称'], ['permissionIds', '权限']])) return
      body = isEdit
        ? { name: form.name, description: form.description || null, enabled: form.enabled,
            permissionIds: form.permissionIds, expectedVersion: editing.value!.version }
        : { enterpriseId: form.enterpriseId || null, code: form.code, name: form.name,
            description: form.description || null, permissionIds: form.permissionIds }
      break
    case 'accounts':
      if (!requireFields(isEdit
        ? [['displayName', '显示名称'], ['roleIds', '角色']]
        : [['username', '账号'], ['password', '初始密码'], ['displayName', '显示名称'], ['roleIds', '角色']])) return
      if (!isEdit && String(form.password).length < 12) {
        ElMessage.warning('初始密码至少需要 12 个字符')
        return
      }
      body = isEdit
        ? { displayName: form.displayName, employeeId: form.employeeId || null, enabled: form.enabled,
            passwordChangeRequired: form.passwordChangeRequired, roleIds: form.roleIds,
            projectIds: form.projectIds || [], expectedVersion: editing.value!.version }
        : { username: form.username, password: form.password, displayName: form.displayName,
            employeeId: form.employeeId || null, enabled: form.enabled, roleIds: form.roleIds,
            projectIds: form.projectIds || [] }
      break
  }
  submitting.value = true
  try {
    const endpoint = `/iam/${currentMeta.value.endpoint}`
    if (isEdit) await http.put(`${endpoint}/${editing.value!.id}`, body)
    else await http.post(endpoint, body)
    ElMessage.success(`${currentMeta.value.singular}${isEdit ? '修改' : '新增'}成功，授权变更已记录审计`)
    drawerVisible.value = false
    await loadAll()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function resetPassword(row: Row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入不少于 12 个字符的临时密码。密码不会写入日志。',
      `重置 ${row.username} 的密码`, { inputType: 'password', inputPattern: /^.{12,200}$/,
        inputErrorMessage: '密码长度必须为 12–200 个字符', confirmButtonText: '重置密码' })
    await http.put(`/iam/users/${row.id}/password`, {
      password: value, requireChange: true, expectedVersion: row.version,
    })
    ElMessage.success('密码已重置；账号下次登录需修改密码')
    await loadAll()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error.response?.data?.message || '密码重置失败')
  }
}

function resetFilters() {
  keyword.value = ''
  status.value = ''
  selectedOrganizationId.value = ''
}

watch(() => route.path, () => {
  keyword.value = ''
  status.value = 'ACTIVE'
  selectedOrganizationId.value = ''
  void loadAll()
}, { immediate: true })
</script>

<template>
  <section class="iam-page">
    <div class="iam-summary-grid">
      <div class="iam-summary-card"><span>当前模块</span><strong>{{ currentMeta.title }}</strong></div>
      <div class="iam-summary-card"><span>当前结果</span><strong>{{ filteredRows.length }}</strong></div>
      <div class="iam-summary-card"><span>启用/在职</span><strong>{{ activeCount }}</strong></div>
      <div class="iam-summary-card warning"><span>数据边界</span><strong>角色 + 项目范围</strong></div>
    </div>

    <el-alert :title="currentMeta.description" type="info" :closable="false" show-icon class="page-note" />

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="keyword" clearable :prefix-icon="Search" :placeholder="`搜索${currentMeta.title}`" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部状态" style="width: 150px">
            <el-option label="启用/在职" value="ACTIVE" />
            <el-option label="停用" value="INACTIVE" />
            <el-option v-if="mode === 'employees'" label="已离职" value="LEFT" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="iam-workspace" :class="{ 'with-tree': mode === 'organizations' }">
      <el-card v-if="mode === 'organizations'" shadow="never" class="iam-tree-card">
        <template #header>组织树</template>
        <el-button link type="primary" @click="selectedOrganizationId = ''">显示全部组织</el-button>
        <el-tree :data="organizationTree" node-key="id" default-expand-all highlight-current
          :expand-on-click-node="false" @node-click="(node: TreeNode) => selectedOrganizationId = node.id" />
      </el-card>

      <el-card shadow="never" class="table-card iam-table-card">
        <div class="table-toolbar">
          <div>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增{{ currentMeta.singular }}</el-button>
          </div>
          <span class="record-summary">显示 <strong>{{ filteredRows.length }}</strong> 条</span>
        </div>
        <el-alert v-if="errorMessage" type="error" :closable="false" show-icon :title="errorMessage" />

        <el-table v-loading="loading" :data="filteredRows" row-key="id" stripe height="calc(100vh - 445px)" empty-text="暂无数据">
          <template v-if="mode === 'enterprises'">
            <el-table-column prop="code" label="企业编码" width="180" />
            <el-table-column prop="name" label="企业名称" min-width="220" />
            <el-table-column prop="status" label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
          </template>
          <template v-else-if="mode === 'organizations'">
            <el-table-column prop="code" label="组织编码" width="150" />
            <el-table-column prop="name" label="组织名称" min-width="180" />
            <el-table-column label="上级组织" min-width="160"><template #default="scope">{{ nameOf(organizations, scope.row.parentId, '根组织') }}</template></el-table-column>
            <el-table-column label="关联项目" min-width="200"><template #default="scope">{{ nameOf(projects, scope.row.communityId) }}</template></el-table-column>
            <el-table-column prop="organizationType" label="类型" width="120" />
            <el-table-column prop="status" label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
          </template>
          <template v-else-if="mode === 'positions'">
            <el-table-column prop="code" label="岗位编码" width="170" />
            <el-table-column prop="name" label="岗位名称" min-width="180" />
            <el-table-column label="所属组织" min-width="180"><template #default="scope">{{ nameOf(organizations, scope.row.organizationId) }}</template></el-table-column>
            <el-table-column prop="description" label="职责说明" min-width="240" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
          </template>
          <template v-else-if="mode === 'employees'">
            <el-table-column prop="employeeNo" label="员工编号" width="160" />
            <el-table-column prop="displayName" label="姓名" min-width="150" />
            <el-table-column label="所属组织" min-width="180"><template #default="scope">{{ nameOf(organizations, scope.row.organizationId) }}</template></el-table-column>
            <el-table-column label="岗位" min-width="150"><template #default="scope">{{ nameOf(positions, scope.row.positionId) }}</template></el-table-column>
            <el-table-column prop="mobileMasked" label="脱敏手机" width="140" />
            <el-table-column prop="employmentStatus" label="在职状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.employmentStatus)">{{ scope.row.employmentStatus }}</el-tag></template></el-table-column>
          </template>
          <template v-else-if="mode === 'roles'">
            <el-table-column prop="code" label="角色编码" width="180" />
            <el-table-column prop="name" label="角色名称" min-width="160" />
            <el-table-column label="权限" min-width="360" show-overflow-tooltip><template #default="scope">{{ permissionNames(scope.row.permissionIds) }}</template></el-table-column>
            <el-table-column prop="enabled" label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.enabled)">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
          </template>
          <template v-else>
            <el-table-column prop="username" label="登录账号" width="180" />
            <el-table-column prop="displayName" label="显示名称" min-width="150" />
            <el-table-column label="关联人员" min-width="150"><template #default="scope">{{ nameOf(employees, scope.row.employeeId) }}</template></el-table-column>
            <el-table-column label="角色" min-width="220" show-overflow-tooltip><template #default="scope">{{ roleNames(scope.row.roleIds) }}</template></el-table-column>
            <el-table-column label="项目范围" min-width="260" show-overflow-tooltip><template #default="scope">{{ projectNames(scope.row.projectIds) }}</template></el-table-column>
            <el-table-column prop="enabled" label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.enabled)">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
          </template>
          <el-table-column v-if="canWrite" label="操作" width="mode === 'accounts' ? 180 : 100" fixed="right">
            <template #default="scope">
              <el-button link type="primary" :icon="Edit" @click="openEdit(scope.row)">编辑</el-button>
              <el-button v-if="mode === 'accounts'" link type="warning" :icon="Key" @click="resetPassword(scope.row)">密码</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-drawer v-model="drawerVisible" :title="`${editing ? '编辑' : '新增'}${currentMeta.singular}`" size="560px">
      <el-form label-position="top" class="drawer-form">
        <template v-if="mode === 'enterprises'">
          <el-form-item v-if="!editing" label="企业编码" required><el-input v-model="form.code" placeholder="例如 PROPERTY_GROUP" /></el-form-item>
          <el-form-item label="企业名称" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item v-if="editing" label="状态"><el-switch v-model="form.status" active-value="ACTIVE" inactive-value="INACTIVE" /></el-form-item>
        </template>

        <template v-else-if="mode === 'organizations'">
          <el-form-item label="所属企业" required><el-select v-model="form.enterpriseId" :disabled="Boolean(editing)" filterable><el-option v-for="item in enterprises" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="!editing" label="组织编码" required><el-input v-model="form.code" placeholder="仅限字母、数字、下划线和连字符" /></el-form-item>
          <el-form-item label="组织名称" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="组织类型" required><el-select v-model="form.organizationType"><el-option label="企业" value="COMPANY" /><el-option label="项目部" value="PROJECT" /><el-option label="部门" value="DEPARTMENT" /></el-select></el-form-item>
          <el-form-item label="上级组织"><el-select v-model="form.parentId" clearable filterable><el-option v-for="item in organizations.filter((o) => o.id !== editing?.id)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="关联项目"><el-select v-model="form.communityId" clearable filterable><el-option v-for="item in projects.filter((p) => p.enterpriseId === form.enterpriseId)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="9999" /></el-form-item>
          <el-form-item v-if="editing" label="状态"><el-switch v-model="form.status" active-value="ACTIVE" inactive-value="INACTIVE" /></el-form-item>
        </template>

        <template v-else-if="mode === 'positions'">
          <el-form-item label="所属企业" required><el-select v-model="form.enterpriseId" :disabled="Boolean(editing)"><el-option v-for="item in enterprises" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="所属组织" required><el-select v-model="form.organizationId" filterable><el-option v-for="item in organizations.filter((o) => o.enterpriseId === form.enterpriseId)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="!editing" label="岗位编码" required><el-input v-model="form.code" /></el-form-item>
          <el-form-item label="岗位名称" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="职责说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
          <el-form-item v-if="editing" label="状态"><el-switch v-model="form.status" active-value="ACTIVE" inactive-value="INACTIVE" /></el-form-item>
        </template>

        <template v-else-if="mode === 'employees'">
          <el-form-item label="所属企业" required><el-select v-model="form.enterpriseId" :disabled="Boolean(editing)"><el-option v-for="item in enterprises" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="所属组织" required><el-select v-model="form.organizationId" filterable><el-option v-for="item in organizations.filter((o) => o.enterpriseId === form.enterpriseId)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="岗位"><el-select v-model="form.positionId" clearable filterable><el-option v-for="item in positions.filter((p) => p.organizationId === form.organizationId)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="!editing" label="员工编号" required><el-input v-model="form.employeeNo" /></el-form-item>
          <el-form-item label="姓名" required><el-input v-model="form.displayName" /></el-form-item>
          <el-form-item label="脱敏手机"><el-input v-model="form.mobileMasked" placeholder="例如 138****0001" /></el-form-item>
          <el-form-item label="入职日期"><el-date-picker v-model="form.hireDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <template v-if="editing">
            <el-form-item label="在职状态"><el-select v-model="form.employmentStatus"><el-option label="在职" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /><el-option label="离职" value="LEFT" /></el-select></el-form-item>
            <el-form-item v-if="form.employmentStatus === 'LEFT'" label="离职日期" required><el-date-picker v-model="form.leaveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          </template>
        </template>

        <template v-else-if="mode === 'roles'">
          <el-form-item label="所属企业"><el-select v-model="form.enterpriseId" :disabled="Boolean(editing)" clearable><el-option v-for="item in enterprises" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="!editing" label="角色编码" required><el-input v-model="form.code" /></el-form-item>
          <el-form-item label="角色名称" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="角色说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="权限" required><el-select v-model="form.permissionIds" multiple filterable collapse-tags :max-collapse-tags="4"><el-option v-for="item in permissions" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="editing" label="状态"><el-switch v-model="form.enabled" /></el-form-item>
        </template>

        <template v-else>
          <el-form-item v-if="!editing" label="登录账号" required><el-input v-model="form.username" /></el-form-item>
          <el-form-item v-if="!editing" label="初始密码" required><el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少 12 个字符" /></el-form-item>
          <el-form-item label="显示名称" required><el-input v-model="form.displayName" /></el-form-item>
          <el-form-item label="关联人员"><el-select v-model="form.employeeId" clearable filterable><el-option v-for="item in employees" :key="item.id" :label="`${item.displayName}（${item.employeeNo}）`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="角色" required><el-select v-model="form.roleIds" multiple filterable><el-option v-for="item in roles.filter((r) => r.enabled)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="项目范围"><el-select v-model="form.projectIds" multiple filterable><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="账号启用"><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item v-if="editing" label="下次登录必须修改密码"><el-switch v-model="form.passwordChangeRequired" /></el-form-item>
          <el-alert type="warning" :closable="false" title="平台管理员角色可跨项目；普通角色只能访问这里明确授权的项目。" />
        </template>
      </el-form>
      <template #footer><el-button @click="drawerVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">保存</el-button></template>
    </el-drawer>
  </section>
</template>
