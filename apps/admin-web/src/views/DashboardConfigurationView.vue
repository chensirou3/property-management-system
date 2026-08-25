<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Plus, Refresh, View } from '@element-plus/icons-vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

type Widget = Record<string, any>

const auth = useAuthStore()
const roleCode = ref('ALL')
const loading = ref(false)
const publishing = ref(false)
const error = ref('')
const widgets = ref<Widget[]>([])
const availableMetrics = ref<string[]>([])
const previewVisible = ref(true)
const dialogVisible = ref(false)
const editing = ref<Widget | null>(null)
const form = reactive({ widgetCode: '', widgetName: '', metricCode: 'ASSET_COUNTS', positionCode: 'MAIN', visible: true, refreshIntervalSeconds: 300 })

const canConfigure = computed(() => auth.hasPermission('dashboard:configure'))
const allPublished = computed(() => widgets.value.length > 0 && widgets.value.every((widget) => widget.status === 'PUBLISHED'))
const positions: Record<string, string> = { SUMMARY: '顶部摘要', MAIN: '主内容区', SIDE: '侧栏' }
const metrics: Record<string, string> = {
  ASSET_COUNTS: '资产数量', COLLECTION_RATE: '收缴率', DATA_QUALITY: '数据质量',
  INTEGRATION_STATUS: '集成状态', ARREARS_SUMMARY: '欠费概览', METER_PROGRESS: '抄表进度',
}

async function load() {
  if (!auth.currentProjectId) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await http.get('/dashboard/configurations', { params: { communityId: auth.currentProjectId, roleCode: roleCode.value } })
    widgets.value = data.items
    availableMetrics.value = data.availableMetrics
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '看板配置加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { widgetCode: '', widgetName: '', metricCode: 'ASSET_COUNTS', positionCode: 'MAIN', visible: true, refreshIntervalSeconds: 300 })
  dialogVisible.value = true
}

function openEdit(widget: Widget) {
  editing.value = widget
  Object.assign(form, {
    widgetCode: widget.widget_code, widgetName: widget.widget_name, metricCode: widget.metric_code,
    positionCode: widget.position_code, visible: Boolean(widget.visible), refreshIntervalSeconds: Number(widget.refresh_interval_seconds),
  })
  dialogVisible.value = true
}

async function save() {
  if (!auth.currentProjectId || !form.widgetName.trim()) return ElMessage.warning('请填写组件名称')
  try {
    if (editing.value) {
      await http.put(`/dashboard/configurations/${editing.value.id}`, {
        widgetName: form.widgetName, metricCode: form.metricCode, positionCode: form.positionCode,
        visible: form.visible, refreshIntervalSeconds: form.refreshIntervalSeconds, expectedVersion: editing.value.version,
      }, { params: { communityId: auth.currentProjectId } })
    } else {
      if (!form.widgetCode.trim()) return ElMessage.warning('请填写组件编码')
      await http.post('/dashboard/configurations', {
        communityId: auth.currentProjectId, roleCode: roleCode.value, widgetCode: form.widgetCode.toUpperCase(),
        widgetName: form.widgetName, metricCode: form.metricCode, positionCode: form.positionCode,
        visible: form.visible, refreshIntervalSeconds: form.refreshIntervalSeconds, displayOrder: widgets.value.length + 1,
      })
    }
    dialogVisible.value = false
    ElMessage.success('配置已保存为草稿')
    await load()
  } catch (reason: any) {
    ElMessage.error(reason.response?.data?.message || '保存失败')
  }
}

async function move(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= widgets.value.length || !auth.currentProjectId) return
  const ordered = [...widgets.value]
  ;[ordered[index], ordered[target]] = [ordered[target], ordered[index]]
  try {
    const { data } = await http.post('/dashboard/configurations:reorder', {
      communityId: auth.currentProjectId, roleCode: roleCode.value,
      widgets: ordered.map((widget) => ({ id: widget.id, expectedVersion: widget.version })),
    })
    widgets.value = data.items
    ElMessage.success('排序已保存为草稿')
  } catch (reason: any) {
    ElMessage.error(reason.response?.data?.message || '排序失败，请刷新后重试')
    await load()
  }
}

async function publish() {
  if (!auth.currentProjectId || !widgets.value.length) return
  publishing.value = true
  try {
    const { data } = await http.post('/dashboard/configurations:publish', {
      communityId: auth.currentProjectId, roleCode: roleCode.value,
      widgets: widgets.value.map((widget) => ({ id: widget.id, expectedVersion: widget.version })),
    })
    widgets.value = data.items
    ElMessage.success('看板配置已发布')
  } catch (reason: any) {
    ElMessage.error(reason.response?.data?.message || '发布失败，请刷新后重试')
    await load()
  } finally {
    publishing.value = false
  }
}

