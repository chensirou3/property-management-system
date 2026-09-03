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
    permissions: ['finance:read', 'finance:export', 'finance:print', 'finance:write', 'report:read', 'report:export',
      'notification:read', 'notification:send', 'bank:read', 'bank:export'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  FinancialOperationsView: { template: '<div>业务处理工作台</div>' },
  QueryPanel: { emits: ['save', 'restore'], template: '<div><slot /><button class="save-filter" @click="$emit(\'save\')">保存</button><button class="restore-filter" @click="$emit(\'restore\')">恢复</button></div>' },
  BatchActionBar: { template: '<div><slot /></div>' },
  DataGrid: { props: ['rows', 'columns', 'page', 'pageSize', 'rowKey'],
    emits: ['update:page', 'update:pageSize', 'selection-change', 'visible-columns-change'],
    template: '<section :data-row-key="rowKey"><slot name="toolbar" /><div v-for="row in rows">{{ JSON.stringify(row) }}</div><button class="next-page" @click="$emit(\'update:page\', 2)">下一页</button><button class="page-size" @click="$emit(\'update:pageSize\', 100)">每页100条</button><button class="visible-subset" @click="$emit(\'visible-columns-change\', [\'assetName\', \'arrearsAmount\'])">设置可见列</button><button class="select-first" @click="$emit(\'selection-change\', [rows[0]])">选择首行</button></section>' },
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

afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

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
      communityId: 'project-1', reportCode: 'BANK_TRUST', format: 'XLSX',
      selectedColumns: ['trustNo', 'bankChannel', 'submittedCount', 'submittedAmount',
        'successCount', 'successAmount', 'reconcileStatus'],
    }), expect.objectContaining({ headers: expect.any(Object) }))
    wrapper.unmount()
  })

  it('exports only default or customized columns currently visible to the user', async () => {
    vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/ARREARS') return { data: {
        ...reportResult, reportCode: 'ARREARS', columns: ['billId', 'assetName', 'customerName', 'feeName',
          'billingPeriod', 'receivableAmount', 'arrearsAmount', 'arrearsDays'],
        rows: [{ billId: 'internal-bill-1', assetName: '1-1-101', customerName: '张*', feeName: '物业费',
          billingPeriod: '2026-07', receivableAmount: 100, arrearsAmount: 30, arrearsDays: 15 }],
      } } as any
      return { data: [] } as any
    })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 'job-2', status: 'QUEUED' } } as any)
    const wrapper = await mountAt('/finance/arrears', '欠费明细表')

    const exportButton = wrapper.findAll('button').find((button) => button.text().includes('异步导出'))
    await exportButton!.trigger('click'); await flushPromises()
    const defaultRequest = post.mock.calls.find(([path]) => path === '/report-jobs')?.[1] as any
    expect(defaultRequest.selectedColumns).toEqual(['assetName', 'customerName', 'feeName', 'billingPeriod',
      'receivableAmount', 'arrearsAmount', 'arrearsDays'])
    expect(defaultRequest.selectedColumns).not.toContain('billId')

    post.mockClear()
    await wrapper.get('.visible-subset').trigger('click')
    await exportButton!.trigger('click'); await flushPromises()

    const request = post.mock.calls.find(([path]) => path === '/report-jobs')?.[1] as any
    expect(request.selectedColumns).toEqual(['assetName', 'arrearsAmount'])
    expect(request.selectedColumns).not.toContain('billId')

    const auth = useAuthStore()
    auth.projects.push({ id: 'project-2', name: '隔离项目', status: 'ACTIVE' })
    auth.currentProjectId = 'project-2'
    await flushPromises()
    post.mockClear()
    await exportButton!.trigger('click'); await flushPromises()

    const switchedProjectRequest = post.mock.calls.find(([path]) => path === '/report-jobs')?.[1] as any
    expect(switchedProjectRequest.communityId).toBe('project-2')
    expect(switchedProjectRequest.selectedColumns).toEqual(['assetName', 'arrearsAmount'])
    expect(switchedProjectRequest.selectedColumns).not.toContain('billId')
    wrapper.unmount()
  })

  it('isolates saved filters by project and restores only the current report fields with replacement semantics', async () => {
    vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/ARREARS') return { data: { ...reportResult, reportCode: 'ARREARS' } } as any
      return { data: [] } as any
    })
    const wrapper = await mountAt('/finance/arrears', '欠费明细表')
    const auth = useAuthStore()

    ;(wrapper.vm as any).values.feeDefinitionId = 'project-1-fee'
    ;(wrapper.vm as any).values.keyword = '主项目条件'
    await wrapper.get('.save-filter').trigger('click')
    auth.projects.push({ id: 'project-2', name: '隔离项目', status: 'ACTIVE' })
    auth.currentProjectId = 'project-2'
    await flushPromises()

    expect((wrapper.vm as any).values).toEqual({})
    localStorage.setItem('pms-report-filter:project-2:/finance/arrears', JSON.stringify({
      feeDefinitionId: 'project-2-fee', keyword: '当前项目', unknownField: 'must-not-restore',
    }))
    ;(wrapper.vm as any).values.billingPeriod = ['2026-01', '2026-02']
    await wrapper.get('.restore-filter').trigger('click')
    await flushPromises()

    expect((wrapper.vm as any).values).toEqual({ feeDefinitionId: 'project-2-fee', keyword: '当前项目' })
    expect((wrapper.vm as any).values.feeDefinitionId).not.toBe('project-1-fee')
    expect((wrapper.vm as any).values.unknownField).toBeUndefined()
    wrapper.unmount()
  })

  it('ignores a stale report response that completes after the newly selected project', async () => {
    let resolvePrimary!: (value: any) => void
    let resolveIsolated!: (value: any) => void
    const primary = new Promise((resolve) => { resolvePrimary = resolve })
    const isolated = new Promise((resolve) => { resolveIsolated = resolve })
    vi.spyOn(http, 'get').mockImplementation(async (path: string, config?: any) => {
      if (path === '/reports/COLLECTION_RATE') {
        return config?.params?.communityId === 'project-1' ? primary as any : isolated as any
      }
      return { data: [] } as any
    })
    const wrapper = await mountAt('/reports/collection-rate', '收缴率报表')
    const auth = useAuthStore()
    auth.projects.push({ id: 'project-2', name: '隔离项目', status: 'ACTIVE' })
    auth.currentProjectId = 'project-2'
    await flushPromises()

    resolveIsolated({ data: { ...reportResult, rows: [{ scopeName: '隔离项目', receivableAmount: 1 }] } })
    await flushPromises()
    resolvePrimary({ data: { ...reportResult, rows: [{ scopeName: '主项目旧响应', receivableAmount: 359 }] } })
    await flushPromises()

    expect(wrapper.text()).toContain('隔离项目')
    expect(wrapper.text()).not.toContain('主项目旧响应')
    wrapper.unmount()
  })

  it('keeps the non-rendered receipt identifier available for batch printing', async () => {
    vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/RECEIPT_BATCH_PRINT') return { data: {
        ...reportResult, reportCode: 'RECEIPT_BATCH_PRINT', columns: ['receiptId', 'receiptNo', 'paidAt',
          'assetName', 'customerName', 'amount', 'printCount', 'status'],
        rows: [{ receiptId: 'receipt-1', receiptNo: 'R-001', paidAt: '2026-07-01T10:00:00',
          assetName: '1-1-101', customerName: '张*', amount: 100, printCount: 0, status: 'ISSUED' }],
      } } as any
      return { data: [] } as any
    })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 'print-job-1', status: 'QUEUED' } } as any)
    const wrapper = await mountAt('/finance/receipt-batch-print', '批量打印收据')

    await wrapper.get('.select-first').trigger('click')
    const printButton = wrapper.findAll('button').find((button) => button.text().includes('批量打印所选'))
    await printButton!.trigger('click'); await flushPromises()

    expect(post).toHaveBeenCalledWith('/receipt-print-jobs', {
      communityId: 'project-1', receiptIds: ['receipt-1'], format: 'PDF',
    }, expect.objectContaining({ headers: expect.any(Object) }))
    wrapper.unmount()
  })

  it('loads project-scoped names for identifier filters instead of requiring UUID input', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/TRANSACTION_SUMMARY') return { data: { ...reportResult, reportCode: 'TRANSACTION_SUMMARY' } } as any
      if (path === '/reports/filter-options') return { data: {
        feeDefinitionId: [{ value: 'fee-1', label: 'WYF · 物业费' }],
        cashierId: [{ value: 'cashier-1', label: '张收银（工号 C001）' }],
      } } as any
      return { data: [] } as any
    })
    const wrapper = await mountAt('/reports/transaction-summary', '交易汇总')

    expect(get).toHaveBeenCalledWith('/reports/filter-options', {
      params: { communityId: 'project-1', reportCode: 'TRANSACTION_SUMMARY' },
    })
    const options = wrapper.findAll('el-option-stub')
    expect(options.some((option) => option.attributes('label') === '张收银（工号 C001）'
      && option.attributes('value') === 'cashier-1')).toBe(true)
    expect(options.some((option) => option.attributes('value') === 'SIMULATOR')).toBe(true)
    expect(wrapper.find('select').attributes()).not.toHaveProperty('allow-create')
    wrapper.unmount()
  })

  it('uses the selected billing period when sending a simulated notification', async () => {
    vi.spyOn(http, 'get').mockImplementation(async (path: string) => {
      if (path === '/reports/BILL_NOTIFICATIONS') return { data: { ...reportResult, reportCode: 'BILL_NOTIFICATIONS', rows: [] } } as any
      return { data: [] } as any
    })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 'notification-1', status: 'SUCCEEDED' } } as any)
    const wrapper = await mountAt('/finance/bill-notifications', '账单通知')
    ;(wrapper.vm as any).values.billingPeriod = '2026-07'

    const notifyButton = wrapper.findAll('button').find((button) => button.text().includes('发送模拟通知'))
    await notifyButton!.trigger('click'); await flushPromises()
    expect(post).toHaveBeenCalledWith('/notification-batches', expect.objectContaining({
      communityId: 'project-1', billingPeriod: '2026-07', channel: 'SMS_SIMULATOR',
    }), expect.objectContaining({ headers: expect.any(Object) }))
    wrapper.unmount()
  })
})
