export type UserRole = 'ADMIN' | 'TEACHER' | 'STUDENT' | 'COUNSELOR'
export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH'
export type AnalysisJobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export type ProfileAvatar = 'avatar-1' | 'avatar-2' | 'avatar-3' | 'avatar-4' | 'avatar-5' | 'avatar-6' | 'avatar-7' | 'avatar-8'
export type ProfileTheme = 'teal' | 'blue' | 'green' | 'amber' | 'red' | 'gray' | 'violet' | 'rose'

export interface AuthenticatedUser {
  userId: string
  username: string
  displayName: string
  role: UserRole
  activeRole?: UserRole
  availableRoles?: UserRole[]
  mustChangePassword: boolean
  accessibleCourseIds: string[]
}

export interface AuthSession {
  accessToken: string
  tokenType: 'Bearer' | string
  expiresAt: string
  user: AuthenticatedUser
}

export interface OrganizationRef {
  organizationId: string
  code: string
  displayName: string
  unitType: string
}

export interface UserProfile {
  userId: string
  username: string
  displayName: string
  officialId: string | null
  roles: UserRole[]
  organizations: OrganizationRef[]
  email: string | null
  phone: string | null
  avatarKey: ProfileAvatar | null
  themeColor: ProfileTheme | null
}

export interface ProfileUpdate {
  email: string | null
  phone: string | null
  avatarKey: ProfileAvatar
  themeColor: ProfileTheme
}

export interface OrganizationUnit {
  organizationId: string
  code: string
  displayName: string
  unitType: 'UNIVERSITY' | 'COLLEGE' | 'DEPARTMENT' | 'MAJOR' | 'CLASS' | string
  parentId: string | null
  enabled: boolean
  children: OrganizationUnit[]
}

export interface OrganizationList { items: OrganizationUnit[]; total: number }
export interface OrganizationWrite {
  code: string
  displayName: string
  unitType: string
  parentId: string | null
  enabled: boolean
}
export interface OrganizationReferences { users: number; courses: number }

export interface AcademicTerm {
  termId: string
  code: string
  displayName: string
  startsOn: string
  endsOn: string
  enabled: boolean
}
export interface AcademicTermWrite {
  code: string
  displayName: string
  startsOn: string
  endsOn: string
  enabled: boolean
}
export interface AcademicTermList { items: AcademicTerm[]; total: number }

export interface UserNotification {
  notificationId: string
  type: string
  title: string
  body: string
  deepLink: string | null
  createdAt: string
  expiresAt: string | null
  read: boolean
}
export interface NotificationPage {
  items: UserNotification[]
  page: number
  size: number
  total: number
}
export interface UnreadCount { count: number }

export interface AssistantConversation {
  conversationId: string
  title: string
  activeRole: UserRole
  createdAt: string
  updatedAt: string
}
export interface AssistantConversationList { items: AssistantConversation[] }
export type AssistantMessageStatus = 'PROCESSING' | 'COMPLETED' | 'FAILED'
export interface AssistantSource {
  label: string
  queriedAt: string
  deepLink: string
}
export interface AssistantMessage {
  messageId: string
  role: 'USER' | 'ASSISTANT'
  status: AssistantMessageStatus
  content: string | null
  sources: AssistantSource[]
  deepLinks: string[]
  errorCode: string | null
  retryable: boolean
  createdAt: string
  completedAt: string | null
}
export interface AssistantMessageList { items: AssistantMessage[] }
export interface AssistantMessageReceipt {
  userMessageId: string
  assistantMessageId: string
  status: string
  streamUrl: string
}
export interface AssistantEvent {
  sequence: number
  messageId: string
  type: 'message.started' | 'tool.started' | 'tool.completed' | 'message.delta' | 'message.completed' | 'message.failed'
  occurredAt: string
  data: Record<string, unknown>
}

export interface CourseSummary {
  courseId: string
  code: string
  name: string
  termLabel: string | null
  credits: number
  college: string | null
  department: string | null
  instructorName: string | null
  role: UserRole
}

export interface CourseList {
  items: CourseSummary[]
  total: number
}

export type LmsContentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type AssessmentType = 'ASSIGNMENT' | 'QUIZ'

export interface LmsCourse {
  courseId: string
  code: string
  title: string
  termLabel: string | null
  description: string
  status: LmsContentStatus
  startsOn: string
  sectionCount: number
  assessmentCount: number
  enrolledStudentCount: number
  college: string | null
  department: string | null
  credits: number
  instructorName: string | null
  assignmentRole: 'OWNER' | 'CO_TEACHER' | 'LEARNER'
}

export interface LmsCourseList {
  items: LmsCourse[]
  total: number
}

export interface CourseMutationRequest {
  code: string
  title: string
  termLabel: string | null
  credits: number
  description: string
  startsOn: string
}