watch([() => auth.currentProjectId, roleCode], load, { immediate: true })
</script>

<template>
  <section class="dashboard-designer" v-loading="loading">
    <el-alert type="info" :closable="false" show-icon title="配置按项目和角色隔离；编辑与排序生成草稿，发布使用乐观锁并写入审计。" />
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never">
      <template #header>
        <div class="designer-header">
          <div><span class="section-kicker">DASHBOARD DESIGNER</span><h2>看板配置</h2></div>
          <div class="designer-actions">
            <el-select v-model="roleCode" aria-label="适用角色" style="width: 170px">
              <el-option label="全部角色" value="ALL" /><el-option label="平台管理员" value="PLATFORM_ADMIN" />
              <el-option label="项目经理" value="PROJECT_MANAGER" />
            </el-select>
            <el-button :icon="Refresh" @click="load">刷新</el-button>
            <el-button :icon="View" @click="previewVisible = !previewVisible">{{ previewVisible ? '隐藏预览' : '预览' }}</el-button>
            <el-button v-if="canConfigure" :icon="Plus" @click="openCreate">新增组件</el-button>
            <el-button v-if="canConfigure" type="primary" :loading="publishing" :disabled="allPublished" @click="publish">发布配置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="widgets" empty-text="当前角色尚未配置组件">
        <el-table-column prop="widget_code" label="组件编码" min-width="160" />
        <el-table-column prop="widget_name" label="组件名称" min-width="150" />
        <el-table-column label="指标" min-width="140"><template #default="{ row }">{{ metrics[row.metric_code] || row.metric_code }}</template></el-table-column>
        <el-table-column label="位置" width="110"><template #default="{ row }">{{ positions[row.position_code] || row.position_code }}</template></el-table-column>
        <el-table-column label="显示" width="90"><template #default="{ row }"><el-tag :type="row.visible ? 'success' : 'info'">{{ row.visible ? '显示' : '隐藏' }}</el-tag></template></el-table-column>
        <el-table-column prop="refresh_interval_seconds" label="刷新（秒）" width="110" />
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'warning'">{{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag></template></el-table-column>
        <el-table-column v-if="canConfigure" label="操作" width="210" fixed="right">
          <template #default="{ row, $index }">
            <el-button link type="primary" @click="openEdit(row)">配置</el-button>
            <el-button link :icon="ArrowUp" :disabled="$index === 0" aria-label="上移" @click="move($index, -1)" />
            <el-button link :icon="ArrowDown" :disabled="$index === widgets.length - 1" aria-label="下移" @click="move($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="previewVisible" shadow="never" class="preview-card">
      <template #header><strong>发布前预览 · {{ roleCode }}</strong></template>
      <div class="widget-preview">
        <article v-for="widget in widgets.filter((item) => item.visible)" :key="widget.id" :class="`position-${widget.position_code.toLowerCase()}`">
          <span>{{ widget.widget_name }}</span><strong>{{ metrics[widget.metric_code] || widget.metric_code }}</strong>
          <small>{{ widget.refresh_interval_seconds }} 秒刷新 · {{ widget.status === 'PUBLISHED' ? '已发布' : '草稿预览' }}</small>
        </article>
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '配置组件' : '新增组件'" width="520px">
      <el-form label-position="top">
        <el-form-item label="组件编码" required><el-input v-model="form.widgetCode" :disabled="Boolean(editing)" placeholder="例如 ARREARS_SUMMARY" /></el-form-item>
        <el-form-item label="组件名称" required><el-input v-model="form.widgetName" /></el-form-item>
        <el-form-item label="指标" required><el-select v-model="form.metricCode" style="width:100%"><el-option v-for="metric in availableMetrics" :key="metric" :label="metrics[metric] || metric" :value="metric" /></el-select></el-form-item>
        <el-form-item label="位置"><el-select v-model="form.positionCode" style="width:100%"><el-option v-for="(label, code) in positions" :key="code" :label="label" :value="code" /></el-select></el-form-item>
        <el-form-item label="刷新间隔（秒）"><el-input-number v-model="form.refreshIntervalSeconds" :min="30" :max="3600" :step="30" /></el-form-item>
        <el-form-item label="是否显示"><el-switch v-model="form.visible" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.dashboard-designer { display: flex; flex-direction: column; gap: 14px; }
.designer-header, .designer-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.designer-header h2 { margin: 3px 0 0; }.preview-card { min-height: 190px; }
.widget-preview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.widget-preview article { min-height: 108px; padding: 16px; border: 1px solid #dce5ef; border-radius: 8px; background: linear-gradient(145deg,#fff,#f6f9fc); display:flex; flex-direction:column; gap:8px; }
.widget-preview article strong { color:#245f91; }.widget-preview article small { color:#8793a3; }.position-main { grid-column: span 2; }
@media (max-width: 1000px) { .widget-preview { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
