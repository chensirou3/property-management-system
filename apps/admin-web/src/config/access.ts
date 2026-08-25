import { allNavigationItems, type NavigationItem } from './navigation'

export function firstAuthorizedNavigationItem(permissions: readonly string[] = []): NavigationItem | undefined {
  return allNavigationItems.find((item) => !item.permission || permissions.includes(item.permission))
}

export function firstAuthorizedPath(permissions: readonly string[] = []): string {
  return firstAuthorizedNavigationItem(permissions)?.path || '/forbidden'
}

export function canAccessNavigationPath(path: string, permissions: readonly string[] = []): boolean {
  const item = allNavigationItems.find((candidate) => candidate.path === path)
  return Boolean(item && (!item.permission || permissions.includes(item.permission)))
}