export interface LmsLesson {
  lessonId: string
  title: string
  summary: string
  body: string
  resourceUrl: string | null
  position: number
  status: LmsContentStatus
  completed: boolean
}

export interface LmsSection {
  sectionId: string
  title: string
  description: string
  position: number
  status: LmsContentStatus
  lessons: LmsLesson[]
}

export interface AssessmentOption {
  choiceId: string
  label: string
}

export interface AssessmentQuestion {
  questionId: string
  prompt: string
  options: AssessmentOption[]
  points: number
  position: number
}

export interface AssessmentSummary {
  assessmentId: string
  courseId: string
  title: string
  description: string
  assessmentType: AssessmentType
  status: LmsContentStatus
  dueAt: string | null
  questionCount: number
  submissionCount: number
  submitted: boolean
  score: number | null
  maxScore: number
}

export interface AssessmentList {
  items: AssessmentSummary[]
  total: number
}

export interface AssessmentDetail extends AssessmentSummary {
  questions: AssessmentQuestion[]
}

export interface CourseOutline {
  course: LmsCourse
  sections: LmsSection[]
  assessments: AssessmentSummary[]
  completedLessonCount: number
  totalLessonCount: number
}

export interface RosterStudent {
  studentId: string
  username: string
  displayName: string
  studentNumber: string
  college: string
  major: string
  cohortYear: number
  className: string
  enrolled: boolean
  enrollmentStatus: 'ACTIVE' | 'COMPLETED' | 'WITHDRAWN' | 'AVAILABLE'
}

export interface RosterList {
  items: RosterStudent[]
  total: number
  enrolledCount: number
}

export interface AssessmentQuestionRequest {
  prompt: string
  options: AssessmentOption[]
  correctChoiceId: string
  points: number
  skillIds: string[]
}

export interface KnowledgeSkillSummary {
  skillId: string
  skillCode: string
  name: string
  contentOrigin: 'CURATED_SYNTHETIC' | 'TEACHER_AUTHORED' | 'SOURCE_COMPATIBLE'
}

export interface KnowledgeSkillList { items: KnowledgeSkillSummary[]; total: number }

export interface AssessmentMutationRequest {
  title: string
  description: string
  assessmentType: AssessmentType
  dueAt: string | null
  questions: AssessmentQuestionRequest[]
}

export interface AssessmentResult {
  submissionId: string
  assessmentId: string
  studentId: string
  score: number
  maxScore: number
  percentage: number
  submittedAt: string
  answers: Array<{
    questionId: string
    selectedChoiceId: string
    correctChoiceId: string
    correct: boolean
    pointsAwarded: number
  }>
}

export interface GradeItem {
  assessmentId: string
  title: string
  studentId: string
  displayName: string
  submitted: boolean
  score: number | null
  maxScore: number
  percentage: number | null
  submittedAt: string | null
}

export interface GradeList {
  items: GradeItem[]
  total: number
}

export interface DataVersionRef {
  sourceId: string
  version: string
  manifestSha256: string
}

export interface ModelVersionRef {
  purpose: string
  family: string
  modelName: string
  modelVersion: string
  artifactSha256: string
  calibratorVersion: string | null
}

export interface TraceRef {
  correlationId: string
  answerEventId: string
  analysisJobId: string
  snapshotId: string
  dataVersions: DataVersionRef[]
  requestedModelVersions: ModelVersionRef[]
  effectiveModelVersions: ModelVersionRef[]
}

export interface RiskPrediction {
  probability: number
  calibrated: boolean
  riskBand: RiskBand
  mediumThreshold: number
  highThreshold: number
  baseValue: number
  predictedAt: string
}

export interface SkillMastery {
  skillId: string
  skillName: string
  probability: number
  estimator: 'BKT' | 'RULE_FALLBACK'
}

export interface TwinState {
  studentId: string
  courseId: string
  snapshotVersion: number
  capturedAt: string
  mastery: SkillMastery[]
  engagementScore: number
  stabilityScore: number
  nextCorrectProbability: number
  risk: RiskPrediction
  planCompletionRate: number
}

export interface TwinHistory {
  items: TwinState[]
  total: number
  nextBeforeVersion: number | null
}

export interface WeakSkill {
  skillId: string
  skillName: string
  averageMastery: number
  affectedStudentCount: number
}

export interface ActivityPoint {
  date: string
  answerCount: number
  activeStudentCount: number
  correctRate: number
}

export interface StudentRiskSummary {
  studentId: string
  displayName: string
  riskProbability: number
  riskBand: RiskBand
  lastActivityAt: string
  averageMastery: number
  engagementScore: number
  answerCount: number
  behaviorProfile: string
}

export interface BehaviorDistributionItem {
  behaviorType: string
  eventCount: number
  studentCount: number
  share: number
}

