import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiError } from '../../types/api'
import TrainingRecordReportPage from './TrainingRecordReportPage.vue'
import { getEvaluationByTrainingRecord } from './api'
import type { ReportView } from './types'

vi.mock('./api', () => ({ getEvaluationByTrainingRecord: vi.fn() }))

vi.mock('./EvaluationReportPanel.vue', () => ({
  default: {
    name: 'EvaluationReportPanel',
    props: ['evaluationId'],
    template: '<div class="panel-stub">{{ evaluationId }}</div>',
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { recordId: 'record-1' } }),
}))

function mountPage() {
  return mount(TrainingRecordReportPage)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('TrainingRecordReportPage pending evaluation', () => {
  it('polls the training endpoint while the evaluation is not ready', async () => {
    const timeoutSpy = vi.spyOn(globalThis, 'setTimeout')
    vi.mocked(getEvaluationByTrainingRecord)
      .mockRejectedValueOnce(
        new ApiError({
          code: 'EVALUATION_NOT_READY',
          message: 'evaluation is not ready',
          status: 409,
        }),
      )
      .mockResolvedValue({ evaluation_id: 'evaluation-1' } as ReportView)

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 正在评测该训练记录')
    expect(wrapper.text()).not.toContain('加载失败')

    const poll = timeoutSpy.mock.calls
      .filter((call) => call[1] === 3_000)
      .map((call) => call[0])
      .pop() as (() => void) | undefined
    expect(poll).toBeDefined()
    poll!()
    await flushPromises()

    expect(wrapper.find('.panel-stub').text()).toContain('evaluation-1')
    timeoutSpy.mockRestore()
  })
})

describe('TrainingRecordReportPage access states', () => {
  it('renders a 403 without a retry button', async () => {
    vi.mocked(getEvaluationByTrainingRecord).mockRejectedValue(
      new ApiError({ code: 'FORBIDDEN', message: 'forbidden', status: 403 }),
    )

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('无权查看该训练报告')
    expect(wrapper.text()).not.toContain('重试')
  })

  it('renders a 404 as a missing training evaluation', async () => {
    vi.mocked(getEvaluationByTrainingRecord).mockRejectedValue(
      new ApiError({ code: 'EVALUATION_NOT_FOUND', message: 'missing', status: 404 }),
    )

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('该训练记录还没有评测')
  })
})
