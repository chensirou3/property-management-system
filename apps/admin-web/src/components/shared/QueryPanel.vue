<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'

withDefaults(defineProps<{
  loading?: boolean
  title?: string
  showSavedFilters?: boolean
}>(), {
  loading: false,
  title: '查询条件',
  showSavedFilters: true,
})

const emit = defineEmits<{
  query: []
  reset: []
  save: []
  restore: []
}>()
</script>

<template>
  <el-card shadow="never" class="query-panel">
    <el-form :inline="true" label-position="left" @submit.prevent="emit('query')">
      <slot />
      <el-form-item class="query-panel__actions">
        <el-button type="primary" :icon="Search" :loading="loading" @click="emit('query')">查询</el-button>
        <el-button :icon="Refresh" :disabled="loading" @click="emit('reset')">重置</el-button>
        <template v-if="showSavedFilters">
          <el-button text :disabled="loading" @click="emit('save')">保存筛选</el-button>
          <el-button text :disabled="loading" @click="emit('restore')">恢复筛选</el-button>
        </template>
        <slot name="actions" />
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.query-panel { margin-bottom: 14px; border-color: var(--line); border-radius: 9px; }
.query-panel :deep(.el-card__body) { padding: 17px 20px 1px; }
.query-panel :deep(.el-form-item) { margin-bottom: 16px; }
.query-panel__actions { margin-left: 4px; }
</style>
