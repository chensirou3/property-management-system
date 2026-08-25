import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTaskStore } from './tasks'

describe('asynchronous task center', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => vi.useRealTimers())

  it('runs a CSV export through queued, running and succeeded states', async () => {
    const store = useTaskStore()
    const id = store.createExportTask({
      title: '客户导出', sourcePath: '/archives/customers', fileName: 'customers.csv',
      columns: [{ key: 'name', label: '客户名称' }], rows: [{ name: '合成客户甲' }, { name: '=unsafe' }],
    })

    expect(store.tasks[0]).toMatchObject({ id, status: 'QUEUED', progress: 0 })
    await vi.advanceTimersByTimeAsync(50)
    expect(store.tasks[0]).toMatchObject({ status: 'SUCCEEDED', progress: 100, totalRows: 2, successRows: 2, failedRows: 0, fileName: 'customers.csv' })
    expect(store.tasks[0].message).toContain('2 行 CSV')
  })

  it('reports partial CSV import validation without writing business data', async () => {
    const store = useTaskStore()
    store.createImportValidationTask({
      title: '房产导入校验', sourcePath: '/archives/rooms',
      file: new File(['房产编码,房产名称\nR001,一号房\nR002'], 'rooms.csv', { type: 'text/csv' }),
      expectedHeaders: ['房产编码', '房产名称'],
    })

    await vi.advanceTimersByTimeAsync(50)
    expect(store.tasks[0]).toMatchObject({ status: 'PARTIAL_FAILED', totalRows: 2, successRows: 1, failedRows: 1 })
    expect(store.tasks[0].message).toContain('未写入业务数据')
    expect(store.tasks[0].fileName).toContain('校验错误')
  })

  it('supports cancelling a queued task', () => {
    const store = useTaskStore()
    const id = store.createPrintTask({
      title: '欠费打印', sourcePath: '/finance/arrears', fileName: 'arrears.html',
      columns: [{ key: 'amount', label: '欠费金额' }], rows: [{ amount: 100 }],
    })

    store.cancel(id)
    expect(store.tasks[0]).toMatchObject({ status: 'CANCELLED', progress: 100 })
    expect(store.activeCount).toBe(0)
  })
})
