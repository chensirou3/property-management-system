import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import { allNavigationItems } from '../config/navigation'
import { pageCatalog, pageFor } from '../config/pageCatalog'
import { schemaFor } from '../config/pageSchemas'
import { firstAuthorizedPath } from '../config/access'
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
const CapabilityWorkspaceView = () => import('../views/CapabilityWorkspaceView.vue')
const AssetWorkspaceView = () => import('../views/AssetWorkspaceView.vue')
const CustomerRelationshipView = () => import('../views/CustomerRelationshipView.vue')

const meterWorkflowPaths = ['/metering/batches', '/metering/readings', '/metering/share-preview', '/metering/replacements', '/metering/charges']
const iamPaths = allNavigationItems.filter((item) => item.path.startsWith('/enterprise/'))
const assetWorkspacePaths = ['/archives/rooms', '/archives/parking-spaces']
const customerWorkspacePaths = ['/archives/customers', '/archives/customer-assets']
const specialPaths = new Set(['/dashboard', '/fees/receivables', '/cashier', ...meterWorkflowPaths,
  ...iamPaths.map((item) => item.path), ...assetWorkspacePaths, ...customerWorkspacePaths])

function pageMeta(path: string, fallbackTitle?: string, fallbackPermission?: string) {
  const page = pageFor(path)
  return {
    title: page?.title || fallbackTitle,
    permission: page?.permissions.read || fallbackPermission,
    pageNo: page?.pageNo,
    wave: page?.wave,
    implementation: page?.implementation,
  }
}

const genericRoutes: RouteRecordRaw[] = allNavigationItems
  .filter((item) => !specialPaths.has(item.path) && schemaFor(item.path))
  .map((item) => ({
    path: item.path.slice(1),
    name: item.path.replaceAll('/', '-').slice(1),
    component: GenericDataView,
    meta: pageMeta(item.path, item.title, schemaFor(item.path)?.readPermission),
  }))
const capabilityRoutes: RouteRecordRaw[] = pageCatalog
  .filter((page) => !specialPaths.has(page.path) && !schemaFor(page.path))
  .map((page) => ({
    path: page.path.slice(1),
    name: `capability-${page.pageNo}-${page.path.replaceAll('/', '-').slice(1)}`,
    component: CapabilityWorkspaceView,
    meta: pageMeta(page.path, page.title, page.permissions.read),
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
        { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: pageMeta('/dashboard', '项目看板', 'dashboard:read') },
        { path: 'forbidden', name: 'forbidden', component: ForbiddenView, meta: { title: '无权访问' } },
        { path: 'fees/receivables', name: 'receivable-workflow', component: ReceivableWorkflowView, meta: pageMeta('/fees/receivables', '应收生成', 'fee:read') },
        { path: 'cashier', name: 'cashier-workflow', component: CashierView, meta: pageMeta('/cashier', '收银台', 'cashier:read') },
        ...meterWorkflowPaths.map((path) => ({
          path: path.slice(1),
          name: `workflow-${path.replaceAll('/', '-').slice(1)}`,
          component: MeterWorkbenchView,
          meta: pageMeta(path, allNavigationItems.find((item) => item.path === path)?.title, 'meter:read'),
        })),
        ...iamPaths.map((item) => ({
          path: item.path.slice(1),
          name: `iam-${item.path.split('/').at(-1)}`,
          component: IamManagementView,
          meta: pageMeta(item.path, item.title, item.permission || 'iam:read'),
        })),
        ...assetWorkspacePaths.map((path) => ({
          path: path.slice(1),
          name: `asset-workspace-${path.split('/').at(-1)}`,
          component: AssetWorkspaceView,
          meta: pageMeta(path, allNavigationItems.find((item) => item.path === path)?.title, 'property:read'),
        })),
        ...customerWorkspacePaths.map((path) => ({
          path: path.slice(1),
          name: `customer-workspace-${path.split('/').at(-1)}`,
          component: CustomerRelationshipView,
          meta: pageMeta(path, allNavigationItems.find((item) => item.path === path)?.title, 'property:read'),
        })),
        ...genericRoutes,
        ...capabilityRoutes,
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
    if (to.path === '/login') {
      return auth.user?.passwordChangeRequired
        ? '/change-password'
        : firstAuthorizedPath(auth.user?.permissions)
    }
    if (to.meta.permission && !auth.hasPermission(String(to.meta.permission))) return '/forbidden'
  }
})

export default router
