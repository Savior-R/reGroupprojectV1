import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EvaluationReportPanel from './EvaluationReportPanel.vue'
import { getEvaluation, getIssues, getReport } from './api'
import { ApiError } from '../../types/api'
import type { EvaluationView, IssueView, ReportView } from './types'

const { push } = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('./api', () => ({
  getEvaluation: vi.fn(),
  getReport: vi.fn(),
  getEvaluationBySubmission: vi.fn(),
  getEvaluationByTrainingRecord: vi.fn(),
  getIssues: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

const EVALUATION_ID = '11111111-1111-1111-1111-111111111111'
const SESSION_ID = '22222222-2222-2222-2222-222222222222'

function evaluation(overrides: Partial<EvaluationView> = {}): EvaluationView {
  return {
    evaluation_id: EVALUATION_ID,
    source_type: 'task_submission',
    source_id: '33333333-3333-3333-3333-333333333333',
    submission_id: '33333333-3333-3333-3333-333333333333',
    training_record_id: null,
    session_id: SESSION_ID,
    task_id: null,
    course_id: '44444444-4444-4444-4444-444444444444',
    student_account_id: '55555555-5555-5555-5555-555555555555',
    mode: 'free',
    status: 'completed',
    total_score: 80.8,
    weights: { content: 30, expression: 20, interaction: 20, board: 15, time: 15 },
    warnings: [],
    partial_reasons: [],
    error_code: null,
    error_message: null,
    model_version: 'mock-model',
    prompt_version: 'm4-eval-v1',
    rubric_version: 'm4-rubric-v1',
    attempt_count: 1,
    started_at: '2026-11-23T07:59:00Z',
    created_at: '2026-11-23T07:58:00Z',
    completed_at: '2026-11-23T08:00:00Z',
    updated_at: '2026-11-23T08:00:00Z',
    ...overrides,
  }
}

function report(overrides: Partial<ReportView> = {}): ReportView {
  return {
    evaluation_id: EVALUATION_ID,
    submission_id: '33333333-3333-3333-3333-333333333333',
    training_record_id: null,
    session_id: SESSION_ID,
    mode: 'free',
    status: 'completed',
    total_score: 80.8,
    weights: { content: 30, expression: 20, interaction: 20, board: 15, time: 15 },
    radar: {
      axes: ['content', 'expression', 'interaction', 'board', 'time'],
      values: [82, 72, 80, 81, 90],
    },
    dimensions: [
      {
        dimension: 'content',
        score: 82,
        sub_scores: { accuracy: 82 },
        sub_confidences: { accuracy: 0.8 },
        confidence: 0.8,
      },
      {
        dimension: 'expression',
        score: 72,
        sub_scores: { speech_rate: 72 },
        sub_confidences: { speech_rate: 0.8 },
        confidence: 0.8,
      },
    ],
    missing_dimensions: [],
    suggestions: [
      {
        issue_id: 's1',
        dimension: 'content',
        start_ms: 12_000,
        end_ms: 18_000,
        severity: 'medium',
        evidence: '讲解缺少关键结论。',
        suggestion: '补充关键结论。',
        is_suggestion: true,
        suggestion_rank: 1,
      },
      {
        issue_id: 's2',
        dimension: 'expression',
        start_ms: 30_000,
        end_ms: null,
        severity: 'high',
        evidence: '语速过快。',
        suggestion: '放慢语速。',
        is_suggestion: true,
        suggestion_rank: 2,
      },
    ],
    warnings: ['EMPTY_EVENTS'],
    partial_reasons: [],
    meta: {
      model_version: 'mock-model',
      prompt_version: 'm4-eval-v1',
      rubric_version: 'm4-rubric-v1',
      config_version: 4,
      attempt_count: 1,
      created_at: '2026-11-23T07:58:00Z',
      started_at: '2026-11-23T07:59:00Z',
      completed_at: '2026-11-23T08:00:00Z',
    },
    ...overrides,
  }
}

function mountPanel() {
  return mount(EvaluationReportPanel, { props: { evaluationId: EVALUATION_ID } })
}

function issue(overrides: Partial<IssueView> = {}): IssueView {
  return {
    issue_id: 'p1',
    dimension: 'content',
    start_ms: 12_000,
    end_ms: 18_000,
    severity: 'medium',
    evidence: '讲解缺少关键结论。',
    suggestion: '补充关键结论。',
    is_suggestion: false,
    suggestion_rank: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getEvaluation).mockReset().mockResolvedValue(evaluation())
  vi.mocked(getReport).mockReset().mockResolvedValue(report())
  vi.mocked(getIssues).mockReset().mockResolvedValue([])
})

describe('EvaluationReportPanel completed state', () => {
  it('renders total, dimensions, warnings and suggestions', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.report-hero__score').text()).toContain('80.8')
    expect(wrapper.find('.report-hero__score').text()).toContain('/ 100')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('自由试讲')
    expect(wrapper.text()).toContain('内容')
    expect(wrapper.text()).toContain('权重 30%')
    expect(wrapper.text()).toContain('讲解缺少关键结论。')
    expect(wrapper.text()).toContain('没有课堂事件')
    expect(wrapper.text()).toContain('m4-eval-v1')
    expect(wrapper.findAll('.suggestion')).toHaveLength(2)
  })

  it('renders the confidence and marks a low-confidence dimension', async () => {
    vi.mocked(getReport).mockResolvedValue(
      report({
        dimensions: [
          {
            dimension: 'content',
            score: 82,
            sub_scores: { accuracy: 82 },
            sub_confidences: { accuracy: 0.6 },
            confidence: 0.6,
          },
        ],
      }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('置信度 60%')
    expect(wrapper.text()).toContain('低置信度')
  })

  it('renders the full problem timeline without duplicating suggestions', async () => {
    vi.mocked(getIssues).mockResolvedValue([
      issue({
        issue_id: 'p2',
        dimension: 'expression',
        start_ms: 30_000,
        severity: 'high',
        evidence: '语速过快。',
      }),
      issue({ issue_id: 'p1' }),
      issue({ issue_id: 's1', is_suggestion: true, suggestion_rank: 1 }),
    ])

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('问题时间线')
    expect(wrapper.text()).toContain('共 2 条')
    expect(wrapper.text()).toContain('语速过快。')
    expect(wrapper.findAll('.el-collapse-item')).toHaveLength(2)
  })

  it('jumps to the replay timestamp of a suggestion', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const button = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().includes('回看该片段'))
    expect(button).toBeDefined()
    await button!.trigger('click')

    expect(push).toHaveBeenCalledWith({
      path: `/student/sessions/${SESSION_ID}/review`,
      query: { t: '12000' },
    })
  })
})

describe('EvaluationReportPanel terminal states', () => {
  it('shows partial results without a total and lists missing dimensions', async () => {
    vi.mocked(getEvaluation).mockResolvedValue(
      evaluation({ status: 'partial', total_score: null }),
    )
    vi.mocked(getReport).mockResolvedValue(
      report({
        status: 'partial',
        total_score: null,
        missing_dimensions: ['board'],
        partial_reasons: ['FRAMES_MISSING'],
        radar: {
          axes: ['content', 'expression', 'interaction', 'board', 'time'],
          values: [82, 72, 80, null, 90],
        },
      }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.report-hero__score').text()).toContain('—')
    expect(wrapper.text()).toContain('本次报告为部分完成')
    expect(wrapper.text()).toContain('缺失维度：板书')
    expect(wrapper.text()).toContain('屏幕帧 / OCR 缺失')
    expect(wrapper.text()).toContain('未提供')
    expect(wrapper.text()).toContain('未完成')
    expect(wrapper.text()).toContain('存在缺失或失败的必需维度')
  })

  it('shows failed status with the stored error code and does not fetch a report', async () => {
    vi.mocked(getEvaluation).mockResolvedValue(
      evaluation({
        status: 'failed',
        total_score: null,
        error_code: 'AI_TIMEOUT',
        error_message: 'model timed out',
      }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('评测失败')
    expect(wrapper.text()).toContain('AI_TIMEOUT')
    expect(wrapper.text()).toContain('model timed out')
    expect(getReport).not.toHaveBeenCalled()
  })

  it('renders the waiting state and polls until a terminal status arrives', async () => {
    const timeoutSpy = vi.spyOn(globalThis, 'setTimeout')
    vi.mocked(getEvaluation)
      .mockResolvedValueOnce(evaluation({ status: 'pending', total_score: null }))
      .mockResolvedValue(evaluation())

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('评测任务已排队')
    expect(getReport).not.toHaveBeenCalled()

    const poll = timeoutSpy.mock.calls
      .filter((call) => call[1] === 5_000)
      .map((call) => call[0])
      .pop() as (() => void) | undefined
    expect(poll).toBeDefined()
    poll!()
    await flushPromises()

    expect(getReport).toHaveBeenCalledWith(EVALUATION_ID)
    expect(wrapper.find('.report-hero__score').text()).toContain('80.8')
    timeoutSpy.mockRestore()
  })
})

describe('EvaluationReportPanel access states', () => {
  it('renders a 403 as no permission without a retry button', async () => {
    vi.mocked(getEvaluation).mockRejectedValue(
      new ApiError({ code: 'FORBIDDEN', message: 'forbidden', status: 403 }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('无权查看该评测报告')
    expect(wrapper.text()).not.toContain('重试')
  })

  it('renders a 404 as missing report', async () => {
    vi.mocked(getEvaluation).mockRejectedValue(
      new ApiError({ code: 'EVALUATION_NOT_FOUND', message: 'missing', status: 404 }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('评测报告不存在')
  })

  it('retries a transient error', async () => {
    vi.mocked(getEvaluation)
      .mockRejectedValueOnce(
        new ApiError({ code: 'INTERNAL_ERROR', message: 'boom', status: 500 }),
      )
      .mockResolvedValue(evaluation())

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('boom')
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试'))
    await retry!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.report-hero__score').text()).toContain('80.8')
  })
})
