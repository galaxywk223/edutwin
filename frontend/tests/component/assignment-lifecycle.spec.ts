import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AssignmentTaskList from '@/features/lms/assignments/AssignmentTaskList.vue'

const base = {
  assessmentId: 'assessment-1',
  courseId: 'course-1',
  title: '阶段测验',
  description: '课程阶段检查',
  assessmentType: 'QUIZ' as const,
  status: 'PUBLISHED' as const,
  dueAt: '2099-12-31T12:00:00Z',
  questionCount: 5,
  submissionCount: 0,
  submitted: false,
  score: null,
  maxScore: 10,
  attemptCount: 0,
  remainingAttemptCount: 0,
}

describe('AssignmentTaskList lifecycle projection', () => {
  it('renders a server-closed task as closed even when its due date is in the future', async () => {
    const wrapper = mount(AssignmentTaskList, {
      props: { filter: 'ALL', items: [{ ...base, learnerState: 'CLOSED_UNSUBMITTED' as const }] },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('已截止未提交')
    expect(wrapper.text()).toContain('申请补交')
    expect(wrapper.text()).not.toContain('开始答题')

    const requestButton = wrapper.findAll('button').find((button) => button.text().includes('申请补交'))
    await requestButton?.trigger('click')
    expect(wrapper.emitted('requestAttempt')).toHaveLength(1)
  })

  it('shows attempt count and history access for submitted work', () => {
    const wrapper = mount(AssignmentTaskList, {
      props: {
        filter: 'ALL',
        items: [{ ...base, learnerState: 'SUBMITTED' as const, submitted: true, score: 9, attemptCount: 2 }],
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('2 次作答')
    expect(wrapper.text()).toContain('作答记录')
  })
})
