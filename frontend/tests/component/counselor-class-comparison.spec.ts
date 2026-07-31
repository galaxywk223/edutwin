import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import CounselorClassComparisonView from '@/features/counselor/CounselorClassComparisonView.vue'

const { studentsMock, compareMock } = vi.hoisted(() => ({
  studentsMock: vi.fn(),
  compareMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({ api: { counselorStudents: studentsMock } }))
vi.mock('@/api/client/business', () => ({ businessApi: { compareClasses: compareMock } }))
vi.mock('vue-echarts', () => ({ default: { name: 'VChart', template: '<div class="chart" />' } }))

describe('CounselorClassComparisonView', () => {
  it('loads two classes with the default analytics period', async () => {
    studentsMock.mockResolvedValue({
      items: [{ className: '计算机 1 班' }, { className: '计算机 2 班' }],
      total: 2,
    })
    compareMock.mockResolvedValue({
      period: '30D',
      riskTrend: [],
      classes: [
        {
          className: '计算机 1 班', studentCount: 30, activeCourseEnrollments: 120,
          averageScorePercentage: 82, averageMastery: 0.74, averageRiskProbability: 0.28,
          lowRiskCourseCount: 80, mediumRiskCourseCount: 30, highRiskCourseCount: 10,
          planCompletionRate: 0.68,
        },
        {
          className: '计算机 2 班', studentCount: 31, activeCourseEnrollments: 124,
          averageScorePercentage: 79, averageMastery: 0.7, averageRiskProbability: 0.34,
          lowRiskCourseCount: 72, mediumRiskCourseCount: 38, highRiskCourseCount: 14,
          planCompletionRate: 0.62,
        },
      ],
    })

    const wrapper = mount(CounselorClassComparisonView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(compareMock).toHaveBeenCalledWith(['计算机 1 班', '计算机 2 班'], '30D')
    expect(wrapper.text()).toContain('学习结果对比')
    expect(wrapper.text()).toContain('风险变化趋势')
    expect(wrapper.findAll('.chart')).toHaveLength(3)
  })
})
