import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import TeacherDashboardView from '@/features/teacher-dashboard/TeacherDashboardView.vue'

const { dashboardMock } = vi.hoisted(() => ({ dashboardMock: vi.fn() }))

vi.mock('@/api/client/edutwin', () => ({
  api: { dashboard: dashboardMock },
}))

vi.mock('vue-echarts', () => ({
  default: { name: 'VChart', template: '<div />' },
}))

vi.mock('@/app/composables/useCourseContext', () => ({
  useCourseContext: () => ({ courseId: { value: 'course-1' } }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

describe('TeacherDashboardView', () => {
  it('shows student names without exposing internal identifiers', async () => {
    const studentId = 'ab38360a-1111-2222-3333-444444444444'
    dashboardMock.mockResolvedValue({
      courseId: 'course-1',
      generatedAt: '2026-07-15T12:00:00Z',
      studentCount: 1,
      highRiskCount: 0,
      mediumRiskCount: 0,
      lowRiskCount: 1,
      averageRiskProbability: 0.12,
      period: '30D',
      activeStudentCount: 1,
      answerCount: 24,
      averageCorrectRate: 0.75,
      weakSkills: [],
      activityTrend: [],
      behaviorDistribution: [],
      activityHeatmap: [],
      studentScatter: [],
      students: [{
        studentId,
        displayName: '测试学生',
        riskProbability: 0.12,
        riskBand: 'LOW',
        lastActivityAt: '2026-07-15T11:00:00Z',
        averageMastery: 0.72,
        engagementScore: 0.8,
        answerCount: 24,
        behaviorProfile: 'STEADY',
      }],
    })

    const wrapper = mount(TeacherDashboardView, {
      global: {
        plugins: [ElementPlus],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('测试学生')
    expect(wrapper.text()).not.toContain(studentId)
    expect(wrapper.text()).not.toContain(studentId.slice(0, 8))
  })
})
