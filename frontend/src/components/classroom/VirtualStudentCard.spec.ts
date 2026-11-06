import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import VirtualStudentCard from './VirtualStudentCard.vue'
import ClassroomControls from './ClassroomControls.vue'
import RecordingStatus from './RecordingStatus.vue'
import WsStatus from './WsStatus.vue'
import type { UploadProgress } from '../../utils/recorderUploader'
import type { StreamType } from '../../types/recording'

/**
 * Deliberately leak the fields the backend must never send to students. The
 * card may receive them on the object but must never declare or render them.
 */
const PRIVILEGED_FIELDS = {
  personality: '好胜、敏感',
  personality_text: '好胜、敏感，喜欢被表扬',
  parameters: { ability: 0.91, activity: 'high' },
  ability: 0.91,
  activity: 'high',
  question_rate: 0.8,
  attention: 0.77,
  hand_raise_energy: 0.63,
  discipline_probability: 0.2,
}

describe('VirtualStudentCard', () => {
  it('virtual student card never renders parameters or personality', () => {
    const wrapper = mount(VirtualStudentCard, {
      props: {
        student: {
          virtual_student_id: 'vs-1',
          name: '小明',
          avatar: null,
          state: 'hand_raised',
          ...PRIVILEGED_FIELDS,
        } as never,
      },
    })

    expect(wrapper.text()).toContain('小明')
    expect(wrapper.text()).toContain('举手')

    const html = wrapper.html()
    for (const leaked of ['好胜', '敏感', '喜欢被表扬', '0.91', '0.77', '0.63', '0.8', '0.2']) {
      expect(html).not.toContain(leaked)
    }
    for (const leakedKey of [
      'personality',
      'parameters',
      'ability',
      'activity',
      'question_rate',
      'attention',
      'hand_raise_energy',
      'discipline_probability',
    ]) {
      expect(html).not.toContain(leakedKey)
    }
  })

  it('renders the avatar when present and emits the selected student id', async () => {
    const wrapper = mount(VirtualStudentCard, {
      props: {
        student: {
          virtual_student_id: 'vs-2',
          name: '小红',
          avatar: 'https://cdn.example.com/avatar.png',
          state: 'focused',
        },
      },
    })

    expect(wrapper.get('img').attributes('src')).toBe('https://cdn.example.com/avatar.png')
    expect(wrapper.text()).toContain('专注')

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')).toEqual([['vs-2']])
  })
})

describe('ClassroomControls', () => {
  const ALL_CONTROLS = [
    'ask-class',
    'ask-student-question',
    'call-on',
    'mark-correct',
    'mark-partial',
    'mark-wrong',
    'remind',
    'end-dialogue',
    'end-qa',
    'end-session',
  ]

  function isDisabled(wrapper: ReturnType<typeof mount>, testId: string): boolean {
    return wrapper.get(`[data-testid="${testId}"]`).attributes('disabled') !== undefined
  }

  it('disables classroom controls before in_progress', async () => {
    const wrapper = mount(ClassroomControls, { props: { status: 'preparing' } })

    for (const testId of ALL_CONTROLS) {
      expect(isDisabled(wrapper, testId)).toBe(true)
    }

    await wrapper.setProps({
      status: 'in_progress',
      selectedStudentId: 'vs-1',
      answerId: 'answer-1',
      dialogueActive: false,
    })

    for (const testId of [
      'ask-class',
      'ask-student-question',
      'call-on',
      'mark-correct',
      'mark-partial',
      'mark-wrong',
      'remind',
      'end-qa',
      'end-session',
    ]) {
      expect(isDisabled(wrapper, testId)).toBe(false)
    }
    // Dialogue controls additionally require an active dialogue from the reducer.
    expect(isDisabled(wrapper, 'end-dialogue')).toBe(true)

    await wrapper.setProps({ dialogueActive: true })
    expect(isDisabled(wrapper, 'end-dialogue')).toBe(false)
  })

  it('disables student-scoped and mark controls without a target', async () => {
    const wrapper = mount(ClassroomControls, {
      props: { status: 'in_progress', selectedStudentId: null, answerId: null },
    })

    expect(isDisabled(wrapper, 'call-on')).toBe(true)
    expect(isDisabled(wrapper, 'remind')).toBe(true)
    expect(isDisabled(wrapper, 'mark-correct')).toBe(true)
  })

  it('emits the classroom commands', async () => {
    const wrapper = mount(ClassroomControls, {
      props: {
        status: 'in_progress',
        selectedStudentId: 'vs-1',
        answerId: 'answer-1',
        dialogueActive: true,
      },
    })

    const clicks: Array<[string, string]> = [
      ['ask-class', 'askClass'],
      ['ask-student-question', 'askStudentQuestion'],
      ['call-on', 'callOn'],
      ['mark-correct', 'mark'],
      ['mark-partial', 'mark'],
      ['mark-wrong', 'mark'],
      ['remind', 'remind'],
      ['end-dialogue', 'endDialogue'],
      ['end-qa', 'endQa'],
      ['end-session', 'endSession'],
    ]
    for (const [testId, emitted] of clicks) {
      await wrapper.get(`[data-testid="${testId}"]`).trigger('click')
      expect(wrapper.emitted(emitted), `${testId} should emit ${emitted}`).toBeTruthy()
    }

    expect(wrapper.emitted('mark')).toEqual([['correct'], ['partial'], ['wrong']])
  })

  it('disables every control after a recording failure', () => {
    const wrapper = mount(ClassroomControls, {
      props: {
        status: 'in_progress',
        selectedStudentId: 'vs-1',
        answerId: 'answer-1',
        dialogueActive: true,
        failed: true,
      },
    })

    for (const testId of ALL_CONTROLS) {
      expect(isDisabled(wrapper, testId)).toBe(true)
    }
  })
})

describe('RecordingStatus and WsStatus', () => {
  it('shows three recording statuses and reconnect countdown', () => {
    const streams: Partial<Record<StreamType, UploadProgress>> = {
      camera: { uploadedBytes: 50, totalBytes: 100, status: 'uploading' },
      screen: { uploadedBytes: 100, totalBytes: 100, status: 'completed' },
      audio: { uploadedBytes: 10, totalBytes: 100, status: 'failed' },
    }
    const recording = mount(RecordingStatus, { props: { streams } })
    const text = recording.text()

    expect(text).toContain('摄像头')
    expect(text).toContain('屏幕')
    expect(text).toContain('麦克风')
    expect(text).toContain('50%')
    expect(text).toContain('已完成')
    expect(text).toContain('失败')

    const reconnecting = mount(WsStatus, {
      props: { status: { status: 'reconnecting', secondsLeft: 17 } },
    })
    expect(reconnecting.text()).toContain('17')
    expect(reconnecting.text()).toContain('重连')

    const lost = mount(WsStatus, {
      props: { status: { status: 'failed', reason: 'recording_lost' } },
    })
    expect(lost.text()).toContain('录制')
  })
})