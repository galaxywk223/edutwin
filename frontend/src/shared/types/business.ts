export type LifecycleStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'CANCELLED' | 'ARCHIVED'
export type LearnerAssessmentState = 'AVAILABLE' | 'SUBMITTED' | 'CLOSED_UNSUBMITTED' | 'CANCELLED'

export interface LearnerAssessmentFields {
  learnerState: LearnerAssessmentState | null
  attemptCount: number
  remainingAttemptCount: number
}

export interface LifecycleImpactPreview {
  entityType: string
  entityId: string
  currentStatus: string
  targetStatus: string
  affectedSectionCount: number
  affectedLessonCount: number
  affectedAssessmentCount: number
  affectedStudentCount: number
  affectedSubmissionCount: number
  confirmationRequired: boolean
}

export interface AssessmentAttempt {
  submissionId: string
  assessmentId: string
  studentId: string
  score: number
  maxScore: number
  percentage: number
  submittedAt: string
  attemptNumber: number
  attemptType: string
  validForGrade: boolean
  answers: Array<{
    questionId: string
    selectedChoiceId: string
    correctChoiceId: string
    correct: boolean
    pointsAwarded: number
  }>
}

export interface AssessmentAttemptList {
  items: AssessmentAttempt[]
  total: number
  currentSubmissionId: string | null
  currentScore: number | null
}

export interface AttemptRequest {
  requestId: string
  assessmentId: string
  studentId: string
  requestType: 'MAKEUP' | 'RETAKE' | 'APPEAL'
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reason: string
  requestedDueAt: string | null
  personalDueAt: string | null
  requestedBy: string
  decidedBy: string | null
  decisionReason: string | null
  requestedAt: string
  decidedAt: string | null
  consumed: boolean
}

export interface AttemptRequestList {
  items: AttemptRequest[]
  total: number
}

export interface PlanTaskProjection {
  planTaskId: string
  planId: string
  questionId: string
  questionTitle: string
  skillId: string
  skillName: string
  taskType: string
  reasonCode: string
  targetCount: number
  completedCount: number
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED'
  dueAt: string
  startedAt: string | null
  completedAt: string | null
  skippedAt: string | null
  skipReason: string | null
  practicePath: string
}

export interface PlanLifecycle {
  planId: string
  courseId: string
  studentId: string
  version: number
  status: 'ACTIVE' | 'COMPLETED' | 'SUPERSEDED' | 'EXPIRED'
  validUntil: string
  expiredAt: string | null
  tasks: PlanTaskProjection[]
}

export interface PracticePlanTask {
  planTaskId: string
  planId: string
  courseId: string
  questionId: string
  questionTitle: string
  prompt: string
  answerType: string
  optionsJson: string
  skillId: string
  skillName: string
  targetCount: number
  completedCount: number
}

export interface RiskFeedback {
  feedbackId: string
  studentId: string
  body: string
  createdAt: string
}

export interface RiskActionItem {
  actionItemId: string
  caseId: string
  studentId: string
  title: string
  description: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
  dueAt: string | null
  resultSummary: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  feedback: RiskFeedback[]
}

export interface RiskCaseSummary {
  caseId: string
  courseId: string
  studentId: string
  studentName: string
  courseTitle: string
  triggerType: string
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH'
  title: string
  summary: string
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
  priority: 'MEDIUM' | 'HIGH' | 'URGENT'
  assignedTo: string | null
  assigneeName: string | null
  version: number
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  closedAt: string | null
}

export interface RiskCasePage {
  items: RiskCaseSummary[]
  total: number
  page: number
  size: number
}

export interface RiskInternalNote {
  noteId: string
  authorId: string
  authorName: string
  body: string
  createdAt: string
}

export interface RiskCaseDetail {
  riskCase: RiskCaseSummary
  assignments: Array<{
    assignmentId: string
    fromAssignee: string | null
    toAssignee: string
    changedBy: string
    reason: string
    createdAt: string
  }>
  internalNotes: RiskInternalNote[]
  actionItems: RiskActionItem[]
}

export interface StudentActionProjection {
  actionItemId: string
  caseId: string
  courseId: string
  courseTitle: string
  title: string
  description: string
  status: RiskActionItem['status']
  dueAt: string | null
  resultSummary: string | null
  feedback: RiskFeedback[]
}

export interface ClassComparison {
  className: string
  studentCount: number
  activeCourseEnrollments: number
  averageScorePercentage: number | null
  averageMastery: number | null
  averageRiskProbability: number | null
  lowRiskCourseCount: number
  mediumRiskCourseCount: number
  highRiskCourseCount: number
  planCompletionRate: number | null
}

export interface ClassComparisonResult {
  classes: ClassComparison[]
  period: '7D' | '30D' | 'TERM'
  riskTrend: Array<{ className: string; weekStart: string; averageRiskProbability: number }>
}

export interface GovernanceDatasetVersion {
  versionId: string
  sourceKey: string
  sourceName: string
  lifecycleStatus: 'ENABLED' | 'DEPRECATED' | 'RETIRED'
  rowCount: number
  manifestSha256: string
  processedAt: string
  referenced: boolean
}

export interface GovernanceDatasetList { items: GovernanceDatasetVersion[]; total: number }
export interface DatasetImpact {
  versionId: string
  courseReferences: number
  activeModelReferences: number
  rollbackModelReferences: number
  canDeprecate: boolean
  canRetire: boolean
  blockers: string[]
}

export interface GovernanceModelDeployment {
  taskName: string
  activeVersionId: string
  rollbackVersionId: string | null
  deployedAt: string
  deployedBy: string
}
export interface GovernanceModelDeploymentList { items: GovernanceModelDeployment[]; total: number }
export interface ModelRollbackResult { deployment: GovernanceModelDeployment; affectedModules: string[] }
