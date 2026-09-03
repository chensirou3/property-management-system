<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  Bell,
  CircleClose,
  Close,
  Expand,
  Fold,
  Grid,
  HomeFilled,
  QuestionFilled,
  RefreshRight,
  SwitchButton,
} from '@element-plus/icons-vue'
import { navigation } from '../config/navigation'
import { canAccessNavigationPath, firstAuthorizedNavigationItem } from '../config/access'
import { schemaFor } from '../config/pageSchemas'
import TaskDrawer from '../components/shared/TaskDrawer.vue'
import { useAuthStore } from '../stores/auth'
import { useTaskStore } from '../stores/tasks'

interface WorkspaceTab {
  path: string
  title: string
}

const TAB_STORAGE_KEY = 'pms_workspace_tabs'
const collapsed = ref(false)
const viewRefreshKey = ref(0)
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const taskStore = useTaskStore()
const currentTitle = computed(() => String(route.meta.title || '物业管理平台'))
const homeItem = computed(() => firstAuthorizedNavigationItem(auth.user?.permissions))
const visibleNavigation = computed(() => navigation.map((group) => ({
  ...group,
  children: group.children.filter((item) => auth.hasPermission(item.permission || schemaFor(item.path)?.readPermission)),
})).filter((group) => group.children.length > 0))
const currentGroupKey = computed(() => visibleNavigation.value.find((group) =>
  group.children.some((item) => item.path === route.path))?.key || '')

function restoreTabs(): WorkspaceTab[] {
  try {
    const saved = JSON.parse(sessionStorage.getItem(TAB_STORAGE_KEY) || '[]')
    if (Array.isArray(saved)) {
      return saved.filter((item) => typeof item?.path === 'string' && typeof item?.title === 'string')
    }
  } catch {
    sessionStorage.removeItem(TAB_STORAGE_KEY)
  }
  return []
}

const workspaceTabs = ref<WorkspaceTab[]>(restoreTabs())

function persistTabs() {
  sessionStorage.setItem(TAB_STORAGE_KEY, JSON.stringify(workspaceTabs.value.slice(-12)))
}

function ensureCurrentTab() {
  if (route.meta.public || route.path === '/forbidden') return
  workspaceTabs.value = workspaceTabs.value.filter((item) =>
    canAccessNavigationPath(item.path, auth.user?.permissions))
  const existing = workspaceTabs.value.find((item) => item.path === route.path)
  if (existing) existing.title = currentTitle.value
  else workspaceTabs.value.push({ path: route.path, title: currentTitle.value })
  if (homeItem.value && !workspaceTabs.value.some((item) => item.path === homeItem.value?.path)) {
    workspaceTabs.value.unshift({ path: homeItem.value.path, title: homeItem.value.title })
  }
  if (workspaceTabs.value.length > 12) {
    workspaceTabs.value = [workspaceTabs.value[0], ...workspaceTabs.value.slice(-11)]
  }
  persistTabs()
}

function goToTab(path: string) {
  if (path !== route.path) void router.push(path)
}

function closeTab(path: string) {
  if (path === homeItem.value?.path) return
  const index = workspaceTabs.value.findIndex((item) => item.path === path)
  if (index < 0) return
  workspaceTabs.value.splice(index, 1)
  persistTabs()
  if (route.path === path) {
    const next = workspaceTabs.value[Math.max(0, index - 1)] || workspaceTabs.value[0]
    void router.push(next?.path || homeItem.value?.path || '/forbidden')
  }
}

function closeOtherTabs() {
  const current = workspaceTabs.value.find((item) => item.path === route.path)
  workspaceTabs.value = [
    ...(homeItem.value ? [{ path: homeItem.value.path, title: homeItem.value.title }] : []),
    ...(current && current.path !== homeItem.value?.path ? [current] : []),
  ]
  persistTabs()
}

function refreshCurrentPage() {
  viewRefreshKey.value += 1
}

