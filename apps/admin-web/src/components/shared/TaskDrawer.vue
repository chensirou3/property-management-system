<script setup lang="ts">
import { computed } from 'vue'
import { Delete, Download, RefreshRight, VideoPause } from '@element-plus/icons-vue'
import { useTaskStore, type AsyncTask } from '../../stores/tasks'
import StatusTag from './StatusTag.vue'

const taskStore = useTaskStore()
const hasFinished = computed(() => taskStore.tasks.some((task) => !['QUEUED', 'RUNNING'].includes(task.status)))

function kindLabel(kind: AsyncTask['kind']) {
  return { IMPORT: '导入校验', EXPORT: '数据导出', PRINT: '打印文件' }[kind]
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<template>
  <el-drawer v-model="taskStore.drawerVisible" title="异步任务中心" size="520px" class="task-drawer">
    <template #header>
      <div class="task-drawer__heading">
        <div><strong>异步任务中心</strong><span>{{ taskStore.activeCount }} 个执行中 · 最近 50 个任务</span></div>
        <el-button v-if="hasFinished" text :icon="Delete" @click="taskStore.clearFinished">清除已结束</el-button>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon title="当前为浏览器内可运行任务基础；大数据量和生产任务将在服务端队列执行。" />
    <el-empty v-if="!taskStore.tasks.length" description="暂无导入、导出或打印任务" />
    <div v-else class="task-list">
      <article v-for="task in taskStore.tasks" :key="task.id" class="task-card">
        <div class="task-card__top">
          <div><span class="task-kind">{{ kindLabel(task.kind) }}</span><strong>{{ task.title }}</strong></div>
          <StatusTag :value="task.status" />
        </div>
        <el-progress :percentage="task.progress" :status="task.status === 'FAILED' ? 'exception' : task.status === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="7" />
        <p>{{ task.message }}</p>
        <dl>
          <div><dt>来源页面</dt><dd>{{ task.sourcePath }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(task.createdAt) }}</dd></div>
          <div v-if="task.totalRows !== undefined"><dt>处理结果</dt><dd>共 {{ task.totalRows }} / 成功 {{ task.successRows || 0 }} / 失败 {{ task.failedRows || 0 }}</dd></div>
        </dl>
        <div class="task-card__actions">
          <el-button v-if="['QUEUED', 'RUNNING'].includes(task.status)" size="small" :icon="VideoPause" @click="taskStore.cancel(task.id)">取消</el-button>
          <el-button v-if="['FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(task.status)" size="small" :icon="RefreshRight" @click="taskStore.retry(task.id)">重试</el-button>
          <el-button v-if="task.fileName" size="small" type="primary" :icon="Download" @click="taskStore.download(task.id)">下载结果</el-button>
        </div>
      </article>
    </div>
  </el-drawer>
</template>

<style scoped>
.task-drawer__heading { width: 100%; padding-right: 20px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-drawer__heading > div { display: flex; flex-direction: column; gap: 4px; }
.task-drawer__heading strong { color: #283850; font-size: 16px; }
.task-drawer__heading span { color: #8792a5; font-size: 11px; }
.task-list { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.task-card { padding: 14px; border: 1px solid #e0e6ee; border-radius: 8px; background: #fff; }
.task-card__top { margin-bottom: 12px; display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.task-card__top > div { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.task-kind { color: #78859a; font-size: 10px; }
.task-card__top strong { overflow: hidden; color: #30405a; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.task-card p { margin: 9px 0; color: #657289; font-size: 11px; line-height: 1.6; }
.task-card dl { margin: 0; display: grid; gap: 4px; }
.task-card dl div { display: grid; grid-template-columns: 65px minmax(0, 1fr); gap: 8px; font-size: 10px; }
.task-card dt { color: #97a0b0; }
.task-card dd { margin: 0; overflow: hidden; color: #657289; text-overflow: ellipsis; white-space: nowrap; }
.task-card__actions { margin-top: 12px; display: flex; justify-content: flex-end; gap: 7px; }
</style>
