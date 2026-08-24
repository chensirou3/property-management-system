import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import DashboardView from './DashboardView.vue'

describe('DashboardView', () => {
  it('shows audited synthetic inventory and the simulator boundary', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.projects = [{ id: 'project-1', name: '优山美地（合成示范项目）', status: 'ACTIVE' }]
    auth.currentProjectId = 'project-1'
    vi.spyOn(http, 'get').mockResolvedValueOnce({
      data: {
        counts: { rooms: '359', customers: 403, parking_spaces: '250', allocations: 743, fee_definitions: 22, fee_standards: 16, meters: 31 },
        finance: { receivable: '100.00', received: '50.00', outstanding: '50.00', collection_rate: '50.00' },
        quality: { orphan_customer_relations: 0, orphan_allocations: 0 },
        adapters: { payment: 'simulator' },
      },
    } as any)
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [pinia],
        directives: { loading: () => undefined },
        stubs: {
          'el-alert': true,
          'el-button': true,
          'el-icon': true,
          'el-radio-group': true,
          'el-radio-button': true,
          'el-progress': true,
        },
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('优山美地')
    expect(wrapper.text()).toContain('359')
    expect(wrapper.text()).toContain('模拟')
  })
})
