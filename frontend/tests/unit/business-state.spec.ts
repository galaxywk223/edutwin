import { describe, expect, it } from 'vitest'

import {
  assessmentStatusLabel,
  averageSubmittedScore,
  learnerStateLabel,
  planTaskStatusLabel,
} from '@/features/lms/businessState'

describe('business state display rules', () => {
  it('uses backend learner states instead of inferring assessment availability', () => {
    expect(learnerStateLabel('AVAILABLE')).toBe('可作答')
    expect(learnerStateLabel('CLOSED_UNSUBMITTED')).toBe('已截止未提交')
    expect(learnerStateLabel('CANCELLED')).toBe('已取消')
  })

  it('keeps missing grades out of the submitted average', () => {
    expect(averageSubmittedScore([{ percentage: null }, { percentage: 80 }, { percentage: 100 }])).toBe(90)
    expect(averageSubmittedScore([{ percentage: null }])).toBeNull()
  })

  it('covers lifecycle terminal states', () => {
    expect(assessmentStatusLabel('CANCELLED')).toBe('已取消')
    expect(assessmentStatusLabel('ARCHIVED')).toBe('已归档')
    expect(planTaskStatusLabel('SKIPPED')).toBe('已跳过')
  })
})
