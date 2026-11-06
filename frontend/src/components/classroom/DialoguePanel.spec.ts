import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DialoguePanel from './DialoguePanel.vue'
import { getTexts } from '../../api/replay'

vi.mock('../../api/replay', () => ({
  getTexts: vi.fn(),
}))

const students = {
  'vs-1': {
    virtual_student_id: 'vs-1',
    name: '小明',
    state: 'answering' as const,
    attention: 0.7,
    hand_raise_energy: 0,
    hand_raise_id: null,
  },
}

describe('DialoguePanel', () => {
  it('shows persisted student answers with the virtual student name', async () => {
    vi.mocked(getTexts).mockResolvedValue([
      {
        id: 't1',
        speaker_type: 'student',
        speaker_ref: 'vs-1',
        source: 'llm',
        kind: 'answer',
        text: '这是模拟的学生回答。',
        start_ms: null,
        end_ms: null,
        at_ms: 4_000,
        confidence: 0.8,
        status: 'final',
      },
    ])

    const wrapper = mount(DialoguePanel, {
      props: { sessionId: 'session-1', students },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="dialogue-panel"]').text()).toContain('小明')
    expect(wrapper.text()).toContain('这是模拟的学生回答。')
    expect(wrapper.text()).toContain('回答')
    wrapper.unmount()
  })

  it('renders the live ASR tail until the final row is persisted', async () => {
    vi.mocked(getTexts).mockResolvedValue([])
    const wrapper = mount(DialoguePanel, {
      props: { sessionId: 'session-1', students, liveText: '老师，为什' },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('识别中')
    expect(wrapper.text()).toContain('老师，为什')
    wrapper.unmount()
  })
})
