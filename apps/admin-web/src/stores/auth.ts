import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '../api/http'
import { pageCatalog } from '../config/pageCatalog'

const mockPermissions = [...new Set([
  'system:audit',
  ...pageCatalog.flatMap((page) => Object.values(page.permissions).filter((permission): permission is string => Boolean(permission))),
])]

export interface SessionUser {
  id: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  projectIds: string[]
  passwordChangeRequired: boolean
}

export interface ProjectOption {
  id: string
  name: string
  status: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(sessionStorage.getItem('pms_access_token') || '')
  const user = ref<SessionUser | null>(null)
  const projects = ref<ProjectOption[]>([])
  const currentProjectId = ref(sessionStorage.getItem('pms_current_project') || '')
  const authenticated = computed(() => Boolean(token.value))
  const currentProject = computed(() => projects.value.find((item) => item.id === currentProjectId.value) || null)

  function hasPermission(permission?: string) {
    return !permission || Boolean(user.value?.permissions.includes(permission))
  }

  async function login(username: string, password: string) {
    if (import.meta.env.VITE_USE_MOCK === 'true') {
      if (!username.trim() || !password) throw new Error('请输入账号和密码')
      token.value = `mock-${crypto.randomUUID()}`
      user.value = {
        id: 'mock-admin', username, displayName: username,
        roles: ['PLATFORM_ADMIN'],
        permissions: mockPermissions,
        projectIds: ['30000000-0000-0000-0000-000000000001'],
        passwordChangeRequired: false,
      }
    } else {
      const { data } = await http.post('/auth/login', { username, password })
      token.value = data.accessToken
      user.value = data.user
    }
    sessionStorage.setItem('pms_access_token', token.value)
  }

  async function loadProfile() {
    if (!token.value || user.value) return
    if (token.value.startsWith('mock-')) {
      user.value = {
        id: 'mock-admin', username: 'admin', displayName: '系统管理员', roles: ['PLATFORM_ADMIN'],
        permissions: mockPermissions,
        projectIds: ['30000000-0000-0000-0000-000000000001'],
        passwordChangeRequired: false,
      }
      return
    }
    const { data } = await http.get('/auth/me')
    user.value = data
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    const { data } = await http.put('/auth/change-password', { currentPassword, newPassword })
    token.value = data.accessToken
    user.value = data.user
    sessionStorage.setItem('pms_access_token', token.value)
  }

  async function loadProjects() {
    if (!token.value) return
    if (token.value.startsWith('mock-')) {
      projects.value = [{ id: 'mock-project', name: '本地测试项目', status: 'ACTIVE' }]
    } else {
      const { data } = await http.get('/data/communities', { params: { page: 1, size: 100, sort: 'name,asc', status: 'ACTIVE' } })
      projects.value = data.items
    }
    if (!projects.value.some((item) => item.id === currentProjectId.value)) {
      currentProjectId.value = projects.value[0]?.id || ''
    }
    if (currentProjectId.value) sessionStorage.setItem('pms_current_project', currentProjectId.value)
  }

  function setProject(projectId: string) {
    if (!projects.value.some((item) => item.id === projectId)) return
    currentProjectId.value = projectId
    sessionStorage.setItem('pms_current_project', projectId)
  }

  function logout() {
    token.value = ''
    user.value = null
    projects.value = []
    currentProjectId.value = ''
    sessionStorage.removeItem('pms_access_token')
    sessionStorage.removeItem('pms_current_project')
  }

  return {
    token, user, projects, currentProjectId, currentProject, authenticated,
    hasPermission, login, changePassword, loadProfile, loadProjects, setProject, logout,
  }
})
