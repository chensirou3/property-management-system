<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Setting } from '@element-plus/icons-vue'
import MoneyCell from './MoneyCell.vue'
import StatusTag from './StatusTag.vue'

export interface DataGridColumn {
  prop: string
  label: string
  width?: number
  minWidth?: number
  sortable?: boolean
  sortKey?: string
  type?: string
}

const props = withDefaults(defineProps<{
  rows: Record<string, unknown>[]
  columns: DataGridColumn[]
  loading?: boolean
  errorMessage?: string
  total?: number
  page?: number
  pageSize?: number
  storageKey?: string
  rowKey?: string
  height?: string
  selectable?: boolean
  showIndex?: boolean
}>(), {
  loading: false,
  errorMessage: '',
  total: 0,
  page: 1,
  pageSize: 20,
  storageKey: 'default',
  rowKey: 'id',
  height: 'calc(100vh - 410px)',
  selectable: true,
  showIndex: true,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
  'selection-change': [rows: Record<string, unknown>[]]
  'visible-columns-change': [columns: string[]]
  'sort-change': [value: { prop: string; order: string | null }]
  reload: []
}>()

const tableRef = ref()
const visibleColumns = ref<string[]>([])

const storageName = computed(() => `pms-columns:${props.storageKey}`)
const renderedColumns = computed(() => props.columns.filter((column) => visibleColumns.value.includes(column.prop)))

function restoreColumns() {
  const available = new Set(props.columns.map((column) => column.prop))
  try {
    const saved = JSON.parse(localStorage.getItem(storageName.value) || '[]')
    const valid = Array.isArray(saved) ? saved.filter((prop) => available.has(prop)) : []
    visibleColumns.value = valid.length ? valid : [...available]
  } catch {
    visibleColumns.value = [...available]
  }
  emit('visible-columns-change', [...visibleColumns.value])
}

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

function isStatus(column: DataGridColumn) {
  return column.type === 'status' || ['status', 'operation_status', 'result_status', 'enabled'].includes(column.prop)
}

function isMoney(column: DataGridColumn) {
  return column.type === 'money' || /(amount|balance|fee|price)$/i.test(column.prop)
}

function updateColumns(value: string[]) {
  visibleColumns.value = value.length ? value : [props.columns[0]?.prop].filter(Boolean)
  localStorage.setItem(storageName.value, JSON.stringify(visibleColumns.value))
  emit('visible-columns-change', [...visibleColumns.value])
}

function clearSelection() {
  tableRef.value?.clearSelection()
}

watch([() => props.storageKey, () => props.columns], restoreColumns, { immediate: true, deep: true })
watch(() => props.pageSize, () => nextTick(() => tableRef.value?.doLayout()))

defineExpose({ clearSelection })
</script>

<template>
  <el-card shadow="never" class="data-grid-card">
    <div class="data-grid-toolbar">
      <div class="data-grid-toolbar__left"><slot name="toolbar" /></div>
      <div class="data-grid-toolbar__right">
        <slot name="summary"><span class="record-summary">共 <strong>{{ total }}</strong> 条记录</span></slot>
        <el-popover placement="bottom-end" :width="230" trigger="click">
          <template #reference><el-button circle :icon="Setting" aria-label="列设置" /></template>
          <div class="column-settings-title">显示列</div>
          <el-checkbox-group :model-value="visibleColumns" class="column-settings" @update:model-value="updateColumns">
            <el-checkbox v-for="column in columns" :key="column.prop" :value="column.prop">{{ column.label }}</el-checkbox>
          </el-checkbox-group>
        </el-popover>
      </div>
    </div>

    <el-alert v-if="errorMessage" type="error" show-icon :closable="false" :title="errorMessage" class="data-grid-error">
      <template #default><el-button link type="primary" @click="emit('reload')">重新加载</el-button></template>
    </el-alert>

    <el-table ref="tableRef" v-loading="loading" :data="rows" stripe :row-key="rowKey" :height="height"
      empty-text="暂无符合条件的数据" @sort-change="emit('sort-change', $event)" @selection-change="emit('selection-change', $event)">
      <el-table-column v-if="selectable" type="selection" width="46" fixed="left" reserve-selection />
      <el-table-column v-if="showIndex" type="index" label="序号" width="70" />
      <el-table-column v-for="column in renderedColumns" :key="column.prop" :prop="column.prop" :label="column.label"
        :width="column.width" :min-width="column.minWidth" :sortable="column.sortable ? 'custom' : false" show-overflow-tooltip>
        <template #default="scope">
          <slot name="cell" :column="column" :row="scope.row" :value="scope.row[column.prop]">
            <StatusTag v-if="isStatus(column)" :value="scope.row[column.prop]" />
            <MoneyCell v-else-if="isMoney(column)" :value="scope.row[column.prop] as string | number | null" />
            <span v-else>{{ display(scope.row[column.prop]) }}</span>
          </slot>
        </template>
      </el-table-column>
      <el-table-column v-if="$slots.operations" label="操作" min-width="180" fixed="right">
        <template #default="scope"><slot name="operations" :row="scope.row" /></template>
      </el-table-column>
    </el-table>

    <div class="pagination-row">
      <el-pagination :current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper" :total="total"
        @update:current-page="emit('update:page', $event)" @update:page-size="emit('update:pageSize', $event)" />
    </div>
  </el-card>
</template>

<style scoped>
.data-grid-card { border-color: var(--line); border-radius: 9px; }
.data-grid-card :deep(.el-card__body) { padding: 0; }
.data-grid-toolbar { min-height: 65px; padding: 10px 18px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--line); }
.data-grid-toolbar__left, .data-grid-toolbar__right { display: flex; align-items: center; gap: 9px; }
.record-summary { color: var(--muted); font-size: 12px; }
.record-summary strong { color: var(--forest-700); }
.data-grid-error { border-radius: 0; }
.pagination-row { padding: 15px 18px; display: flex; justify-content: flex-end; border-top: 1px solid var(--line); }
.column-settings-title { margin-bottom: 8px; color: #34435c; font-size: 12px; font-weight: 600; }
.column-settings { display: flex; flex-direction: column; max-height: 320px; overflow: auto; }
</style>
