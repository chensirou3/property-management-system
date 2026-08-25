import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import { flatNavigation } from '../config/navigation'
import { schemaFor } from '../config/pageSchemas'
import { useAuthStore } from '../stores/auth'

const DashboardView = () => import('../views/DashboardView.vue')
const GenericDataView = () => import('../views/GenericDataView.vue')
const LoginView = () => import('../views/LoginView.vue')
const ChangePasswordView = () => import('../views/ChangePasswordView.vue')
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const ReceivableWorkflowView = () => import('../views/ReceivableWorkflowView.vue')
const CashierView = () => import('../views/CashierView.vue')
const MeterWorkbenchView = () => import('../views/MeterWorkbenchView.vue')
const IamManagementView = () => import('../views/IamManagementView.vue')

const meterWorkflowPaths = ['/metering/batches', '/metering/readings', '/metering/share-preview', '/metering/replacements', '/metering/charges']
const iamPaths = flatNavigation.filter((item) => item.path.startsWith('/enterprise/'))
const specialPaths = new Set(['/dashboard', '/fees/receivables', '/cashier', ...meterWorkflowPaths, ...iamPaths.map((item) => item.path)])
const genericRoutes: RouteRecordRaw[] = flatNavigation
  .filter((item) => !specialPaths.has(item.path))
  .map((item) => ({
    path: item.path.slice(1),
    name: item.path.replaceAll('/', '-').slice(1),
    component: GenericDataView,
    meta: { title: item.title, permission: schemaFor(item.path)?.readPermission },
  }))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/change-password', name: 'change-password', component: ChangePasswordView, meta: { title: '修改临时密码' } },
    {
      path: '/',
      component: AppLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { title: '项目看板' } },
        { path: 'forbidden', name: 'forbidden', component: ForbiddenView, meta: { title: '无权访问' } },
        { path: 'fees/receivables', name: 'receivable-workflow', component: ReceivableWorkflowView, meta: { title: '应收生成', permission: 'fee:read' } },
        { path: 'cashier', name: 'cashier-workflow', component: CashierView, meta: { title: '收银台', permission: 'cashier:read' } },
        ...meterWorkflowPaths.map((path) => ({
          path: path.slice(1),
          name: `workflow-${path.replaceAll('/', '-').slice(1)}`,
          component: MeterWorkbenchView,
          meta: { title: flatNavigation.find((item) => item.path === path)?.title, permission: 'meter:read' },
        })),
        ...iamPaths.map((item) => ({
          path: item.path.slice(1),
          name: `iam-${item.path.split('/').at(-1)}`,
          component: IamManagementView,
          meta: { title: item.title, permission: item.permission || 'iam:read' },
        })),
        ...genericRoutes,
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async (to) => {
  document.title = `${String(to.meta.title || '物业管理平台')} - 物业管理平台`
  const hasToken = Boolean(sessionStorage.getItem('pms_access_token'))
  if (!to.meta.public && !hasToken) return '/login'
  if (hasToken) {
    const auth = useAuthStore()
    await auth.loadProfile()
    if (auth.user?.passwordChangeRequired && to.path !== '/change-password') return '/change-password'
    if (to.path === '/login') return auth.user?.passwordChangeRequired ? '/change-password' : '/dashboard'
    if (to.meta.permission && !auth.hasPermission(String(to.meta.permission))) return '/forbidden'
  }
})

export default router
