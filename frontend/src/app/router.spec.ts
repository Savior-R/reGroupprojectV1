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

  it('registers the student task routes but not the session rooms yet', () => {
    const paths = router.getRoutes().map((route) => route.path)

    expect(paths).toContain('/student/tasks')
    expect(paths).toContain('/student/tasks/:taskId')
    expect(paths).not.toContain('/student/sessions/:sessionId/classroom')
    expect(paths).not.toContain('/student/sessions/:sessionId/interview')
    expect(paths).not.toContain('/student/sessions/:sessionId/review')
  })

  it('resolves a student task detail path to the task detail route', () => {
    const resolved = router.resolve('/student/tasks/task-1')

    expect(resolved.name).toBe('student-task-detail')
    expect(resolved.params.taskId).toBe('task-1')
  })
})