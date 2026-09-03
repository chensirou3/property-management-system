import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import DashboardView from './DashboardView.vue'

function mountDashboard(projectName: string, responseData: Record<string, any>) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: projectName, status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  vi.spyOn(http, 'get').mockResolvedValueOnce({ data: responseData } as any)
  return mount(DashboardView, {
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
}

describe('DashboardView', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows audited synthetic inventory and the simulator boundary', async () => {
    const wrapper = mountDashboard('优山美地（合成示范项目）', {
      counts: { rooms: '359', customers: 403, parking_spaces: '250', allocations: 773,
        asset_allocations: 743, meter_allocations: 30, fee_definitions: 22, fee_standards: 16, meters: 31 },
      finance: { receivable: '100.00', received: '50.00', outstanding: '50.00', collection_rate: '50.00' },
      quality: { room_detail_mismatches: 0, orphan_customer_relations: 0, orphan_allocations: 0 },
      adapters: { payment: 'simulator' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('优山美地')
    expect(wrapper.text()).toContain('359')
    expect(wrapper.text()).toContain('743 条资产分配 · 30 条仪表分配')
    expect(wrapper.find('.quality-row').text()).toContain('房屋主档与明细一致性已通过')
    expect(wrapper.text()).toContain('31 条（实时统计）')
    expect(wrapper.text()).toContain('模拟')
  })

  it('uses project-scoped violation counts instead of fixed inventory targets', async () => {
    const wrapper = mountDashboard('海湾雅居（合成隔离项目）', {
      counts: { rooms: 1, customers: 1, parking_spaces: 1, allocations: 0,
        asset_allocations: 0, meter_allocations: 0, fee_definitions: 0, fee_standards: 0, meters: 1 },
      finance: { receivable: 0, received: 0, outstanding: 0, collection_rate: 0 },
      quality: { room_detail_mismatches: 0, orphan_customer_relations: 0, orphan_allocations: 2 },
      adapters: { payment: 'simulator' },
    })
    await flushPromises()

    const rows = wrapper.findAll('.quality-row')
    expect(rows.find((row) => row.text().includes('房屋主档与明细一致性'))?.text()).toContain('已通过')
    expect(rows.find((row) => row.text().includes('费用分配无孤儿键'))?.text()).toContain('存在异常（2 项）')
    expect(rows.find((row) => row.text().includes('仪表主档数量'))?.text()).toContain('1 条（实时统计）')
  })

  it('does not report missing quality data as passed', async () => {
    const wrapper = mountDashboard('空白项目', {
      counts: { rooms: 0, customers: 0, parking_spaces: 0, allocations: 0,
        asset_allocations: 0, meter_allocations: 0, fee_definitions: 0, fee_standards: 0, meters: 0 },
      finance: { receivable: 0, received: 0, outstanding: 0, collection_rate: 0 },
      quality: { room_detail_mismatches: null, orphan_customer_relations: undefined, orphan_allocations: '' },
      adapters: { payment: 'simulator' },
    })
    await flushPromises()

    expect(wrapper.findAll('.quality-row').slice(0, 3).every((row) => row.text().includes('待加载'))).toBe(true)
  })

  it('ignores an older project response that completes after the current project response', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.projects = [
      { id: 'project-1', name: '主项目', status: 'ACTIVE' },
      { id: 'project-2', name: '隔离项目', status: 'ACTIVE' },
    ]
    auth.currentProjectId = 'project-1'
    let resolvePrimary!: (value: any) => void
    let resolveIsolated!: (value: any) => void
    const primary = new Promise((resolve) => { resolvePrimary = resolve })
    const isolated = new Promise((resolve) => { resolveIsolated = resolve })
    vi.spyOn(http, 'get').mockImplementation(async (_path: string, config?: any) => (
      config?.params?.communityId === 'project-1' ? primary as any : isolated as any
    ))
    const wrapper = mount(DashboardView, {
      global: { plugins: [pinia], directives: { loading: () => undefined }, stubs: {
        'el-alert': true, 'el-button': true, 'el-icon': true, 'el-radio-group': true,
        'el-radio-button': true, 'el-progress': true,
      } },
    })
    auth.currentProjectId = 'project-2'
    await flushPromises()
    resolveIsolated({ data: {
      counts: { rooms: 1 }, finance: {}, quality: { room_detail_mismatches: 0, orphan_customer_relations: 0, orphan_allocations: 0 }, adapters: {},
    } })
    await flushPromises()
    resolvePrimary({ data: {
      counts: { rooms: 359 }, finance: {}, quality: { room_detail_mismatches: 0, orphan_customer_relations: 0, orphan_allocations: 0 }, adapters: {},
    } })
    await flushPromises()

    expect(wrapper.text()).toContain('隔离项目')
    expect(wrapper.text()).toContain('1套')
    expect(wrapper.text()).not.toContain('359')
    wrapper.unmount()
  })
})
