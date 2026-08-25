<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value?: string | number | null
  currency?: string
  emptyText?: string
}>(), {
  currency: 'CNY',
  emptyText: '—',
})

const formatted = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') return props.emptyText
  const amount = Number(props.value)
  if (!Number.isFinite(amount)) return props.emptyText
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: props.currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)
})
</script>

<template><span class="money-cell">{{ formatted }}</span></template>

<style scoped>
.money-cell { color: #263a57; font-variant-numeric: tabular-nums; white-space: nowrap; }
</style>
