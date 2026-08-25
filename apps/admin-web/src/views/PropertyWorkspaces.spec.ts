import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import AssetWorkspaceView from './AssetWorkspaceView.vue'
import CustomerRelationshipView from './CustomerRelationshipView.vue'

let routePath = '/archives/rooms'
vi.mock('vue-router', () => ({ useRoute: () => ({ path: routePath, query: {}, meta: {} }) }))

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成验收项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = {
    id: 'user-1', username: 'tester', displayName: '测试人员', roles: [],
    permissions: ['property:read', 'property:write'], projectIds: ['project-1'], passwordChangeRequired: false,
  }
  return pinia
}

const stubs = {
  QueryPanel: { template: '<section><slot /></section>' },
  TreeWorkspace: { template: '<section><slot name="tree-actions"/><slot name="tree"/><slot /></section>' },
  DataGrid: { props: ['rows', 'total'], template: '<section><slot name="toolbar"/><span>共 {{ total }} 条</span><slot name="operations" :row="rows[0] || {}"/></section>' },
  StatusTag: true,
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': true,
  'el-select': true,
  'el-option': true,
  'el-icon': true,
  'el-tree': true,
  'el-drawer': true,
  'el-dialog': true,
  'el-upload': true,
}

afterEach(() => vi.restoreAllMocks())

describe('property archive workspaces', () => {
  it('loads the real room asset tree and typed asset page', async () => {
    routePath = '/archives/rooms'
    const get = vi.spyOn(http, 'get').mockImplementation(async (url) => {
      if (url === '/property/tree') return { data: { grids: [], buildings: [], units: [], assets: [], assetCount: 359 } } as any
      return { data: { items: [], total: 359 } } as any
    })
    const wrapper = mount(AssetWorkspaceView, { global: { plugins: [projectPinia()], directives: { loading: () => undefined }, stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('房产信息采用统一资产主档')
    expect(wrapper.text()).toContain('新增房产信息')
    expect(get).toHaveBeenCalledWith('/property/tree', expect.objectContaining({
      params: expect.objectContaining({ communityId: 'project-1', assetType: 'ROOM' }),
    }))
    expect(get).toHaveBeenCalledWith('/property/assets', expect.objectContaining({
      params: expect.objectContaining({ communityId: 'project-1', assetType: 'ROOM' }),
    }))
  })

  it('loads typed customers instead of structural sample rows', async () => {
    routePath = '/archives/customers'
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { items: [], total: 403 } } as any)
    const wrapper = mount(CustomerRelationshipView, { global: { plugins: [projectPinia()], directives: { loading: () => undefined }, stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('客户主档与资产关系分层管理')
    expect(wrapper.text()).toContain('新增客户')
    expect(get).toHaveBeenCalledWith('/property/customers', expect.objectContaining({
      params: expect.objectContaining({ communityId: 'project-1', status: 'ACTIVE', page: 1, size: 20 }),
    }))
  })
})
