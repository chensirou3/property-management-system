import rawCatalog from './page-catalog.json'

export type PageWave = 'A' | 'B' | 'C' | 'D'
export type TargetLevel = 'L1' | 'L2' | 'L3'
export type PageState =
  | 'normal'
  | 'loading'
  | 'empty'
  | 'forbidden'
  | 'validation-error'
  | 'request-error'
  | 'conflict'
  | 'partial-failure'

export type FieldType =
  | 'text'
  | 'number'
  | 'money'
  | 'area'
  | 'percent'
  | 'status'
  | 'masked'
  | 'selection'
  | 'date'
  | 'datetime'
  | 'month'
  | 'date-range'
  | 'month-range'
  | 'select'
  | 'tree-select'
  | 'project'
  | 'asset-select'
  | 'customer-select'

export interface PageField {
  key: string
  label: string
  type: FieldType
}

export interface PagePermissions {
  read: string
  write?: string
  import?: string
  export?: string
  print?: string
}

export interface PageCatalogEntry {
  pageNo: number
  wave: PageWave
  domain: string
  path: string
  title: string
  implementation: string
  targetLevels: TargetLevel[]
  permissions: PagePermissions
  query: PageField[]
  columns: PageField[]
  operations: string[]
  requiredStates: PageState[]
  sourceRef: string
  targetPath: string
  assumptions: string
}

type RawPage = Omit<PageCatalogEntry, 'query' | 'columns' | 'requiredStates'> & {
  query: string[]
  columns: string[]
}

function parseField(token: string): PageField {
  const [key, label, type] = token.split('|')
  if (!key || !label || !type) throw new Error(`Invalid page field token: ${token}`)
  return { key, label, type: type as FieldType }
}

// These catalog controls currently have no truthful domain relation.  A report
// is already restricted by the project selected in the application shell, and
// the current schema does not map an organization unit to a subset of assets.
// BANK_TRUST is a capability sentinel rather than a persisted batch dataset.
// Hide the controls until those domain models exist instead of sending filters
// that the server would have to ignore.
const disabledReportFilters: Record<string, Set<string>> = {
  '/finance/arrears': new Set(['communityId']),
  '/reports/collection-rate': new Set(['organizationScope']),
  '/reports/arrears-clearance-rate': new Set(['organizationScope']),
  '/reports/collection-clearance-summary': new Set(['organizationScope']),
  '/reports/charge-details': new Set(['communityId']),
  '/reports/fee-status': new Set(['communityId']),
  '/reports/comprehensive-query': new Set(['organizationScope']),
  '/reports/ownership-transfers': new Set(['communityId']),
  '/finance/bank-trust': new Set(['dateRange', 'bankChannel', 'reconcileStatus']),
}

const requiredStates = rawCatalog.requiredStates as PageState[]

export const pageCatalog: PageCatalogEntry[] = (rawCatalog.pages as RawPage[]).map((page) => ({
  ...page,
  query: page.query.map(parseField).filter((field) => !disabledReportFilters[page.path]?.has(field.key)),
  columns: page.columns.map(parseField),
  requiredStates: [...requiredStates],
}))

export const pageCatalogByPath = new Map(pageCatalog.map((page) => [page.path, page]))

export function pageFor(path: string): PageCatalogEntry | undefined {
  return pageCatalogByPath.get(path)
}
