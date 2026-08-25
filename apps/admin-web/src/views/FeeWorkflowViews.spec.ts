import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import FeeConfigurationView from './FeeConfigurationView.vue'
import TemporaryReceivableView from './TemporaryReceivableView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: [],
    permissions: ['fee:read', 'fee:write'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  'el-alert': { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-form': { template: '<form><slot /></form>' }, 'el-form-item': { template: '<label><slot /></label>' },
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': true,
  'el-input': true, 'el-input-number': true, 'el-date-picker': true, 'el-switch': true,
  'el-button': { template: '<button><slot /></button>' }, 'el-tag': { template: '<span><slot /></span>' },
  'el-select': { template: '<select><slot /></select>' }, 'el-option': true, 'el-icon': true,
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-drawer': { template: '<section><slot name="header" /><slot /></section>' },
  'el-descriptions': { template: '<div><slot /></div>' }, 'el-descriptions-item': { template: '<div><slot /></div>' },
  StatusTag: { props: ['value'], template: '<span>{{ value }}</span>' },
}

afterEach(() => vi.restoreAllMocks())

describe('G5 fee workflow pages', () => {
  it('loads governed definitions and standards from typed APIs', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] } as any)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/fees/definitions', component: FeeConfigurationView }] })
    await router.push('/fees/definitions')
    const wrapper = mount(FeeConfigurationView, { global: { plugins: [projectPinia(), router], directives: { loading: () => undefined }, stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('费用定义与财税口径')
    expect(wrapper.text()).toContain('科目、税目、精度、舍入规则')
    expect(get).toHaveBeenCalledWith('/fees/definitions', { params: { communityId: 'project-1' } })
    expect(get).toHaveBeenCalledWith('/fees/standards', { params: { communityId: 'project-1' } })
  })

  it('renders the preview-first allocation workspace', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => ({ data: path === '/fees/allocations' ? [] : [] } as any))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/fees/allocations', component: FeeConfigurationView }] })
    await router.push('/fees/allocations')
    const wrapper = mount(FeeConfigurationView, { global: { plugins: [projectPinia(), router], directives: { loading: () => undefined }, stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('费用分配与生效范围')
    expect(wrapper.text()).toContain('预览命中范围')
    expect(wrapper.text()).toContain('已分配费用与有效期')
    expect(get).toHaveBeenCalledWith('/fees/allocations', { params: { communityId: 'project-1', feeStandardId: undefined } })
  })

  it('loads the independent temporary receivable workspace and job history', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/fees/definitions' || path === '/receivable-jobs') return { data: [] } as any
      return { data: { items: [], total: 0 } } as any
    })
    const wrapper = mount(TemporaryReceivableView, { global: { plugins: [projectPinia()], directives: { loading: () => undefined }, stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('临时应收使用独立手工数量与单价')
    expect(wrapper.text()).toContain('临时费用明细')
    expect(wrapper.text()).toContain('临时应收任务')
    expect(get).toHaveBeenCalledWith('/receivable-jobs', { params: { communityId: 'project-1', jobType: 'TEMPORARY' } })
  })
})
