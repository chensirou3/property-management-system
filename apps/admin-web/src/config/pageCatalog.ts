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

const requiredStates = rawCatalog.requiredStates as PageState[]

export const pageCatalog: PageCatalogEntry[] = (rawCatalog.pages as RawPage[]).map((page) => ({
  ...page,
  query: page.query.map(parseField),
  columns: page.columns.map(parseField),
  requiredStates: [...requiredStates],
}))

export const pageCatalogByPath = new Map(pageCatalog.map((page) => [page.path, page]))

export function pageFor(path: string): PageCatalogEntry | undefined {
  return pageCatalogByPath.get(path)
}
