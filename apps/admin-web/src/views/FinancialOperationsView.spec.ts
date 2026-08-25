import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import FinancialOperationsView from './FinancialOperationsView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: ['PLATFORM_ADMIN'],
    permissions: ['finance:read', 'finance:write'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': true, 'el-input-number': true, 'el-select': { template: '<select><slot /></select>' },
  'el-option': true, 'el-date-picker': true, 'el-icon': true,
  'el-tag': { template: '<span><slot /></span>' }, 'el-statistic': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-table': { props: ['data'], template: '<div><slot /></div>' },
  'el-table-column': { props: ['label'], template: '<span>{{ label }}<slot :row="{}" /></span>' },
  'el-pagination': true, 'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' }, 'el-form-item': { template: '<label><slot /></label>' },
}

async function mountAt(path: string, title: string, get: ReturnType<typeof vi.spyOn>) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: FinancialOperationsView, meta: { title } }] })
  await router.push(path)
  const wrapper = mount(FinancialOperationsView, { global: { plugins: [projectPinia(), router], directives: { loading: () => undefined }, stubs } })
  await flushPromises()
  return { wrapper, get }
}

afterEach(() => vi.restoreAllMocks())

describe('G6 financial workspaces', () => {
  it('loads governed bill balances rather than structural sample rows', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { items: [], total: 0 } } as any)
    const { wrapper } = await mountAt('/finance/bills', '应收管理', get)

    expect(wrapper.text()).toContain('应收管理')
    expect(wrapper.text()).toContain('原应收')
    expect(wrapper.text()).toContain('调整')
    expect(get).toHaveBeenCalledWith('/finance/bills', { params: expect.objectContaining({ communityId: 'project-1', size: 200 }) })
  })

  it('loads settlement preview, history and reconciliation together', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/finance/settlements') return { data: [] } as any
      if (path === '/finance/reconciliation') return { data: { healthy: true } } as any
      return { data: { transactionCount: 0, grossAmount: 0, reversalAmount: 0, netAmount: 0 } } as any
    })
    const { wrapper } = await mountAt('/reports/daily-settlement-details', '日结明细表', get)

    expect(wrapper.text()).toContain('执行日结')
    expect(wrapper.text()).toContain('对账 一致')
    expect(get).toHaveBeenCalledWith('/finance/settlements:preview', { params: expect.objectContaining({ communityId: 'project-1' }) })
    expect(get).toHaveBeenCalledWith('/finance/reconciliation', { params: { communityId: 'project-1' } })
  })

  it('loads real receipt inventory and number segments', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => ({ data: path === '/finance/receipt-segments'
      ? [{ id: 'segment-1', segment_code: 'SYN-2026', status: 'ACTIVE', remaining_count: 10 }]
      : [] } as any))
    const { wrapper } = await mountAt('/finance/instruments', '票据管理', get)

    expect(wrapper.text()).toContain('新增票据号段')
    expect(wrapper.text()).toContain('SYN-2026')
    expect(get).toHaveBeenCalledWith('/finance/receipts', { params: expect.objectContaining({ communityId: 'project-1' }) })
    expect(get).toHaveBeenCalledWith('/finance/receipt-segments', { params: { communityId: 'project-1' } })
  })
})
