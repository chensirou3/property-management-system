import { describe, expect, it } from 'vitest'
import router from '../router'
import { allNavigationItems, auxiliaryNavigation, flatNavigation, navigation } from './navigation'
import { pageCatalog } from './pageCatalog'

describe('target navigation and route coverage', () => {
  it('renders the exact 49 target pages in eight target menu groups', () => {
    expect(navigation).toHaveLength(8)
    expect(flatNavigation).toHaveLength(49)
    expect(new Set(flatNavigation.map((item) => item.path))).toEqual(new Set(pageCatalog.map((page) => page.path)))
    for (const item of flatNavigation) {
      const page = pageCatalog.find((candidate) => candidate.path === item.path)
      expect(item.scope).toBe('target')
      expect(item.title).toBe(page?.title)
      expect(item.permission).toBe(page?.permissions.read)
    }
  })

  it('keeps supporting workflows routable without counting them as target pages', () => {
    expect(auxiliaryNavigation.length).toBeGreaterThan(0)
    expect(auxiliaryNavigation.every((item) => item.scope === 'auxiliary')).toBe(true)
    expect(new Set(allNavigationItems.map((item) => item.path)).size).toBe(allNavigationItems.length)
  })

  it('registers every target route with catalog metadata', () => {
    const routes = new Map(router.getRoutes().map((route) => [route.path, route]))
    for (const page of pageCatalog) {
      const route = routes.get(page.path)
      expect(route, `${page.title} route`).toBeDefined()
      expect(route?.meta).toMatchObject({
        title: page.title,
        permission: page.permissions.read,
        pageNo: page.pageNo,
        wave: page.wave,
        implementation: page.implementation,
      })
    }
  })
})
