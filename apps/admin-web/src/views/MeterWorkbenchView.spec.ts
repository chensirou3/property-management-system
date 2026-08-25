import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import MeterWorkbenchView from './MeterWorkbenchView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: ['PLATFORM_ADMIN'],
    permissions: ['meter:read', 'meter:write'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': true, 'el-date-picker': true, 'el-select': { template: '<select><slot /></select>' },
  'el-option': true, 'el-icon': true, 'el-tag': { template: '<span><slot /></span>' },
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': true,
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' }, 'el-form-item': { template: '<label><slot /></label>' },
}

const context = {
  summary: { meterCount: 32, batchCount: 1, pendingReviewCount: 0, reconciliationMismatchCount: 0 },
  meters: [{ id: 'meter-1', meterNo: 'SYN-SUB-001', meterType: 'WATER', meterClass: 'SUB',
    status: 'ACTIVE', assetId: 'asset-1', assetName: '1-101', multiplier: 1, lossRate: 0, correction: 0 }],
  batches: [{ id: 'batch-1', batchNo: 'MR-001', readingPeriod: '2026-08', status: 'DRAFT' }],
  shareRules: [{ id: 'rule-1', name: '按面积公摊', activeVersionNo: 1 }],
  feeStandards: [{ id: 'standard-1', code: 'METER-WATER', name: '合成计量水费', unitPrice: 1.25 }],
}

async function mountAt(path: string, get: ReturnType<typeof vi.spyOn>) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: MeterWorkbenchView }] })
  await router.push(path)
  const wrapper = mount(MeterWorkbenchView, {
    global: { plugins: [projectPinia(), router], directives: { loading: () => undefined }, stubs },
  })
  await flushPromises()
  return { wrapper, get }
}

afterEach(() => vi.restoreAllMocks())

describe('G7 governed meter workbench', () => {
  it('replaces the generic meter page with typed master data and asset binding', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/meter-workbench') return { data: { ...context, batches: [] } } as any
      return { data: { items: [], total: 0 } } as any
    })
    const { wrapper } = await mountAt('/archives/meters', get)

    expect(wrapper.text()).toContain('仪表主档与资产绑定')
    expect(wrapper.text()).toContain('主档、跨期连续读数、异常复核')
    expect(get).toHaveBeenCalledWith('/meter-workbench', { params: { communityId: 'project-1' } })
    expect(get).toHaveBeenCalledWith('/data/assets', { params: expect.objectContaining({
      communityId: 'project-1', category: 'ROOM', page: 1, size: 200,
    }) })
  })

  it('loads batch detail from the typed API and exposes anomaly and IoT controls', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/meter-workbench') return { data: context } as any
      if (path === '/api-never') return { data: {} } as any
      if (path.startsWith('/meter-reading-batches/')) return { data: {
        batch: context.batches[0], readings: [], iotEvidence: [], reconciliation: [],
      } } as any
      return { data: { items: [], total: 0 } } as any
    })
    const { wrapper } = await mountAt('/metering/readings', get)

    expect(wrapper.text()).toContain('读数录入、IoT 证据与异常复核')
    expect(wrapper.text()).toContain('IoT 模拟入站')
    expect(wrapper.text()).toContain('审核批次')
    expect(get).toHaveBeenCalledWith('/meter-reading-batches/batch-1', {
      params: { communityId: 'project-1' },
    })
  })
})
