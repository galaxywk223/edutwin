import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import PracticeHistoryPanel from '@/features/practice/PracticeHistoryPanel.vue'
import type { AnswerHistoryPage } from '@/shared/types/api'

const { answerHistoryMock } = vi.hoisted(() => ({ answerHistoryMock: vi.fn() }))

vi.mock('@/api/client/edutwin', () => ({
  api: { answerHistory: answerHistoryMock },
}))

const populatedHistory: AnswerHistoryPage = {
  items: [
    {
      answerEventId: '10000000-0000-0000-0000-000000000001',
      questionId: '20000000-0000-0000-0000-000000000001',
      prompt: '哪一个选项符合数据类型定义？',
      selectedChoice: { choiceId: 'B', label: '短路求值' },
      correctChoice: { choiceId: 'A', label: '类型转换' },
      correct: false,
      skills: [{ skillId: '30000000-0000-0000-0000-000000000001', name: '数据类型与表达式' }],
      attemptNumber: 2,
      eventSequence: 25,
      responseTimeMs: 1_250,
      occurredAt: '2026-07-15T04:00:00Z',
      sourceType: 'ONLINE',
    },
  ],
  total: 25,
  page: 0,
  size: 20,
}

afterEach(() => {
  answerHistoryMock.mockReset()
})

describe('PracticeHistoryPanel', () => {
  it('loads and renders answer review details for the current course', async () => {
    answerHistoryMock.mockResolvedValue(populatedHistory)
    const wrapper = mount(PracticeHistoryPanel, {
      props: { courseId: 'course-1', refreshKey: 0 },
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()

    expect(answerHistoryMock).toHaveBeenCalledWith('course-1', 0, 20)
    expect(wrapper.text()).toContain('共 25 条')
    expect(wrapper.text()).toContain('哪一个选项符合数据类型定义？')
    expect(wrapper.text()).toContain('短路求值')
    expect(wrapper.text()).toContain('在线答题')

    await wrapper.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(answerHistoryMock).toHaveBeenCalledTimes(2)
  })

  it('renders an explicit empty state', async () => {
    answerHistoryMock.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const wrapper = mount(PracticeHistoryPanel, {
      props: { courseId: 'course-1', refreshKey: 0 },
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('暂无答题记录')
  })

  it('translates visible pagination to zero-based API requests', async () => {
    answerHistoryMock.mockResolvedValue(populatedHistory)
    const wrapper = mount(PracticeHistoryPanel, {
      props: { courseId: 'course-1', refreshKey: 0 },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    expect(pagination.exists()).toBe(true)
    pagination.vm.$emit('update:current-page', 2)
    await nextTick()
    pagination.vm.$emit('current-change', 2)
    await flushPromises()

    expect(answerHistoryMock).toHaveBeenLastCalledWith('course-1', 1, 20)
  })

  it('keeps history failures inside the history surface', async () => {
    answerHistoryMock.mockRejectedValue(new Error('历史服务暂不可用'))
    const wrapper = mount(PracticeHistoryPanel, {
      props: { courseId: 'course-1', refreshKey: 0 },
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('数据读取失败')
    expect(wrapper.text()).toContain('历史服务暂不可用')
  })
})
