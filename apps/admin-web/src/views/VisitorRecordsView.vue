<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { Download, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useTaskStore } from '../stores/tasks'

type Visitor = Record<string, any>

const auth = useAuthStore()
const tasks = useTaskStore()
const loading = ref(false)
const error = ref('')
const rows = ref<Visitor[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const visitStatus = ref('')
const dateRange = ref<string[]>([])
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ visitorNameMasked: '合成访客丁**', visitorMobileMasked: '136****0004', hostNameMasked: '合成住户丁**', assetName: '4号楼-1单元-0401', scheduledAt: dayjs().add(1, 'day').format('YYYY-MM-DD HH:mm:ss') })

const canWrite = computed(() => auth.hasPermission('visitor:write'))
const canImport = computed(() => auth.hasPermission('visitor:import'))
const canExport = computed(() => auth.hasPermission('visitor:export-sensitive'))
const statusLabels: Record<string, string> = { REGISTERED: '已登记', CHECKED_IN: '已进入', CHECKED_OUT: '已离开', CANCELLED: '已取消' }
const dateTime = (value: string | null | undefined) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'

async function load() {
  if (!auth.currentProjectId) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await http.get('/visitors', { params: {
      communityId: auth.currentProjectId, from: dateRange.value?.[0] || undefined, to: dateRange.value?.[1] || undefined,
      keyword: keyword.value || undefined, status: visitStatus.value || undefined, page: page.value, size: pageSize.value,
    } })
    rows.value = data.items
    total.value = data.total
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '访客记录加载失败'
  } finally {
    loading.value = false
  }
}

function reset() {
  keyword.value = ''; visitStatus.value = ''; dateRange.value = []; page.value = 1; void load()
}

function openRegister() {
  Object.assign(form, { visitorNameMasked: '合成访客丁**', visitorMobileMasked: '136****0004', hostNameMasked: '合成住户丁**', assetName: '4号楼-1单元-0401', scheduledAt: dayjs().add(1, 'day').format('YYYY-MM-DD HH:mm:ss') })
  dialogVisible.value = true
}

async function register() {
  if (!auth.currentProjectId) return
  submitting.value = true
  try {
    await http.post('/visitors', { communityId: auth.currentProjectId, ...form })
    dialogVisible.value = false
    ElMessage.success('模拟访客登记成功；未向真实门禁发送数据')
    await load()
  } catch (reason: any) {
    ElMessage.error(reason.response?.data?.message || '登记失败')
  } finally {
    submitting.value = false
  }
}

async function transition(row: Visitor, action: 'check-in' | 'check-out') {
  if (!auth.currentProjectId) return
  try {
    await http.post(`/visitors/${row.id}:${action}`, { communityId: auth.currentProjectId, expectedVersion: row.version })
    ElMessage.success(action === 'check-in' ? '模拟核验进入成功' : '模拟核验离开成功')
    await load()
  } catch (reason: any) {
    ElMessage.error(reason.response?.data?.message || '状态流转失败')
    await load()
  }
}

function exportRows() {
  tasks.createExportTask({
    title: '访客记录脱敏导出', sourcePath: '/security/visitor-records', projectId: auth.currentProjectId,
    fileName: `访客记录-脱敏-${dayjs().format('YYYY-MM-DD')}.csv`,
    columns: [
      { key: 'visit_no', label: '访客单号' }, { key: 'visitor_name_masked', label: '访客姓名（脱敏）' },
      { key: 'visitor_mobile_masked', label: '联系方式（脱敏）' }, { key: 'host_name_masked', label: '受访人（脱敏）' },
      { key: 'asset_name', label: '访问房产' }, { key: 'scheduled_at', label: '预约时间' },
      { key: 'check_in_at', label: '进入时间' }, { key: 'check_out_at', label: '离开时间' }, { key: 'visit_status', label: '状态' },
    ], rows: rows.value.map((row) => ({ ...row })),
  })
  tasks.openDrawer()
  ElMessage.success('脱敏导出已进入任务中心并记录当前项目范围')
}

function validateImport(uploadFile: UploadFile) {
  if (!uploadFile.raw) return
  tasks.createImportValidationTask({
    title: '访客模拟导入校验', sourcePath: '/security/visitor-records', projectId: auth.currentProjectId,
    file: uploadFile.raw, expectedHeaders: ['访客姓名（脱敏）', '联系方式（脱敏）', '受访人（脱敏）', '访问房产', '预约时间'],
  })
  tasks.openDrawer()
  ElMessage.info('文件仅进入模拟导入校验；不会连接真实门禁或写入未脱敏个人信息')
}

