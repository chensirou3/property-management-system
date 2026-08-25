<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'

defineProps<{
  selectedCount: number
  total?: number
}>()

const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <transition name="batch-bar">
    <div v-if="selectedCount > 0" class="batch-action-bar" role="status">
      <div><strong>已选择 {{ selectedCount }} 条</strong><span v-if="total"> / 共 {{ total }} 条</span></div>
      <div class="batch-action-bar__actions">
        <slot />
        <el-button text :icon="Close" @click="emit('clear')">取消选择</el-button>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.batch-action-bar { min-height: 48px; margin-bottom: 10px; padding: 8px 14px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border: 1px solid #b9d7ff; border-radius: 7px; color: #315578; background: #eef6ff; font-size: 12px; }
.batch-action-bar span { color: #71839a; }
.batch-action-bar__actions { display: flex; align-items: center; gap: 8px; }
.batch-bar-enter-active, .batch-bar-leave-active { transition: opacity .15s ease, transform .15s ease; }
.batch-bar-enter-from, .batch-bar-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
