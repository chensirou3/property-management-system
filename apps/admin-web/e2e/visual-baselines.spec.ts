import { expect, test } from '@playwright/test'

const username = process.env.PMS_E2E_USERNAME || 'admin'
const password = process.env.PMS_E2E_PASSWORD
const dashboardVisualFixture = {
  communityId: '30000000-0000-0000-0000-000000000001',
  counts: { rooms: 359, customers: 403, parking_spaces: 250, meters: 31,
    fee_definitions: 23, fee_standards: 17, allocations: 743 },
  finance: { receivable: 2468.84, received: 241.26, outstanding: 2227.58,
    bill_count: 360, collection_rate: 9.77 },
  quality: { orphan_customer_relations: 0, orphan_allocations: 0, synthetic: true },
  adapters: { payment: 'simulator', invoice: 'simulator', iot: 'simulator', java110: 'disabled' },
}

const targetViewports = [
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1920x1080', width: 1920, height: 1080 },
] as const

for (const viewport of targetViewports) {
  test.describe(`visual baseline ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } })

    test(`login and authenticated shell remain stable at ${viewport.name}`, async ({ page }) => {
      if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')

      await page.route('**/api/v1/dashboard*', async (route) => {
        if (route.request().method() === 'GET') {
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dashboardVisualFixture) })
        } else {
          await route.continue()
        }
      })

      await page.goto('/login')
      await expect(page.getByRole('heading', { name: '项目、资产、收费一体化管理' })).toBeVisible()
      await expect(page.locator('.login-card')).toBeVisible()
      await assertNoPageOverflow(page)
      await expect(page).toHaveScreenshot(`login-${viewport.name}.png`, {
        animations: 'disabled',
        caret: 'hide',
        maxDiffPixelRatio: 0.005,
      })

      await page.getByRole('textbox', { name: '账号' }).fill(username)
      await page.getByRole('textbox', { name: '密码' }).fill(password)
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await expect(page).toHaveURL(/\/dashboard$/)
      await expect(page.locator('.stat-card').filter({ hasText: '房屋资产' })).toContainText('359')
      await expect(page.locator('.topbar')).toBeVisible()
      await expect(page.locator('.sidebar')).toBeVisible()
      await expect(page.locator('.workspace-tabs')).toContainText('看板')
      await assertShellGeometry(page, viewport.width, viewport.height)
      await expect(page).toHaveScreenshot(`dashboard-${viewport.name}.png`, {
        animations: 'disabled',
        caret: 'hide',
        maxDiffPixelRatio: 0.005,
      })
    })
  })
}

async function assertNoPageOverflow(page: import('@playwright/test').Page) {
  const geometry = await page.evaluate(() => ({
    bodyClientWidth: document.body.clientWidth,
    bodyScrollWidth: document.body.scrollWidth,
    rootClientWidth: document.documentElement.clientWidth,
    rootScrollWidth: document.documentElement.scrollWidth,
  }))
  expect(geometry.bodyScrollWidth).toBe(geometry.bodyClientWidth)
  expect(geometry.rootScrollWidth).toBe(geometry.rootClientWidth)
}

async function assertShellGeometry(page: import('@playwright/test').Page, width: number, height: number) {
  const geometry = await page.evaluate(() => {
    const rect = (selector: string) => {
      const element = document.querySelector(selector)
      if (!element) throw new Error(`Missing shell element: ${selector}`)
      const bounds = element.getBoundingClientRect()
      return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height,
        right: bounds.right, bottom: bounds.bottom }
    }
    return {
      bodyClientWidth: document.body.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      rootClientWidth: document.documentElement.clientWidth,
      rootScrollWidth: document.documentElement.scrollWidth,
      topbar: rect('.topbar'),
      sidebar: rect('.sidebar'),
      main: rect('.main-region'),
      tabs: rect('.workspace-tabs'),
    }
  })

  expect(geometry.bodyScrollWidth).toBe(geometry.bodyClientWidth)
  expect(geometry.rootScrollWidth).toBe(geometry.rootClientWidth)
  expect(geometry.topbar).toMatchObject({ x: 0, y: 0, width, height: 42 })
  expect(geometry.sidebar).toMatchObject({ x: 0, y: 42, width: 160 })
  expect(Math.round(geometry.sidebar.bottom)).toBe(height)
  expect(geometry.main).toMatchObject({ x: 160, width: width - 160 })
  expect(geometry.tabs).toMatchObject({ x: 160, y: 42, width: width - 160, height: 38 })
}
