import { expect, test } from '@playwright/test'
import { createRequire } from 'node:module'

const pageCatalog = createRequire(import.meta.url)('../src/config/page-catalog.json').pages as Array<{
  pageNo: number
  title: string
  path: string
}>

const username = process.env.PMS_E2E_USERNAME || 'admin'
const password = process.env.PMS_E2E_PASSWORD

test('all 49 target pages are routable and render a traceable workspace', async ({ page }) => {
  test.setTimeout(180_000)
  if (!password) throw new Error('PMS_E2E_PASSWORD is required; do not commit a local password')

  await page.goto('/login')
  await page.getByRole('textbox', { name: '账号' }).fill(username)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  for (const catalogPage of pageCatalog) {
    await page.goto(catalogPage.path)
    await expect(page, `${catalogPage.pageNo}. ${catalogPage.title} URL`).toHaveURL(new RegExp(`${catalogPage.path.replaceAll('/', '\\/')}$`))
    await expect(page.locator('.page-heading h1'), `${catalogPage.title} heading`).toHaveText(catalogPage.title)
    await expect(page.locator('.main-region'), `${catalogPage.title} shell`).toBeVisible()
    await expect(page.locator('.page-content'), `${catalogPage.title} content`).not.toContainText('开发中')
    await expect(page.locator('.page-content'), `${catalogPage.title} content`).not.toContainText('功能建设中')
    await assertNoPageOverflow(page, catalogPage.title)
  }
})

async function assertNoPageOverflow(page: import('@playwright/test').Page, title: string) {
  const geometry = await page.evaluate(() => ({
    bodyClientWidth: document.body.clientWidth,
    bodyScrollWidth: document.body.scrollWidth,
    rootClientWidth: document.documentElement.clientWidth,
    rootScrollWidth: document.documentElement.scrollWidth,
    offenders: [...document.querySelectorAll<HTMLElement>('body *')]
      .filter((element) => element.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .slice(0, 8)
      .map((element) => ({ tag: element.tagName, className: element.className, right: Math.round(element.getBoundingClientRect().right), scrollWidth: element.scrollWidth })),
  }))
  const details = geometry.offenders.map((item) => `${item.tag}.${String(item.className)} right=${item.right} scroll=${item.scrollWidth}`).join('; ')
  expect(geometry.bodyScrollWidth, `${title} body overflow: ${details}`).toBe(geometry.bodyClientWidth)
  expect(geometry.rootScrollWidth, `${title} root overflow: ${details}`).toBe(geometry.rootClientWidth)
}