onMounted(async () => {
  await auth.loadProfile()
  await auth.loadProjects()
  ensureCurrentTab()
})

watch(() => [route.path, currentTitle.value], ensureCurrentTab)

function changeProject(projectId: string) {
  auth.setProject(projectId)
}

function logout() {
  auth.logout()
  sessionStorage.removeItem(TAB_STORAGE_KEY)
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar-brand">
        <span class="cloud-mark">物</span>
        <strong>物业云</strong>
        <sup>®</sup>
      </div>
      <button class="menu-toggle" type="button" aria-label="展开或收起菜单" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
      </button>
      <div class="topbar-app"><el-icon><Grid /></el-icon><span>我的应用</span></div>
      <div class="topbar-spacer"></div>
      <span v-if="auth.projects.length === 1" class="project-single" title="当前部署仅启用一个项目">
        {{ auth.projects[0].name }}
      </span>
      <el-select
        v-else
        :model-value="auth.currentProjectId"
        class="project-select"
        popper-class="project-select-popper"
        placeholder="请选择项目"
        @change="changeProject"
      >
        <el-option v-for="project in auth.projects" :key="project.id" :label="project.name" :value="project.id" />
      </el-select>
      <div class="topbar-tools">
        <button type="button" aria-label="使用帮助"><el-icon><QuestionFilled /></el-icon></button>
        <el-badge :value="taskStore.activeCount" :hidden="taskStore.activeCount === 0" class="task-center-badge">
          <button type="button" aria-label="任务中心" @click="taskStore.openDrawer"><el-icon><Bell /></el-icon></button>
        </el-badge>
      </div>
      <el-dropdown>
        <button class="user-button" type="button">
          <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) || '管' }}</span>
          <span class="user-name">{{ auth.user?.displayName || '系统管理员' }}</span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ auth.user?.roles.join(' / ') }}</el-dropdown-item>
            <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <aside class="sidebar" :class="{ collapsed }">
      <el-scrollbar class="menu-scroll">
        <el-menu
          :key="currentGroupKey"
          :default-active="route.path"
          :default-openeds="currentGroupKey ? [currentGroupKey] : []"
          router
          unique-opened
          :collapse="collapsed"
          :collapse-transition="false"
          class="app-menu"
        >
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
      <nav class="workspace-tabs" aria-label="已打开页面">
        <button
          v-for="tab in workspaceTabs"
          :key="tab.path"
          type="button"
          class="workspace-tab"
          :class="{ active: tab.path === route.path }"
          @click="goToTab(tab.path)"
        >
          <el-icon v-if="tab.path === homeItem?.path"><HomeFilled /></el-icon>
          <span>{{ tab.title }}</span>
          <el-icon v-if="tab.path !== homeItem?.path" class="tab-close" @click.stop="closeTab(tab.path)"><Close /></el-icon>
        </button>
        <div class="workspace-tab-actions">
          <el-tooltip content="刷新当前页面" placement="bottom">
            <button type="button" aria-label="刷新当前页面" @click="refreshCurrentPage"><el-icon><RefreshRight /></el-icon></button>
          </el-tooltip>
          <el-tooltip content="关闭其他页签" placement="bottom">
            <button type="button" aria-label="关闭其他页签" @click="closeOtherTabs"><el-icon><CircleClose /></el-icon></button>
          </el-tooltip>
        </div>
      </nav>

      <div class="page-heading">
        <div>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>我的应用</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
          <h1>{{ currentTitle }}</h1>
        </div>
        <div class="heading-status">
          <span class="status-dot"></span>
          合成测试环境 · 外部通道模拟
        </div>
      </div>

      <main class="page-content">
        <router-view v-slot="{ Component }">
          <KeepAlive :max="12">
            <component :is="Component" :key="`${route.path}:${viewRefreshKey}`" />
          </KeepAlive>
        </router-view>
      </main>
    </section>
    <TaskDrawer />
  </div>
</template>
