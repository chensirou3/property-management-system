import { describe, expect, it } from 'vitest'
import { pageCatalog, pageFor, type PageState } from './pageCatalog'

const requiredStates: PageState[] = [
  'normal',
  'loading',
  'empty',
  'forbidden',
  'validation-error',
  'request-error',
  'conflict',
  'partial-failure',
]

describe('49-page delivery catalog', () => {
  it('contains exactly the frozen 49 pages in sequence', () => {
    expect(pageCatalog).toHaveLength(49)
    expect(pageCatalog.map((page) => page.pageNo)).toEqual(Array.from({ length: 49 }, (_, index) => index + 1))
  })

  it('has unique stable paths, titles and source references', () => {
    for (const key of ['path', 'title', 'sourceRef', 'targetPath'] as const) {
      expect(new Set(pageCatalog.map((page) => page[key])).size, `${key} must be unique`).toBe(49)
    }
    expect(pageCatalog.every((page) => page.path.startsWith('/') && !page.path.endsWith('/'))).toBe(true)
  })

  it('defines query, columns, permissions, operations, states and acceptance assumptions per page', () => {
    for (const page of pageCatalog) {
      if (page.path === '/finance/bank-trust') {
        // BANK_TRUST is an explicit capability-only simulator until a bank
        // protocol is authorized; exposing inert batch filters would be false.
        expect(page.query, `${page.title} capability-only query model`).toEqual([])
      } else {
        expect(page.query.length, `${page.title} query model`).toBeGreaterThan(0)
      }
      expect(page.columns.length, `${page.title} column model`).toBeGreaterThan(0)
      expect(page.permissions.read, `${page.title} read permission`).toMatch(/^[a-z-]+:[a-z-]+$/)
      expect(page.operations.length, `${page.title} operations`).toBeGreaterThan(0)
      expect(page.implementation, `${page.title} implementation`).not.toBe('generic-crud')
      expect(page.sourceRef, `${page.title} source`).toContain(`#${String(page.pageNo).padStart(2, '0')}`)
      expect(page.targetPath, `${page.title} target path`).toMatch(/^\/[A-Za-z0-9/-]+$/)
      expect(page.assumptions.trim(), `${page.title} assumptions`).not.toBe('')
      expect(page.requiredStates).toEqual(requiredStates)
    }
  })

  it('keeps field keys unique inside each page model', () => {
    for (const page of pageCatalog) {
      expect(new Set(page.query.map((field) => field.key)).size, `${page.title} query keys`).toBe(page.query.length)
      expect(new Set(page.columns.map((field) => field.key)).size, `${page.title} column keys`).toBe(page.columns.length)
    }
  })

  it('provides direct path lookup', () => {
    expect(pageFor('/cashier')?.pageNo).toBe(12)
    expect(pageFor('/security/visitor-records')?.pageNo).toBe(49)
    expect(pageFor('/not-in-scope')).toBeUndefined()
  })
})
