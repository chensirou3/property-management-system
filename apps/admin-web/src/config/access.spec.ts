import { describe, expect, it } from 'vitest'
import { canAccessNavigationPath, firstAuthorizedNavigationItem, firstAuthorizedPath } from './access'

describe('permission-aware landing route', () => {
  it('keeps the dashboard as the landing page when it is authorized', () => {
    expect(firstAuthorizedPath(['dashboard:read', 'iam:read'])).toBe('/dashboard')
  })

  it('selects the first authorized workspace instead of sending a limited user to forbidden', () => {
    expect(firstAuthorizedNavigationItem(['iam:read'])).toMatchObject({
      path: '/enterprise/enterprises',
      permission: 'iam:read',
    })
    expect(canAccessNavigationPath('/dashboard', ['iam:read'])).toBe(false)
    expect(canAccessNavigationPath('/enterprise/accounts', ['iam:read'])).toBe(true)
  })

  it('uses forbidden only when the account has no usable page permission', () => {
    expect(firstAuthorizedPath([])).toBe('/forbidden')
  })
})
