import { expect, test } from '@playwright/test'
import { createRequire } from 'node:module'

const pageCatalog = createRequire(import.meta.url)('../src/config/page-catalog.json').pages as Array<{
  pageNo: number
  title: string
  path: string
}>

const username = process.env.PMS_E2E_USERNAME || 'admin'
const password = process.env.PMS_E2E_PASSWORD
const targetViewports = [
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1920x1080', width: 1920, height: 1080 },
] as const

for (const viewport of targetViewports) {
  test(`49-page structural visual baseline at ${viewport.name}`, async ({ page }) => {
    test.setTimeout(600_000)
    if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.route('**/api/v1/migrations/batches*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 1, size: 20, total: 0 }) })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/v1/receivable-jobs*', async (route) => {
      const requestUrl = new URL(route.request().url())
      if (route.request().method() === 'GET' && requestUrl.pathname.endsWith('/api/v1/receivable-jobs')) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
      } else {
        await route.continue()
      }
    })
    await page.goto('/login')
    await page.getByRole('textbox', { name: '账号' }).fill(username)
    await page.getByRole('textbox', { name: '密码' }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    for (const catalogPage of pageCatalog) {
      await page.goto(catalogPage.path)
      await expect(page.locator('.page-heading h1')).toHaveText(catalogPage.title)
      await page.locator('.el-loading-mask').first().waitFor({ state: 'detached', timeout: 8_000 }).catch(() => undefined)
      await page.evaluate(() => document.fonts.ready)
      await page.mouse.move(2, 2)
      await page.waitForTimeout(100)
      await expect(page).toHaveScreenshot(`page-${String(catalogPage.pageNo).padStart(2, '0')}-${viewport.name}.png`, {
        animations: 'disabled',
        caret: 'hide',
        maxDiffPixelRatio: 0.008,
      })
    }
  })
}
