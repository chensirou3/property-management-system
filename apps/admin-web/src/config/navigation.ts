import type { Component } from 'vue'
import {
  Coin,
  CreditCard,
  DataAnalysis,
  Files,
  Grid,
  House,
  Menu,
  Money,
  OfficeBuilding,
  Operation,
  Setting,
  Stopwatch,
  Tools,
  User,
  Van,
} from '@element-plus/icons-vue'
import { pageFor } from './pageCatalog'

export interface NavigationItem {
  path: string
  title: string
  icon?: Component
  permission?: string
  scope?: 'target' | 'auxiliary'
}

export interface NavigationGroup {
  key: string
  title: string
  icon: Component
  children: NavigationItem[]
}

function target(path: string, icon?: Component): NavigationItem {
  const page = pageFor(path)
  if (!page) throw new Error(`Target page catalog is missing navigation route: ${path}`)
  return { path, title: page.title, icon, permission: page.permissions.read, scope: 'target' }
}

export const navigation: NavigationGroup[] = [
  {
    key: 'home', title: '首页', icon: DataAnalysis,
    children: [target('/dashboard', Grid), target('/dashboard/configuration', Setting)],
  },
  {
    key: 'enterprise', title: '企业信息', icon: OfficeBuilding,
    children: [
      target('/enterprise/enterprises', OfficeBuilding), target('/enterprise/organizations', Menu),
      target('/enterprise/roles', Setting), target('/enterprise/positions', Grid), target('/enterprise/employees', User),
    ],
  },
  {
    key: 'archives', title: '基础档案', icon: Files,
    children: [
      target('/archives/communities', House), target('/archives/grids', Grid), target('/archives/rooms', House),
      target('/archives/customers', User), target('/archives/parking-spaces', CreditCard), target('/archives/meters', Stopwatch),
    ],
  },
  {
    key: 'fees', title: '物业收费', icon: Money,
    children: [
      target('/fees/definitions', Files), target('/fees/allocations', Operation), target('/finance/instruments', Files),
      target('/metering/readings', Stopwatch), target('/fees/receivables', Files), target('/fees/temporary-receivables', Files),
      target('/cashier', CreditCard), target('/fees/discounts', Coin), target('/system/third-party-settings', Setting),
      target('/finance/invoice-replacements', Files), target('/finance/prepayment-batch-offsets', Money),
    ],
  },
  {
    key: 'queries', title: '数据查询', icon: DataAnalysis,
    children: [
      target('/reports/transaction-summary', DataAnalysis), target('/reports/transaction-details', Files),
      target('/finance/receipt-batch-print', Files), target('/finance/payments', CreditCard), target('/finance/arrears', DataAnalysis),
      target('/finance/bill-notifications', Files), target('/finance/bills', Files), target('/reports/collection-rate', DataAnalysis),
      target('/reports/arrears-clearance-rate', DataAnalysis), target('/reports/comprehensive-query', DataAnalysis),
      target('/reports/collection-clearance-summary', DataAnalysis), target('/reports/charge-details', Files),
      target('/reports/discount-details', Files), target('/reports/prepayments', Money), target('/reports/ownership-transfers', Operation),
      target('/reports/reminders', Files), target('/reports/fee-status', DataAnalysis), target('/reports/invoice-statistics', DataAnalysis),
      target('/finance/deposits', Coin), target('/reports/daily-settlement-details', Files), target('/finance/adjustments', Operation),
      target('/finance/bank-trust', CreditCard),
    ],
  },
  {
    key: 'base-management', title: '基础管理', icon: Setting,
    children: [target('/system/dictionaries', Files)],
  },
  {
    key: 'system-tools', title: '系统工具', icon: Tools,
    children: [target('/system/migrations', Operation)],
  },
  {
    key: 'visitor', title: '访客核销', icon: User,
    children: [target('/security/visitor-records', User)],
  },
]

export const auxiliaryNavigation: NavigationItem[] = [
  { path: '/enterprise/accounts', title: '账号与项目权限', icon: Operation, permission: 'iam:read', scope: 'auxiliary' },
  { path: '/archives/buildings', title: '楼栋管理', icon: Menu, permission: 'property:read', scope: 'auxiliary' },
  { path: '/archives/units', title: '单元管理', icon: Grid, permission: 'property:read', scope: 'auxiliary' },
  { path: '/archives/customer-assets', title: '客户资产关系', icon: Operation, permission: 'property:read', scope: 'auxiliary' },
  { path: '/archives/vehicles', title: '车辆信息', icon: Van, permission: 'property:read', scope: 'auxiliary' },
  { path: '/fees/standards', title: '费用标准', icon: Coin, permission: 'fee:read', scope: 'auxiliary' },
  { path: '/metering/batches', title: '抄表批次', icon: Files, permission: 'meter:read', scope: 'auxiliary' },
  { path: '/metering/share-rules', title: '公摊规则', icon: Operation, permission: 'meter:read', scope: 'auxiliary' },
  { path: '/metering/share-preview', title: '公摊试算', icon: DataAnalysis, permission: 'meter:read', scope: 'auxiliary' },
  { path: '/metering/replacements', title: '换表记录', icon: Stopwatch, permission: 'meter:read', scope: 'auxiliary' },
  { path: '/metering/charges', title: '计量费用生成', icon: Money, permission: 'meter:read', scope: 'auxiliary' },
  { path: '/finance/prepayments', title: '预收款记录', icon: Money, permission: 'cashier:read', scope: 'auxiliary' },
  { path: '/finance/receipts', title: '收据记录', icon: Files, permission: 'cashier:read', scope: 'auxiliary' },
  { path: '/system/audits', title: '操作审计', icon: Operation, permission: 'system:audit', scope: 'auxiliary' },
  { path: '/system/adapters', title: '模拟通道', icon: Setting, permission: 'dashboard:read', scope: 'auxiliary' },
]

export const flatNavigation = navigation.flatMap((group) => group.children)
export const allNavigationItems = [...flatNavigation, ...auxiliaryNavigation]
