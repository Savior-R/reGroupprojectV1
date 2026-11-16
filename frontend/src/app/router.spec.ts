import { describe, expect, it } from 'vitest'
import { router } from './router'

describe('router', () => {
  it('registers the error routes and a role-aware root redirect', () => {
    const routes = router.getRoutes()
    const root = routes.find((route) => route.path === '/')

    // 根路径按角色落地（管理员 → 管理端、学生 → 任务中心、教师 → 账号安全），
    // 未登录时统一去登录页——不能再固定跳 /student/tasks，否则管理员会撞 403
    expect(typeof root?.redirect).toBe('function')
    expect((root?.redirect as () => string)()).toBe('/login')

    expect(routes.some((route) => route.path === '/403')).toBe(true)
    expect(routes.some((route) => route.path === '/404')).toBe(true)
    expect(routes.some((route) => route.path === '/:pathMatch(.*)*')).toBe(true)
  })

  it('resolves unknown paths to the not-found route', () => {
    expect(router.resolve('/missing').name).toBe('not-found')
  })

  it('registers the student task, classroom, interview and review routes', () => {
    const paths = router.getRoutes().map((route) => route.path)

    expect(paths).toContain('/student/tasks')
    expect(paths).toContain('/student/tasks/:taskId')
    expect(paths).toContain('/student/sessions/:sessionId/classroom')
    expect(paths).toContain('/student/sessions/:sessionId/interview')
    expect(paths).toContain('/student/sessions/:sessionId/review')
  })

  it('resolves a student task detail path to the task detail route', () => {
    const resolved = router.resolve('/student/tasks/task-1')

    expect(resolved.name).toBe('student-task-detail')
    expect(resolved.params.taskId).toBe('task-1')
  })

  it('resolves a classroom path to the classroom route', () => {
    const resolved = router.resolve('/student/sessions/session-1/classroom')

    expect(resolved.name).toBe('student-classroom')
    expect(resolved.params.sessionId).toBe('session-1')
  })

  it('resolves an interview path to the interview route', () => {
    const resolved = router.resolve('/student/sessions/session-1/interview')

    expect(resolved.name).toBe('student-interview')
    expect(resolved.params.sessionId).toBe('session-1')
  })

  it('resolves a review path to the review route', () => {
    const resolved = router.resolve('/student/sessions/session-1/review?t=5000')

    expect(resolved.name).toBe('student-review')
    expect(resolved.params.sessionId).toBe('session-1')
    expect(resolved.query.t).toBe('5000')
  })
})