import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import ReceivableWorkflowView from './ReceivableWorkflowView.vue'
import CashierView from './CashierView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  return pinia
}

const globalOptions = (pinia: ReturnType<typeof createPinia>) => ({
  plugins: [pinia],
  directives: { loading: () => undefined },
  stubs: {
    'el-alert': { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
    'el-card': { template: '<section><slot name="header" /><slot /></section>' },
    'el-form': { template: '<form><slot /></form>' },
    'el-form-item': { template: '<label><slot /></label>' },
    'el-table': { template: '<div><slot /></div>' },
    'el-table-column': true,
    'el-input': true,
    'el-date-picker': true,
    'el-button': { template: '<button><slot /></button>' },
    'el-tag': { template: '<span><slot /></span>' },
    'el-select': { template: '<select><slot /></select>' },
    'el-option': true,
    'el-icon': true,
    'el-result': true,
    'el-descriptions': true,
    'el-descriptions-item': true,
  },
})

afterEach(() => vi.restoreAllMocks())

describe('dedicated workflow pages', () => {
  it('loads project assets for the receivable preview workflow', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { items: [], total: 0 } } as any)
    const pinia = projectPinia()
    const wrapper = mount(ReceivableWorkflowView, { global: globalOptions(pinia) })
    await flushPromises()

    expect(wrapper.text()).toContain('选择账期与计费资产')
    expect(wrapper.text()).toContain('先试算')
    expect(get).toHaveBeenCalledWith('/data/assets', expect.objectContaining({
      params: expect.objectContaining({ communityId: 'project-1', category: 'ROOM' }),
    }))
  })

  it('loads unpaid bills and identifies the simulator boundary', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { bills: [], assets: [], customers: [] } } as any)
    const pinia = projectPinia()
    const wrapper = mount(CashierView, { global: globalOptions(pinia) })
    await flushPromises()

    expect(wrapper.text()).toContain('待收账单')
    expect(wrapper.text()).toContain('本地支付与开票模拟器')
    expect(get).toHaveBeenCalledWith('/cashier/context', expect.objectContaining({
      params: expect.objectContaining({ communityId: 'project-1' }),
    }))
  })
})
