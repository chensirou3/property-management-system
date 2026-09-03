import { describe, expect, it } from 'vitest'
import type { PageField } from './pageCatalog'
import { canonicalReportFilterKeys, reportFilterContractByCode, serializeReportFilters } from './reportFilters'
import { pageFor } from './pageCatalog'
import { reportCodeByPath } from './reporting'

const field = (key: string, type: PageField['type'] = 'select'): PageField => ({ key, label: key, type })

describe('report filter serialization', () => {
  it('keeps independent scalar selectors instead of overwriting status', () => {
    expect(serializeReportFilters([
      field('transactionNo', 'text'), field('paymentChannel'), field('status'),
    ], {
      transactionNo: 'TX-001', paymentChannel: 'CASH', status: 'SUCCESS',
    })).toEqual({ transactionNo: 'TX-001', paymentChannel: 'CASH', status: 'SUCCESS' })
  })

  it('normalizes date and month ranges to distinct canonical fields', () => {
    expect(serializeReportFilters([
      field('dateRange', 'date-range'), field('billingPeriodRange', 'month-range'),
      field('arrearsPeriodRange', 'month-range'),
    ], {
      dateRange: ['2026-01-01', '2026-01-31'],
      billingPeriodRange: ['2026-01', '2026-02'],
      arrearsPeriodRange: ['2025-01', '2025-12'],
    })).toEqual({
      dateFrom: '2026-01-01', dateTo: '2026-01-31',
      billingPeriodFrom: '2026-01', billingPeriodTo: '2026-02',
      arrearsPeriodFrom: '2025-01', arrearsPeriodTo: '2025-12',
    })
  })

  it('maps a single billing period to an inclusive exact range and omits blanks', () => {
    expect(serializeReportFilters([
      field('billingPeriod', 'month'), field('channel'), field('deliveryStatus'),
    ], { billingPeriod: '2026-07', channel: '', deliveryStatus: 'SENT_SIMULATED' })).toEqual({
      billingPeriodFrom: '2026-07', billingPeriodTo: '2026-07', deliveryStatus: 'SENT_SIMULATED',
    })
  })

  it('does not expose filters that lack a truthful current domain relation', () => {
    expect(pageFor('/reports/collection-rate')?.query.map((item) => item.key)).not.toContain('organizationScope')
    expect(pageFor('/finance/arrears')?.query.map((item) => item.key)).not.toContain('communityId')
    expect(pageFor('/finance/bank-trust')?.query).toEqual([])
    expect(canonicalReportFilterKeys(pageFor('/reports/collection-rate')?.query || []))
      .toEqual(['billingPeriodFrom', 'billingPeriodTo', 'feeDefinitionId'])
  })

  it('maps every one of the 22 report pages to the server allow-list', () => {
    expect(Object.keys(reportFilterContractByCode)).toHaveLength(22)
    expect(Object.keys(reportCodeByPath)).toHaveLength(22)
    for (const [path, code] of Object.entries(reportCodeByPath)) {
      const frontendKeys = canonicalReportFilterKeys(pageFor(path)?.query || [])
      const serverKeys = reportFilterContractByCode[code]
      expect(serverKeys, `${code} must have a declared server contract`).toBeDefined()
      expect(serverKeys, `${code} must accept every rendered filter`).toEqual(expect.arrayContaining(frontendKeys))
      const apiOnly = serverKeys.filter((key) => !frontendKeys.includes(key))
      expect(apiOnly, `${code} has an unexpected API-only filter`).toEqual(
        code === 'DAILY_SETTLEMENT_DETAILS' ? ['dateFrom', 'dateTo'] : [],
      )
    }
  })
})
