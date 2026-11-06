import { describe, expect, it } from 'vitest'
import { router } from './router'

describe('router', () => {
  it('registers the error routes and the root redirect', () => {
    const routes = router.getRoutes()

    expect(routes.find((route) => route.path === '/')?.redirect).toBe('/student/tasks')
    expect(routes.some((route) => route.path === '/403')).toBe(true)
    expect(routes.some((route) => route.path === '/404')).toBe(true)
    expect(routes.some((route) => route.path === '/:pathMatch(.*)*')).toBe(true)
  })

  it('resolves unknown paths to the not-found route', () => {
    expect(router.resolve('/missing').name).toBe('not-found')
  })

  it('does not register task, classroom, interview or replay pages yet', () => {
    const paths = router.getRoutes().map((route) => route.path)

    expect(paths).not.toContain('/student/tasks')
    expect(paths).not.toContain('/student/sessions/:sessionId/classroom')
    expect(paths).not.toContain('/student/sessions/:sessionId/interview')
    expect(paths).not.toContain('/student/sessions/:sessionId/review')
  })
})