watch([() => auth.currentProjectId, page, pageSize], load, { immediate: true })
</script>

<template>
  <section class="visitor-workbench">
    <el-alert type="warning" :closable="false" show-icon title="当前仅使用 IOT_SIMULATOR：没有真实门禁连接，productionConnected=false；姓名与联系方式只接受合成或脱敏值。" />
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never">
      <template #header>
        <div class="visitor-header">
          <div><span class="section-kicker">SIMULATED ACCESS CONTROL</span><h2>访客记录</h2></div>
          <div class="visitor-actions">
            <el-upload v-if="canImport" action="#" accept=".csv,text/csv" :auto-upload="false" :show-file-list="false" :on-change="validateImport"><el-button :icon="Upload">模拟导入</el-button></el-upload>
            <el-button v-if="canExport" :icon="Download" @click="exportRows">脱敏导出</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openRegister">登记访客</el-button>
          </div>
        </div>
      </template>

      <el-form inline class="visitor-filter" @submit.prevent="page = 1; load()">
        <el-form-item label="来访日期"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
        <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="访客/受访人/房产" @keyup.enter="page = 1; load()" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="visitStatus" clearable placeholder="全部状态" style="width:140px"><el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="page = 1; load()">查询</el-button><el-button @click="reset">重置</el-button><el-button :icon="Refresh" @click="load">刷新</el-button></el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" empty-text="当前筛选条件下暂无访客记录">
        <el-table-column prop="visit_no" label="访客单号" min-width="142" />
        <el-table-column prop="visitor_name_masked" label="访客姓名" min-width="110" />
        <el-table-column prop="visitor_mobile_masked" label="联系方式" width="120" />
        <el-table-column prop="host_name_masked" label="受访人" min-width="110" />
        <el-table-column prop="asset_name" label="访问房产" min-width="135" />
        <el-table-column label="进入时间" width="138"><template #default="{ row }">{{ dateTime(row.check_in_at) }}</template></el-table-column>
        <el-table-column label="离开时间" width="138"><template #default="{ row }">{{ dateTime(row.check_out_at) }}</template></el-table-column>
        <el-table-column label="状态" width="82"><template #default="{ row }"><el-tag :type="row.visit_status === 'CHECKED_OUT' ? 'info' : row.visit_status === 'CHECKED_IN' ? 'success' : 'warning'">{{ statusLabels[row.visit_status] || row.visit_status }}</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite" label="模拟核验" width="115" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.visit_status === 'REGISTERED'" link type="primary" @click="transition(row, 'check-in')">核验进入</el-button>
            <el-button v-if="row.visit_status === 'CHECKED_IN'" link type="primary" @click="transition(row, 'check-out')">核验离开</el-button>
            <span v-if="!['REGISTERED','CHECKED_IN'].includes(row.visit_status)" class="muted">流程已结束</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="visitor-pagination"><span>共 {{ total }} 条合成脱敏记录</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" layout="sizes, prev, pager, next" :total="total" :page-sizes="[20,50,100]" /></div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="登记模拟访客" width="540px">
      <el-alert type="info" :closable="false" show-icon title="只允许合成/脱敏值；此操作不会接触真实访客或门禁设备。" />
      <el-form label-position="top" class="register-form">
        <el-form-item label="访客姓名（脱敏）" required><el-input v-model="form.visitorNameMasked" /></el-form-item>
        <el-form-item label="联系方式（脱敏）" required><el-input v-model="form.visitorMobileMasked" placeholder="138****0001" /></el-form-item>
        <el-form-item label="受访人（脱敏）" required><el-input v-model="form.hostNameMasked" /></el-form-item>
        <el-form-item label="访问房产" required><el-input v-model="form.assetName" /></el-form-item>
        <el-form-item label="预约时间" required><el-date-picker v-model="form.scheduledAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="register">确认登记</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.visitor-workbench { display:flex; flex-direction:column; gap:14px; }.visitor-header,.visitor-actions,.visitor-pagination { display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; }
.visitor-header h2 { margin:3px 0 0; }.visitor-filter { padding:14px 14px 0; margin-bottom:12px; background:#f7f9fc; border:1px solid #e5eaf0; border-radius:8px; }
.visitor-pagination { padding:14px 2px 2px; color:#738094; }.muted { color:#9aa5b3; }.register-form { margin-top:14px; }
</style>
