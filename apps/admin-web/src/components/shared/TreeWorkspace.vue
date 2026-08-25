<script setup lang="ts">
withDefaults(defineProps<{
  treeTitle?: string
  treeWidth?: string
}>(), {
  treeTitle: '范围导航',
  treeWidth: '240px',
})
</script>

<template>
  <div class="tree-workspace" :style="{ '--tree-workspace-width': treeWidth }">
    <el-card shadow="never" class="tree-workspace__tree">
      <template #header>
        <div class="tree-workspace__header"><strong>{{ treeTitle }}</strong><slot name="tree-actions" /></div>
      </template>
      <slot name="tree" />
    </el-card>
    <div class="tree-workspace__content"><slot /></div>
  </div>
</template>

<style scoped>
.tree-workspace { min-width: 0; display: grid; grid-template-columns: var(--tree-workspace-width) minmax(0, 1fr); gap: 14px; }
.tree-workspace__tree { min-width: 0; border-color: var(--line); border-radius: 9px; }
.tree-workspace__tree :deep(.el-card__header) { padding: 14px 16px; }
.tree-workspace__tree :deep(.el-card__body) { padding: 10px; }
.tree-workspace__header { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #34435c; font-size: 13px; }
.tree-workspace__content { min-width: 0; }
</style>
