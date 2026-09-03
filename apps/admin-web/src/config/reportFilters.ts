import type { PageField } from './pageCatalog'

export type ReportFilterValues = Record<string, unknown>

// Mirrors the server's per-report allow-list. Keeping the complete map here
// makes every catalog control auditable instead of relying on a generic set of
// query parameters that may be silently ignored by one report.
export const reportFilterContractByCode: Record<string, readonly string[]> = {
  TRANSACTION_SUMMARY: ['dateFrom', 'dateTo', 'paymentChannel', 'cashierId'],
  TRANSACTION_DETAILS: ['dateFrom', 'dateTo', 'transactionNo', 'paymentChannel', 'status'],
  RECEIPT_BATCH_PRINT: ['dateFrom', 'dateTo', 'receiptStatus', 'cashierId', 'keyword'],
  PAYMENTS: ['dateFrom', 'dateTo', 'keyword', 'status'],
  ARREARS: ['billingPeriodFrom', 'billingPeriodTo', 'feeDefinitionId', 'keyword'],
  BILL_NOTIFICATIONS: ['billingPeriodFrom', 'billingPeriodTo', 'channel', 'deliveryStatus'],
  BILLS: ['billingPeriodFrom', 'billingPeriodTo', 'keyword', 'status'],
  COLLECTION_RATE: ['billingPeriodFrom', 'billingPeriodTo', 'feeDefinitionId'],
  ARREARS_CLEARANCE_RATE: ['dateFrom', 'dateTo', 'arrearsPeriodFrom', 'arrearsPeriodTo'],
  COMPREHENSIVE_QUERY: ['subjectType', 'dateFrom', 'dateTo', 'keyword'],
  COLLECTION_CLEARANCE_SUMMARY: ['dateFrom', 'dateTo', 'feeDefinitionId'],
  CHARGE_DETAILS: ['dateFrom', 'dateTo', 'feeDefinitionId', 'paymentChannel'],
  DISCOUNT_DETAILS: ['dateFrom', 'dateTo', 'discountType', 'keyword'],
  PREPAYMENTS: ['dateFrom', 'dateTo', 'keyword', 'entryType'],
  OWNERSHIP_TRANSFERS: ['dateFrom', 'dateTo', 'keyword'],
  REMINDERS: ['dateFrom', 'dateTo', 'reminderType', 'deliveryStatus', 'keyword'],
  FEE_STATUS: ['billingPeriodFrom', 'billingPeriodTo', 'feeDefinitionId'],
  INVOICE_STATISTICS: ['dateFrom', 'dateTo', 'invoiceType', 'invoiceStatus'],
  DEPOSITS: ['dateFrom', 'dateTo', 'keyword', 'status'],
  DAILY_SETTLEMENT_DETAILS: ['settlementDate', 'dateFrom', 'dateTo', 'cashierId', 'paymentChannel'],
  ADJUSTMENTS: ['dateFrom', 'dateTo', 'adjustmentType', 'status', 'keyword'],
  BANK_TRUST: [],
}

export function canonicalReportFilterKeys(fields: PageField[]) {
  return fields.flatMap((field) => {
    if (field.key === 'billingPeriodRange' || field.key === 'billingPeriod') {
      return ['billingPeriodFrom', 'billingPeriodTo']
    }
    if (field.key === 'arrearsPeriodRange') return ['arrearsPeriodFrom', 'arrearsPeriodTo']
    if (field.key === 'dateRange') return ['dateFrom', 'dateTo']
    if (field.type === 'date-range' || field.type === 'month-range') {
      return [`${field.key}From`, `${field.key}To`]
    }
    return [field.key]
  })
}

/**
 * Translate catalog field names to the canonical API filter contract.
 *
 * Scalar fields deliberately keep their own names.  In particular, a
 * selector must never be collapsed into a generic `status` parameter: many
 * reports have two independent selectors and the backend applies them with
 * AND semantics.
 */
export function serializeReportFilters(fields: PageField[], values: ReportFilterValues) {
  const result: Record<string, string> = {}
  for (const field of fields) {
    const raw = values[field.key]
    if (raw === undefined || raw === null || raw === '') continue
    if (Array.isArray(raw)) {
      if (raw.length < 2 || !raw[0] || !raw[1]) continue
      const [from, to] = raw.map(String)
      if (field.key === 'billingPeriodRange') {
        result.billingPeriodFrom = from
        result.billingPeriodTo = to
      } else if (field.key === 'arrearsPeriodRange') {
        result.arrearsPeriodFrom = from
        result.arrearsPeriodTo = to
      } else if (field.key === 'dateRange') {
        result.dateFrom = from
        result.dateTo = to
      } else {
        result[`${field.key}From`] = from
        result[`${field.key}To`] = to
      }
      continue
    }
    const value = String(raw).trim()
    if (!value) continue
    if (field.key === 'billingPeriod') {
      result.billingPeriodFrom = value
      result.billingPeriodTo = value
    } else {
      result[field.key] = value
    }
  }
  return result
}
