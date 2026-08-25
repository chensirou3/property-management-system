import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import DashboardConfigurationView from './DashboardConfigurationView.vue'
import VisitorRecordsView from './VisitorRecordsView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: ['PLATFORM_ADMIN'],
    permissions: ['dashboard:read', 'dashboard:configure', 'visitor:read', 'visitor:write', 'visitor:import', 'visitor:export-sensitive'],
    projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  'el-alert': { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-select': { template: '<select><slot /></select>' }, 'el-option': true,
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': true,
  'el-tag': { template: '<span><slot /></span>' }, 'el-icon': true,
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' }, 'el-form-item': { template: '<label><slot /></label>' },
  'el-input': true, 'el-input-number': true, 'el-switch': true, 'el-date-picker': true,
  'el-upload': { template: '<div><slot /></div>' }, 'el-pagination': true,
}

afterEach(() => vi.restoreAllMocks())

describe('G10 final page capabilities', () => {
  it('loads project and role scoped dashboard configuration from the governed API', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: {
      items: [{ id: 'widget-1', widget_code: 'ASSET_SUMMARY', widget_name: '资产概览', metric_code: 'ASSET_COUNTS',
        position_code: 'SUMMARY', visible: true, refresh_interval_seconds: 300, status: 'PUBLISHED', version: 0 }],
      availableMetrics: ['ASSET_COUNTS'],
    } } as any)
    const wrapper = mount(DashboardConfigurationView, { global: {
      plugins: [projectPinia()], directives: { loading: () => undefined }, stubs,
    } })
    await flushPromises()

    expect(wrapper.text()).toContain('看板配置')
    expect(wrapper.text()).toContain('编辑与排序生成草稿')
    expect(wrapper.text()).toContain('发布配置')
    expect(get).toHaveBeenCalledWith('/dashboard/configurations', {
      params: { communityId: 'project-1', roleCode: 'ALL' },
    })
  })

  it('loads masked visitor records and states the simulator-only boundary', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { items: [], total: 0,
      adapterCode: 'IOT_SIMULATOR', productionConnected: false } } as any)
    const wrapper = mount(VisitorRecordsView, { global: {
      plugins: [projectPinia()], directives: { loading: () => undefined }, stubs,
    } })
    await flushPromises()

    expect(wrapper.text()).toContain('访客记录')
    expect(wrapper.text()).toContain('当前仅使用 IOT_SIMULATOR')
    expect(wrapper.text()).toContain('登记访客')
    expect(get).toHaveBeenCalledWith('/visitors', { params: expect.objectContaining({
      communityId: 'project-1', page: 1, size: 20,
    }) })
  })
})
