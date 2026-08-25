import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http } from '../api/http'
import { governedReportingPaths, reportCodeByPath } from '../config/reporting'
import { useAuthStore } from '../stores/auth'
import ReportingWorkbenchView from './ReportingWorkbenchView.vue'

function projectPinia() {
  const pinia = createPinia(); setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: ['PLATFORM_ADMIN'],
    permissions: ['finance:read', 'finance:export', 'finance:print', 'finance:write', 'bank:read', 'bank:export'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  FinancialOperationsView: { template: '<div>业务处理工作台</div>' },
  QueryPanel: { template: '<div><slot /></div>' },
  BatchActionBar: { template: '<div><slot /></div>' },
  DataGrid: { props: ['rows', 'page', 'pageSize', 'rowKey'], emits: ['update:page', 'update:pageSize'],
    template: '<section :data-row-key="rowKey"><slot name="toolbar" /><div v-for="row in rows">{{ JSON.stringify(row) }}</div><button class="next-page" @click="$emit(\'update:page\', 2)">下一页</button><button class="page-size" @click="$emit(\'update:pageSize\', 100)">每页100条</button></section>' },
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-input': true, 'el-date-picker': true, 'el-form-item': { template: '<label><slot /></label>' },
  'el-icon': true, 'el-tag': { template: '<span><slot /></span>' }, 'el-statistic': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-select': { template: '<select><slot /></select>' }, 'el-option': true,
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': true,
}

const reportResult = {
  reportCode: 'COLLECTION_RATE', title: '收缴率报表', rowGrain: '项目汇总', formulaNote: '收缴率=已收/应收×100',
  fixedSample: { receivableAmount: 100, collectedAmount: 80, collectionRate: 80 },
  queryChecksum: 'a'.repeat(64), durationMs: 12, total: 1, page: 1, size: 50,
  summary: { rowCount: 1, numericTotals: { receivableAmount: 100 } },
  rows: [{ scopeName: '合成项目', receivableAmount: 100, collectedAmount: 80, outstandingAmount: 20, collectionRate: 80 }],
}

async function mountAt(path: string, title: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: ReportingWorkbenchView, meta: { title } }] })
  await router.push(path)
  const wrapper = mount(ReportingWorkbenchView, { global: { plugins: [projectPinia(), router], stubs } })
  await flushPromises()
  return wrapper
}

afterEach(() => vi.restoreAllMocks())

describe('G8 governed reporting workbench', () => {
  it('maps all 22 query navigation entries to unique executable report codes', () => {
    expect(governedReportingPaths).toHaveLength(22)
    expect(new Set(Object.values(reportCodeByPath)).size).toBe(22)
    expect(reportCodeByPath['/finance/bank-trust']).toBe('BANK_TRUST')
    expect(reportCodeByPath['/reports/comprehensive-query']).toBe('COMPREHENSIVE_QUERY')
  })

  it('renders formula evidence and backend rows instead of browser structural samples', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/COLLECTION_RATE') return { data: reportResult } as any
      return { data: [] } as any
    })
    const wrapper = await mountAt('/reports/collection-rate', '收缴率报表')

    expect(wrapper.text()).toContain('收缴率=已收/应收×100')
    expect(wrapper.text()).toContain('项目汇总')
    expect(wrapper.text()).toContain('合成项目')
    expect(wrapper.text()).toContain('aaaaaaaa')
    expect(get).toHaveBeenCalledWith('/reports/COLLECTION_RATE', { params: expect.objectContaining({ communityId: 'project-1', page: 1, size: 50 }) })
    wrapper.unmount()
  })

  it('reloads the governed backend read model when page or page size changes', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/COLLECTION_RATE') return { data: { ...reportResult, total: 240 } } as any
      return { data: [] } as any
    })
    const wrapper = await mountAt('/reports/collection-rate', '收缴率报表')

    await wrapper.get('.next-page').trigger('click'); await flushPromises()
    expect(get).toHaveBeenCalledWith('/reports/COLLECTION_RATE', {
      params: expect.objectContaining({ communityId: 'project-1', page: 2, size: 50 }),
    })
    await wrapper.get('.page-size').trigger('click'); await flushPromises()
    expect(get).toHaveBeenCalledWith('/reports/COLLECTION_RATE', {
      params: expect.objectContaining({ communityId: 'project-1', page: 1, size: 100 }),
    })
    wrapper.unmount()
  })

  it('queues backend exports and clearly labels the bank adapter as simulator only', async () => {
    vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/BANK_TRUST') return { data: { ...reportResult, reportCode: 'BANK_TRUST',
        title: '银行信托', rowGrain: '模拟托收批次', formulaNote: '仅模拟器', rows: [], total: 0 } } as any
      return { data: [] } as any
    })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 'job-1', status: 'QUEUED' } } as any)
    const wrapper = await mountAt('/finance/bank-trust', '银行信托')

    expect(wrapper.text()).toContain('BANK_TRUST_SIMULATOR')
    const exportButton = wrapper.findAll('button').find((button) => button.text().includes('异步导出'))
    await exportButton!.trigger('click'); await flushPromises()
    expect(post).toHaveBeenCalledWith('/report-jobs', expect.objectContaining({
      communityId: 'project-1', reportCode: 'BANK_TRUST', format: 'XLSX', selectedColumns: [],
    }), expect.objectContaining({ headers: expect.any(Object) }))
    wrapper.unmount()
  })
})