export interface ActivityHeatmapPoint { weekday: number; hour: number; eventCount: number }
export interface StudentScatterPoint {
  studentId: string
  displayName: string
  averageMastery: number
  riskProbability: number
  engagementScore: number
  behaviorProfile: string
}

export interface TeacherDashboard {
  courseId: string
  generatedAt: string
  studentCount: number
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
  averageRiskProbability: number
  period: AnalyticsPeriod
  activeStudentCount: number
  answerCount: number
  averageCorrectRate: number
  weakSkills: WeakSkill[]
  activityTrend: ActivityPoint[]
  students: StudentRiskSummary[]
  behaviorDistribution: BehaviorDistributionItem[]
  activityHeatmap: ActivityHeatmapPoint[]
  studentScatter: StudentScatterPoint[]
}

export type AnalyticsPeriod = '7D' | '30D' | 'TERM'

export interface StudentAnalytics {
  courseId: string
  studentId: string
  period: AnalyticsPeriod
  generatedAt: string
  answerCount: number
  correctRate: number
  activeDays: number
  totalDurationMinutes: number
  currentMastery: number
  currentRisk: number
  currentEngagement: number
  snapshotTrend: TwinState[]
  activityTrend: ActivityPoint[]
  behaviorDistribution: BehaviorDistributionItem[]
  activityHeatmap: ActivityHeatmapPoint[]
}

export interface PracticeChoice {
  choiceId: string
  label: string
}

export interface PracticeQuestion {
  questionId: string
  courseId: string
  prompt: string
  questionType: 'SINGLE_CHOICE'
  choices: PracticeChoice[]
  skillIds: string[]
}

export interface AnswerHistorySkill {
  skillId: string
  name: string
}

export interface AnswerHistoryItem {
  answerEventId: string
  questionId: string
  prompt: string
  selectedChoice: PracticeChoice
  correctChoice: PracticeChoice
  correct: boolean
  skills: AnswerHistorySkill[]
  attemptNumber: number
  eventSequence: number
  responseTimeMs: number
  occurredAt: string
  sourceType: 'ONLINE' | 'IMPORTED'
}

export interface AnswerHistoryPage {
  items: AnswerHistoryItem[]
  total: number
  page: number
  size: number
}

export interface AnalysisFailure {
  errorCode: string
  message: string
  retryable: boolean
  failedAt: string
}

export interface AnalysisJob {
  jobId: string
  studentId: string
  courseId: string
  status: AnalysisJobStatus
  stage: string
  lastEventSequence: number
  submittedAt: string
  startedAt: string | null
  completedAt: string | null
  failure: AnalysisFailure | null
  result: {
    twinUrl: string
    learningPlanUrl: string
    diagnosisUrl: string
  }
}

export interface SseJobEvent {
  sequence: number
  eventType: string
  occurredAt: string
  job: AnalysisJob
}

export interface LearningPlanTask {
  taskId: string
  sequence: number
  taskType: 'PRACTICE' | 'REVIEW'
  questionId: string
  skillId: string
  targetMastery: number
  reasonCode: string
  dueAt: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'
}

export interface LearningPlan {
  planId: string
  studentId: string
  courseId: string
  version: number
  generatedAt: string
  validUntil: string
  status: 'ACTIVE' | 'SUPERSEDED' | 'COMPLETED'
  completionRate: number
  tasks: LearningPlanTask[]
}

export interface DiagnosisEvidence {
  rank: number
  featureName: string
  rawValue: number
  direction: 'INCREASES_RISK' | 'DECREASES_RISK'
  contribution: number
  baseValue: number
  outputUnit: 'LOG_ODDS'
}

export interface Diagnosis {
  diagnosisId: string
  schemaVersion: '1.0'
  generatedAt: string
  summary: string
  riskBand: RiskBand
  strengths: string[]
  concerns: string[]
  recommendedActions: string[]
  evidence: DiagnosisEvidence[]
  learningPlan: LearningPlan
}

export interface SourceProvenance {
  sourceId: string
  datasetVersion: string
  sourceSha256: string
  manifestSha256: string
  licenseName: string
  licenseUrl: string
  sourceUrl: string
  rowCount: number
  processingRunId: string
  processingConfigSha256: string
  splitSeed: 42
}

export interface MetricValue {
  name: string
  split: 'VALIDATION' | 'TEST'
  value: number
}

export interface ModelTransparency {
  model: ModelVersionRef
  manifestSha256: string
  configSha256: string
  dependencyLockSha256: string
  selected: boolean
  metrics: MetricValue[]
}

export interface TransparencyResponse {
  provenance: {
    synthetic: boolean
    matchingVersion: string
    matchingSeed: 42
    generatedAt: string
    sources: SourceProvenance[]
  }
  models: ModelTransparency[]
  generatedAt: string
}

