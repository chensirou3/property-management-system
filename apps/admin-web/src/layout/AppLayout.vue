<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Expand, Fold, Search, SwitchButton } from '@element-plus/icons-vue'
import { navigation } from '../config/navigation'
import { schemaFor } from '../config/pageSchemas'
import { useAuthStore } from '../stores/auth'

const collapsed = ref(false)
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const currentTitle = computed(() => String(route.meta.title || '物业管理平台'))
const visibleNavigation = computed(() => navigation.map((group) => ({
  ...group,
  children: group.children.filter((item) => {
    if (item.path === '/dashboard') return auth.hasPermission('dashboard:read')
    return auth.hasPermission(schemaFor(item.path)?.readPermission)
  }),
})).filter((group) => group.children.length > 0))

onMounted(async () => {
  await auth.loadProfile()
  await auth.loadProjects()
})

function changeProject(projectId: string) {
  auth.setProject(projectId)
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="brand-mark">物</div>
        <div v-show="!collapsed" class="brand-copy">
          <strong>物业管理平台</strong>
          <span>PROPERTY OPS</span>
        </div>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu :default-active="route.path" router :collapse="collapsed" class="app-menu">
          <el-sub-menu v-for="group in visibleNavigation" :key="group.key" :index="group.key">
            <template #title>
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
              <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
      <button class="collapse-button" type="button" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        <span v-if="!collapsed">收起菜单</span>
      </button>
    </aside>

    <section class="main-region">
      <header class="topbar">
        <div class="project-context">
          <span class="context-label">当前项目</span>
          <el-select :model-value="auth.currentProjectId" class="project-select" placeholder="请选择项目" @change="changeProject">
            <el-option v-for="project in auth.projects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
          <el-tag type="warning" effect="plain">合成测试环境</el-tag>
        </div>
        <div class="top-actions">
          <el-button text circle :icon="Search" aria-label="全局搜索" />
          <el-dropdown>
            <button class="user-button" type="button">
              <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) || '管' }}</span>
              <span>{{ auth.user?.displayName || '系统管理员' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.roles.join(' / ') }}</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <div class="page-heading">
        <div>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>物业管理平台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
          <h1>{{ currentTitle }}</h1>
        </div>
        <div class="heading-status">
          <span class="status-dot"></span>
          模拟支付 / 发票 / IoT
        </div>
      </div>

      <main class="page-content">
        <router-view />
      </main>
    </section>
  </div>
</template>
