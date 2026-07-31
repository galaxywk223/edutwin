import type { LearnerAssessmentState } from '@/shared/types/business'

export function learnerStateLabel(state: LearnerAssessmentState | null | undefined) {
  return ({
    AVAILABLE: '可作答',
    SUBMITTED: '已提交',
    CLOSED_UNSUBMITTED: '已截止未提交',
    CANCELLED: '已取消',
  } as const)[state ?? 'AVAILABLE']
}

export function assessmentStatusLabel(status: string) {
  return ({ DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已截止', CANCELLED: '已取消', ARCHIVED: '已归档' } as Record<string, string>)[status] ?? status
}

export function averageSubmittedScore(items: Array<{ percentage: number | null }>) {
  const values = items.flatMap((item) => item.percentage == null ? [] : [item.percentage])
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

export function planTaskStatusLabel(status: string) {
  return ({ PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', SKIPPED: '已跳过' } as Record<string, string>)[status] ?? status
}
