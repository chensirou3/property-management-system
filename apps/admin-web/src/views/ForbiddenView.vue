<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { firstAuthorizedPath } from '../config/access'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const returnPath = computed(() => firstAuthorizedPath(auth.user?.permissions))
</script>

<template>
  <el-result icon="warning" title="无权访问" sub-title="当前角色没有该页面或项目的数据权限。">
    <template #extra>
      <el-button v-if="returnPath !== '/forbidden'" type="primary" @click="router.push(returnPath)">返回可用工作台</el-button>
    </template>
  </el-result>
</template>
