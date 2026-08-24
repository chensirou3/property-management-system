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
  Operation,
  Setting,
  Stopwatch,
  User,
  Van,
} from '@element-plus/icons-vue'

export interface NavigationItem {
  path: string
  title: string
  icon?: Component
}

export interface NavigationGroup {
  key: string
  title: string
  icon: Component
  children: NavigationItem[]
}

export const navigation: NavigationGroup[] = [
  {
    key: 'workbench',
    title: '工作台',
    icon: DataAnalysis,
    children: [{ path: '/dashboard', title: '项目看板', icon: Grid }],
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
      { path: '/fees/receivables', title: '应收生成', icon: Files },
      { path: '/cashier', title: '收银台', icon: CreditCard },
      { path: '/finance/bills', title: '账单管理', icon: Files },
      { path: '/finance/arrears', title: '欠费管理', icon: DataAnalysis },
      { path: '/finance/prepayments', title: '预收管理', icon: Money },
      { path: '/finance/deposits', title: '押金管理', icon: Coin },
      { path: '/finance/payments', title: '收款记录', icon: CreditCard },
      { path: '/finance/receipts', title: '收据管理', icon: Files },
    ],
  },
  {
    key: 'metering',
    title: '抄表管理',
    icon: Stopwatch,
    children: [
      { path: '/metering/batches', title: '抄表批次', icon: Files },
      { path: '/metering/readings', title: '抄表录入', icon: Stopwatch },
      { path: '/metering/share-rules', title: '公摊规则', icon: Operation },
      { path: '/metering/share-preview', title: '公摊试算', icon: DataAnalysis },
      { path: '/metering/replacements', title: '换表记录', icon: Stopwatch },
      { path: '/metering/charges', title: '计量费用生成', icon: Money },
    ],
  },
  {
    key: 'system',
    title: '系统管理',
    icon: Setting,
    children: [
      { path: '/system/users', title: '用户与角色', icon: User },
      { path: '/system/dictionaries', title: '数据字典', icon: Files },
      { path: '/system/audits', title: '操作审计', icon: Operation },
      { path: '/system/adapters', title: '模拟通道', icon: Setting },
    ],
  },
]

export const flatNavigation = navigation.flatMap((group) => group.children)

