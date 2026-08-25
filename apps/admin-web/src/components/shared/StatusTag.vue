<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value?: unknown }>()

const labels: Record<string, string> = {
  ACTIVE: '启用', INACTIVE: '停用', NORMAL: '正常', DISABLED: '禁用',
  PENDING: '待处理', QUEUED: '排队中', RUNNING: '执行中', PROCESSING: '处理中',
  UPLOADED: '已上传', VALIDATING: '校验中', READY: '待审批', APPROVED: '已审批', EXECUTING: '写入中',
  RECONCILED: '已对账', ROLLED_BACK: '已回滚', REJECTED: '已拒绝', MATCHED: '一致', MISMATCH: '不一致',
  SUCCESS: '成功', SUCCEEDED: '成功', COMPLETED: '已完成', PARTIAL: '部分完成', PARTIAL_FAILED: '部分失败',
  FAILED: '失败', ERROR: '错误', CANCELLED: '已取消', UNPAID: '未缴', PARTIAL_PAID: '部分缴费', PAID: '已缴',
  true: '启用', false: '停用',
}

const code = computed(() => String(props.value ?? ''))
const label = computed(() => labels[code.value] || code.value || '—')
const type = computed(() => {
  if (['ACTIVE', 'NORMAL', 'SUCCESS', 'SUCCEEDED', 'COMPLETED', 'RECONCILED', 'MATCHED', 'PAID', 'true'].includes(code.value)) return 'success'
  if (['FAILED', 'ERROR', 'CANCELLED', 'INACTIVE', 'DISABLED', 'REJECTED', 'MISMATCH', 'false'].includes(code.value)) return 'danger'
  if (['PENDING', 'UPLOADED', 'VALIDATING', 'READY', 'APPROVED', 'EXECUTING', 'QUEUED', 'RUNNING', 'PROCESSING', 'PARTIAL', 'PARTIAL_FAILED', 'PARTIAL_PAID'].includes(code.value)) return 'warning'
  return 'info'
})
</script>

<template><el-tag :type="type" effect="light" size="small">{{ label }}</el-tag></template>
