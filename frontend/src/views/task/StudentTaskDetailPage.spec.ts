import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '../../types/api'
import { routes } from '../../app/router'
import StudentTaskDetailPage from './StudentTaskDetailPage.vue'
import { getStudentTask } from '../../api/tasks'
import type { StudentTaskDetailView } from '../../api/tasks'
import { createSession } from '../../api/sessions'
import type { SessionView } from '../../types/session'

vi.mock('../../api/tasks', () => ({
  listStudentTasks: vi.fn(),
  getStudentTask: vi.fn(),
}))

vi.mock('../../api/sessions', () => ({
  createSession: vi.fn(),
  getSession: vi.fn(),
  startSession: vi.fn(),
  endSession: vi.fn(),
  cancelSession: vi.fn(),
}))

const TASK_ID = 'task-1'

/**
 * Deliberately leak fields the backend must never send to students. The page
 * must not render them even if a future payload accidentally contains them.
 */
const PRIVILEGED_FIELDS = {
  rubric_weights: { content: 0.47, expression: 0.53 },
  reference_material: { text: '仅供教师参考的材料' },
  answer_points: ['答案要点：先审题再作答'],
}

function detailView(overrides: Partial<StudentTaskDetailView> = {}): StudentTaskDetailView {
  return {
    task_id: TASK_ID,
    course_id: 'course-1',
    course_name: '语文教学论',
    mode: 'free',
    topic: '自由试讲：春天',
    duration_seconds: 300,
    question_count: 2,
    virtual_students: [
      { virtual_student_id: 'vs-1', name: '小明', avatar: null, seat_no: 1 },
      { virtual_student_id: 'vs-2', name: '小红', avatar: null, seat_no: 2 },
    ],
    teacher_account_ids: ['teacher-1'],
    status: 'published',
    assignment_status: 'assigned',
    ...PRIVILEGED_FIELDS,
    ...overrides,
  } as StudentTaskDetailView
}

function sessionView(overrides: Partial<SessionView> = {}): SessionView {
  return {
    session_id: 'session-1',
    task_id: TASK_ID,
    course_id: 'course-1',
    session_type: 'task',
    mode: 'free',
    status: 'preparing',
    attempt_no: 1,
    started_at: null,
    ended_at: null,
    duration_ms: null,
    stream_offsets: {},
    last_seq: 0,
    ...overrides,
  }
}

async function mountPage(taskId = TASK_ID) {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(`/student/tasks/${taskId}`)
  await router.isReady()

  const wrapper = mount(StudentTaskDetailPage, {
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

function expectNoPrivilegedData(wrapper: ReturnType<typeof mount>): void {
  const text = wrapper.text()
  expect(text).not.toContain('0.47')
  expect(text).not.toContain('0.53')
  expect(text).not.toContain('仅供教师参考的材料')
  expect(text).not.toContain('答案要点')
  expect(text).not.toContain('rubric')
  expect(text).not.toContain('reference_material')
  expect(text).not.toContain('answer_points')
  expect(text).not.toContain('权重')
  expect(text).not.toContain('材料')
}

async function clickStart(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.get('[data-testid="start-task-session"]').trigger('click')
  await flushPromises()
}

describe('StudentTaskDetailPage', () => {
  beforeEach(() => {
    vi.mocked(getStudentTask).mockReset()
    vi.mocked(createSession).mockReset()
  })

  it('renders only public task fields and navigates to classroom', async () => {
    vi.mocked(getStudentTask).mockResolvedValue(detailView())
    vi.mocked(createSession).mockResolvedValue(sessionView())

    const { wrapper, router } = await mountPage()

    expect(getStudentTask).toHaveBeenCalledWith(TASK_ID)
    const text = wrapper.text()
    expect(text).toContain('自由试讲：春天')
    expect(text).toContain('语文教学论')
    expect(text).toContain('自由试讲')
    expect(text).toContain('5 分钟')
    expect(text).toContain('小明')
    expect(text).toContain('小红')
    expectNoPrivilegedData(wrapper)

    await clickStart(wrapper)

    expect(createSession).toHaveBeenCalledWith(TASK_ID)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe('/student/sessions/session-1/classroom')
    })
  })

  it('routes interview mode to the interview room and shows the pre-check without answer points', async () => {
    vi.mocked(getStudentTask).mockResolvedValue(
      detailView({ mode: 'interview', question_count: 2 }),
    )
    vi.mocked(createSession).mockResolvedValue(
      sessionView({ mode: 'interview', status: 'preparing' }),
    )

    const { wrapper, router } = await mountPage()

    const text = wrapper.text()
    expect(text).toContain('结构化面试')
    expect(text).toContain('2 路录制（摄像头 + 麦克风）')
    expect(text).toContain('共 2 题')
    expectNoPrivilegedData(wrapper)

    await clickStart(wrapper)

    expect(createSession).toHaveBeenCalledWith(TASK_ID)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe('/student/sessions/session-1/interview')
    })
  })

  it('shows a retryable error with the code when an active session already exists', async () => {
    vi.mocked(getStudentTask).mockResolvedValue(detailView())
    vi.mocked(createSession).mockRejectedValue(
      new ApiError({
        code: 'SESSION_ACTIVE_EXISTS',
        message: '已有进行中的会话',
        details: { task_id: TASK_ID },
        status: 409,
      }),
    )

    const { wrapper, router } = await mountPage()
    await clickStart(wrapper)

    expect(wrapper.text()).toContain('SESSION_ACTIVE_EXISTS')
    expect(wrapper.find('[data-testid="retry-start"]').exists()).toBe(true)
    expect(router.currentRoute.value.path).toBe(`/student/tasks/${TASK_ID}`)
  })
})