export interface AdminOverview {
  enabledUsers: number
  teachers: number
  students: number
  counselors: number
  enabledDatasets: number
  modelDeployments: number
  generatedAt: string
}

export interface AdminAiConfiguration {
  source: 'ENVIRONMENT' | 'DATABASE'
  revision: number
  enabled: boolean
  apiBaseUrl: string
  model: string
  apiKeyConfigured: boolean
  writeAvailable: boolean
  lastTestStatus: 'PASSED' | 'NOT_TESTED' | 'SKIPPED_DISABLED'
  lastTestedAt: string | null
  lastTestLatencyMs: number | null
  updatedAt: string
  updatedBy: string | null
}
export interface AdminAiConfigurationActivationResult {
  configuration: AdminAiConfiguration
  testStatus: 'PASSED' | 'SKIPPED_DISABLED'
  testLatencyMs: number | null
}

export interface AdminUser {
  userId: string
  username: string
  displayName: string
  role: UserRole
  roles: UserRole[]
  enabled: boolean
  mustChangePassword: boolean
  originType: 'DEMO_SYNTHETIC' | 'MANUAL' | 'BULK_IMPORT'
  createdAt: string
  updatedAt: string
}

export interface AdminUserList { items: AdminUser[]; total: number }
export interface TemporaryPassword { temporaryPassword: string; mustChangePassword: boolean }
export interface UserImportError {
  file: string
  sheet: string
  row: number
  field: string
  code: string
  message: string
}
export interface UserImportPreview {
  fileSha256: string
  format: 'XLSX' | 'CSV'
  userRows: number
  membershipRows: number
  createCount: number
  updateCount: number
  errors: UserImportError[]
  valid: boolean
}
export interface TemporaryCredential {
  username: string
  displayName: string
  role: UserRole
  temporaryPassword: string
}
export interface UserImportResult {
  fileSha256: string
  createdCount: number
  updatedCount: number
  membershipCount: number
  credentials: TemporaryCredential[]
}

export interface CounselorScope {
  scopeType: 'COHORT' | 'CLASS'
  college: string
  major: string
  cohortYear: number
  className: string | null
}
export interface CounselorScopeList { items: CounselorScope[]; total: number }
export interface StudentGroup extends CounselorScope { studentCount: number }
export interface StudentGroupList { items: StudentGroup[]; total: number }
export interface CounselorStudentSummary {
  studentId: string
  username: string
  displayName: string
  studentNumber: string
  college: string
  major: string
  cohortYear: number
  className: string
  courseCount: number
  totalCredits: number
  averageScorePercentage: number | null
  averageMastery: number | null
  highRiskCourseCount: number
  lastActivityAt: string | null
}
export interface CounselorStudentPage {
  items: CounselorStudentSummary[]
  total: number
  page: number
  size: number
}
export interface CounselorCourseStatistics {
  courseId: string
  code: string
  title: string
  termLabel: string | null
  credits: number
  instructors: string[]
  enrollmentStatus: 'ACTIVE' | 'COMPLETED'
  completedLessons: number
  totalLessons: number
  submittedAssessments: number
  publishedAssessments: number
  scorePercentage: number | null
  averageMastery: number | null
  riskBand: RiskBand | null
  riskProbability: number | null
  lastActivityAt: string | null
}
export interface CounselorStudentOverview {
  student: CounselorStudentSummary
  courses: CounselorCourseStatistics[]
}
export interface AdminDatasetVersion {
  versionId: string
  sourceKey: string
  sourceName: string
  lifecycleStatus: 'ENABLED' | 'DEPRECATED' | 'RETIRED'
  rowCount: number
  manifestSha256: string
  processedAt: string
  referenced: boolean
}
export interface AdminDatasetList { items: AdminDatasetVersion[]; total: number }
export interface AdminModelDeployment {
  taskName: string
  activeVersionId: string
  rollbackVersionId: string | null
  deployedAt: string
  deployedBy: string
}
export interface AdminModelDeploymentList { items: AdminModelDeployment[]; total: number }
export interface AdminAuditEvent {
  id: string
  actorUserId: string
  actorDisplayName: string
  activeRole: UserRole | null
  action: string
  targetType: string
  targetId: string
  outcome: 'SUCCEEDED' | 'FAILED'
  reason: string | null
  errorCode: string | null
  beforeJson: string | null
  afterJson: string | null
  metadataJson: string | null
  correlationId: string | null
  createdAt: string
}
export interface AdminAuditList {
  items: AdminAuditEvent[]
  total: number
  page: number
  size: number
}

export interface ProblemDetail {
  title: string
  status: number
  detail: string
  code: string
  traceId: string
  violations: Array<{ field: string; message: string }>
}
