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

export interface NavigationItem {
  path: string
  title: string
  icon?: Component
  permission?: string
}

export interface NavigationGroup {
  key: string
  title: string
  icon: Component
  children: NavigationItem[]
}

export const navigation: NavigationGroup[] = [
  {
    key: 'home',
    title: '首页',
    icon: DataAnalysis,
    children: [{ path: '/dashboard', title: '项目看板', icon: Grid }],
  },
  {
    key: 'enterprise',
    title: '企业信息',
    icon: OfficeBuilding,
    children: [
      { path: '/enterprise/enterprises', title: '企业管理', icon: OfficeBuilding, permission: 'iam:read' },
      { path: '/enterprise/organizations', title: '组织管理', icon: Menu, permission: 'iam:read' },
      { path: '/enterprise/roles', title: '角色管理', icon: Setting, permission: 'iam:read' },
      { path: '/enterprise/positions', title: '岗位管理', icon: Grid, permission: 'iam:read' },
      { path: '/enterprise/employees', title: '人员管理', icon: User, permission: 'iam:read' },
      { path: '/enterprise/accounts', title: '账号与项目权限', icon: Operation, permission: 'iam:read' },
    ],
  },
  {
    key: 'archives',
    title: '基础档案',
    icon: Files,
    children: [
      { path: '/archives/communities', title: '小区信息', icon: House },
      { path: '/archives/buildings', title: '楼栋管理', icon: Menu },
      { path: '/archives/units', title: '单元管理', icon: Grid },
      { path: '/archives/rooms', title: '房产信息', icon: House },
      { path: '/archives/customers', title: '客户信息', icon: User },
      { path: '/archives/customer-assets', title: '客户资产关系', icon: Operation },
      { path: '/archives/parking-spaces', title: '车位信息', icon: CreditCard },
      { path: '/archives/vehicles', title: '车辆信息', icon: Van },
      { path: '/archives/meters', title: '仪表管理', icon: Stopwatch },
    ],
  },
  {
    key: 'fees',
    title: '物业收费',
    icon: Money,
    children: [
      { path: '/fees/definitions', title: '费用定义', icon: Files },
      { path: '/fees/standards', title: '费用标准', icon: Coin },
      { path: '/fees/allocations', title: '费用分配', icon: Operation },
      { path: '/fees/receivables', title: '生成应收', icon: Files },
      { path: '/cashier', title: '收银台', icon: CreditCard },
      { path: '/metering/batches', title: '抄表批次', icon: Files },
      { path: '/metering/readings', title: '抄表录入', icon: Stopwatch },
      { path: '/metering/share-rules', title: '公摊规则', icon: Operation },
      { path: '/metering/share-preview', title: '公摊试算', icon: DataAnalysis },
      { path: '/metering/replacements', title: '换表记录', icon: Stopwatch },
      { path: '/metering/charges', title: '计量费用生成', icon: Money },
    ],
  },
  {
    key: 'queries',
    title: '数据查询',
    icon: DataAnalysis,
    children: [
      { path: '/finance/bills', title: '应收管理', icon: Files },
      { path: '/finance/arrears', title: '欠费明细', icon: DataAnalysis },
      { path: '/finance/prepayments', title: '预收款记录', icon: Money },
      { path: '/finance/deposits', title: '押金记录', icon: Coin },
      { path: '/finance/payments', title: '交易记录', icon: CreditCard },
      { path: '/finance/receipts', title: '收据记录', icon: Files },
    ],
  },
  {
    key: 'base-management',
    title: '基础管理',
    icon: Setting,
    children: [{ path: '/system/dictionaries', title: '数据字典', icon: Files }],
  },
  {
    key: 'system-tools',
    title: '系统工具',
    icon: Tools,
    children: [
      { path: '/system/audits', title: '操作审计', icon: Operation },
      { path: '/system/adapters', title: '模拟通道', icon: Setting },
    ],
  },
]

export const flatNavigation = navigation.flatMap((group) => group.children)
