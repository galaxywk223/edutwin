import { apiUrl, currentToken, request } from './http'
import type {
  AnalysisJob,
  AcademicTerm,
  AcademicTermList,
  AcademicTermWrite,
  AnswerHistoryPage,
  AdminAuditEvent,
  AdminAuditList,
  AdminAiConfiguration,
  AdminAiConfigurationActivationResult,
  AdminOverview,
  AdminUser,
  AdminUserList,
  AssessmentDetail,
  AssessmentList,
  AssessmentMutationRequest,
  AssessmentResult,
  AuthSession,
  AuthenticatedUser,
  AssistantConversation,
  AssistantConversationList,
  AssistantMessageList,
  AssistantMessageReceipt,
  CourseList,
  CourseMutationRequest,
  CourseOutline,
  NotificationPage,
  OrganizationList,
  OrganizationReferences,
  OrganizationUnit,
  OrganizationWrite,
  ProfileUpdate,
  CounselorScope,
  CounselorScopeList,
  CounselorStudentOverview,
  CounselorStudentPage,
  Diagnosis,
  LearningPlan,
  GradeList,
  LmsCourse,
  LmsCourseList,
  LmsLesson,
  LmsSection,
  KnowledgeSkillList,
  PracticeQuestion,
  TeacherDashboard,
  StudentAnalytics,
  AnalyticsPeriod,
  TransparencyResponse,
  RosterList,
  TwinHistory,
  TwinState,
  TemporaryPassword,
  StudentGroupList,
  UserImportPreview,
  UserImportResult,
  UserProfile,
  UserRole,
  UnreadCount,
} from '@/shared/types/api'

const encoded = encodeURIComponent

export const api = {
  login(username: string, password: string) {
    return request<AuthSession>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },
  me: () => request<AuthenticatedUser>('/auth/me'),
  switchRole: (role: UserRole) =>
    request<AuthSession>('/auth/role', { method: 'POST', body: JSON.stringify({ role }) }),
  profile: () => request<UserProfile>('/me/profile'),
  updateProfile: (payload: ProfileUpdate) =>
    request<UserProfile>('/me/profile', { method: 'PUT', body: JSON.stringify(payload) }),
  notifications: (page = 0, size = 20) =>
    request<NotificationPage>(`/notifications?page=${page}&size=${size}`),
  unreadNotifications: () => request<UnreadCount>('/notifications/unread-count'),
  markNotificationRead: (notificationId: string) =>
    request<void>(`/notifications/${encoded(notificationId)}/read`, { method: 'PUT' }),
  markAllNotificationsRead: () => request<void>('/notifications/read-all', { method: 'PUT' }),
  courses: () => request<CourseList>('/courses'),
  dashboard: (courseId: string, period: AnalyticsPeriod = '30D') =>
    request<TeacherDashboard>(`/teacher/courses/${encoded(courseId)}/dashboard?period=${period}`),
  studentAnalytics: (courseId: string, period: AnalyticsPeriod = '30D') =>
    request<StudentAnalytics>(`/student/courses/${encoded(courseId)}/analytics?period=${period}`),
  twin: (courseId: string, studentId: string) =>
    request<TwinState>(`/courses/${encoded(courseId)}/students/${encoded(studentId)}/twin/current`),
  twinHistory: (courseId: string, studentId: string, limit = 30) =>
    request<TwinHistory>(
      `/courses/${encoded(courseId)}/students/${encoded(studentId)}/twin/history?limit=${limit}`,
    ),
  nextPractice: (courseId: string) =>
    request<PracticeQuestion>(`/courses/${encoded(courseId)}/practice/next`),
  answerHistory: (courseId: string, page = 0, size = 20) =>
    request<AnswerHistoryPage>(
      `/courses/${encoded(courseId)}/answers?page=${page}&size=${size}`,
    ),
  submitAnswer: (
    courseId: string,
    payload: { questionId: string; selectedChoiceId: string; occurredAt: string },
    idempotencyKey: string,
  ) =>
    request<AnalysisJob>(`/courses/${encoded(courseId)}/answers`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(payload),
    }),
  job: (jobId: string) => request<AnalysisJob>(`/analysis/jobs/${encoded(jobId)}`),
  currentPlan: (courseId: string, studentId: string) =>
    request<LearningPlan>(
      `/courses/${encoded(courseId)}/students/${encoded(studentId)}/learning-plans/current`,
    ),
  generatePlan: (courseId: string, studentId: string, reason: string) =>
    request<LearningPlan>(
      `/courses/${encoded(courseId)}/students/${encoded(studentId)}/learning-plans`,
      {
        method: 'POST',
        body: JSON.stringify({ reason }),
      },
    ),
  diagnosis: (courseId: string, studentId: string) =>
    request<Diagnosis>(`/courses/${encoded(courseId)}/students/${encoded(studentId)}/diagnoses`, {
      method: 'POST',
      body: JSON.stringify({}),
    }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/auth/password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword }),
    }),
  adminOverview: () => request<AdminOverview>('/admin/overview'),
  adminAiConfiguration: () => request<AdminAiConfiguration>('/admin/ai-configuration'),
  activateAdminAiConfiguration: (payload: {
    expectedRevision: number
    enabled: boolean
    apiBaseUrl: string
    model: string
    apiKey?: string
  }) => request<AdminAiConfigurationActivationResult>('/admin/ai-configuration', {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  restoreAdminAiEnvironment: (expectedRevision: number) =>
    request<AdminAiConfigurationActivationResult>('/admin/ai-configuration/restore-environment', {
      method: 'POST', body: JSON.stringify({ expectedRevision }),
    }),
  adminUsers: () => request<AdminUserList>('/admin/users'),
  adminOrganizations: () => request<OrganizationList>('/admin/organizations'),
  createAdminOrganization: (payload: OrganizationWrite) =>
    request<OrganizationUnit>('/admin/organizations', { method: 'POST', body: JSON.stringify(payload) }),
  updateAdminOrganization: (organizationId: string, payload: OrganizationWrite) =>
    request<OrganizationUnit>(`/admin/organizations/${encoded(organizationId)}`, {
      method: 'PUT', body: JSON.stringify(payload),
    }),
  adminOrganizationReferences: (organizationId: string) =>
    request<OrganizationReferences>(`/admin/organizations/${encoded(organizationId)}/references`),
  replaceUserOrganization: (userId: string, organizationId: string) =>
    request<void>(`/admin/users/${encoded(userId)}/organization`, {
      method: 'PUT', body: JSON.stringify({ organizationId }),
    }),
  adminAcademicTerms: () => request<AcademicTermList>('/admin/academic-terms'),
  createAdminAcademicTerm: (payload: AcademicTermWrite) =>
    request<AcademicTerm>('/admin/academic-terms', { method: 'POST', body: JSON.stringify(payload) }),
  updateAdminAcademicTerm: (termId: string, payload: AcademicTermWrite) =>
    request<AcademicTerm>(`/admin/academic-terms/${encoded(termId)}`, {
      method: 'PUT', body: JSON.stringify(payload),
    }),
  userImportTemplate: async (template: 'xlsx' | 'users.csv' | 'course_memberships.csv') => {
    const response = await fetch(apiUrl(`/admin/imports/user-provisioning/templates/${encodeURIComponent(template)}`), {
      headers: currentToken() ? { Authorization: `Bearer ${currentToken()}` } : {},
    })
    if (!response.ok) throw new Error('导入模板下载失败')
    return response.blob()
  },
  previewUserImport: (files: File[]) => {
    const body = new FormData()
    files.forEach((file) => body.append('files', file))
    return request<UserImportPreview>('/admin/imports/user-provisioning/preview', { method: 'POST', body })
  },
  commitUserImport: (files: File[], expectedSha256: string) => {
    const body = new FormData()
    files.forEach((file) => body.append('files', file))
    body.append('expectedSha256', expectedSha256)
    return request<UserImportResult>('/admin/imports/user-provisioning', { method: 'POST', body })
  },
  createAdminUser: (payload: { username: string; displayName: string; role?: string; roles?: UserRole[]; password: string }) =>
    request<AdminUser>('/admin/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateAdminUser: (userId: string, payload: { displayName?: string; role?: string; roles?: UserRole[]; enabled?: boolean }) =>
    request<AdminUser>(`/admin/users/${encoded(userId)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  resetAdminPassword: (userId: string) =>
    request<TemporaryPassword>(`/admin/users/${encoded(userId)}/password-reset`, { method: 'POST' }),
  studentGroups: () => request<StudentGroupList>('/admin/student-groups'),
  counselorScopes: (counselorId: string) =>
    request<CounselorScopeList>(`/admin/counselors/${encoded(counselorId)}/scopes`),
  replaceCounselorScopes: (counselorId: string, items: CounselorScope[]) =>
    request<CounselorScopeList>(`/admin/counselors/${encoded(counselorId)}/scopes`, {
      method: 'PUT', body: JSON.stringify({ items }),
    }),
  counselorStudents: (params: URLSearchParams) =>
    request<CounselorStudentPage>(`/counselor/students?${params.toString()}`),
  counselorStudentOverview: (studentId: string) =>
    request<CounselorStudentOverview>(`/counselor/students/${encoded(studentId)}/overview`),
  adminTransparency: () => request<TransparencyResponse>('/admin/transparency'),
  adminAuditEvents: (params = new URLSearchParams()) =>
    request<AdminAuditList>(`/admin/audit-events${params.size ? `?${params.toString()}` : ''}`),
  adminAuditEvent: (auditId: string) => request<AdminAuditEvent>(`/admin/audit-events/${encoded(auditId)}`),
  async exportAdminAuditEvents(params = new URLSearchParams()) {
    const headers = new Headers({ Accept: 'text/csv, application/problem+json' })
    const token = currentToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(apiUrl(`/admin/audit-events.csv${params.size ? `?${params.toString()}` : ''}`), { headers })
    if (!response.ok) throw new Error('审计 CSV 导出失败。')
    return response.blob()
  },
  managedCourses: () => request<LmsCourseList>('/lms/teacher/courses'),
  createManagedCourse: (payload: CourseMutationRequest) =>
    request<LmsCourse>('/lms/teacher/courses', { method: 'POST', body: JSON.stringify(payload) }),
  updateManagedCourse: (courseId: string, payload: CourseMutationRequest) =>
    request<LmsCourse>(`/lms/teacher/courses/${encoded(courseId)}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  changeCourseStatus: (courseId: string, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED') =>
    request<LmsCourse>(`/lms/teacher/courses/${encoded(courseId)}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    }),
  managedOutline: (courseId: string) =>
    request<CourseOutline>(`/lms/teacher/courses/${encoded(courseId)}/sections`),
  createSection: (
    courseId: string,
    payload: { title: string; description: string; status: 'DRAFT' | 'PUBLISHED' },
  ) =>
    request<LmsSection>(`/lms/teacher/courses/${encoded(courseId)}/sections`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  createLesson: (
    sectionId: string,
    payload: {
      title: string
      summary: string
      body: string
      resourceUrl: string | null
      status: 'DRAFT' | 'PUBLISHED'
    },
  ) =>
    request<LmsLesson>(`/lms/teacher/sections/${encoded(sectionId)}/lessons`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  roster: (courseId: string) =>
    request<RosterList>(`/lms/teacher/courses/${encoded(courseId)}/roster`),
  courseKnowledgeSkills: (courseId: string) =>
    request<KnowledgeSkillList>(`/lms/teacher/courses/${encoded(courseId)}/knowledge-skills`),
  updateEnrollment: (courseId: string, studentId: string, enrolled: boolean) =>
    request<void>(`/lms/teacher/courses/${encoded(courseId)}/roster/${encoded(studentId)}`, {
      method: 'PUT',
      body: JSON.stringify({ enrolled }),
    }),
  managedAssessments: (courseId: string) =>
    request<AssessmentList>(`/lms/teacher/courses/${encoded(courseId)}/assessments`),
  createAssessment: (courseId: string, payload: AssessmentMutationRequest) =>
    request<AssessmentDetail>(`/lms/teacher/courses/${encoded(courseId)}/assessments`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  changeAssessmentStatus: (assessmentId: string, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED') =>
    request<AssessmentDetail>(`/lms/teacher/assessments/${encoded(assessmentId)}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    }),
  assessmentGrades: (assessmentId: string) =>
    request<GradeList>(`/lms/teacher/assessments/${encoded(assessmentId)}/submissions`),
  publishedCourse: (courseId: string) => request<CourseOutline>(`/lms/courses/${encoded(courseId)}`),
  completeLesson: (courseId: string, lessonId: string) =>
    request<void>(`/lms/courses/${encoded(courseId)}/lessons/${encoded(lessonId)}/completion`, {
      method: 'PUT',
    }),
  publishedAssessments: (courseId: string) =>
    request<AssessmentList>(`/lms/courses/${encoded(courseId)}/assessments`),
  assessment: (assessmentId: string) =>
    request<AssessmentDetail>(`/lms/assessments/${encoded(assessmentId)}`),
  submitAssessment: (
    assessmentId: string,
    answers: Array<{ questionId: string; selectedChoiceId: string }>,
    idempotencyKey: string,
  ) =>
    request<AssessmentResult>(`/lms/assessments/${encoded(assessmentId)}/submissions`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ answers }),
    }),
  assessmentSubmission: (assessmentId: string) =>
    request<AssessmentResult>(`/lms/assessments/${encoded(assessmentId)}/submissions`),
  studentGrades: (courseId: string) =>
    request<GradeList>(`/lms/courses/${encoded(courseId)}/grades`),
  assistantConversations: () => request<AssistantConversationList>('/assistant/conversations'),
  createAssistantConversation: (title?: string) =>
    request<AssistantConversation>('/assistant/conversations', {
      method: 'POST', body: JSON.stringify({ title: title || null }),
    }),
  renameAssistantConversation: (conversationId: string, title: string) =>
    request<AssistantConversation>(`/assistant/conversations/${encoded(conversationId)}`, {
      method: 'PUT', body: JSON.stringify({ title }),
    }),
  deleteAssistantConversation: (conversationId: string) =>
    request<void>(`/assistant/conversations/${encoded(conversationId)}`, { method: 'DELETE' }),
  assistantMessages: (conversationId: string) =>
    request<AssistantMessageList>(`/assistant/conversations/${encoded(conversationId)}/messages`),
  sendAssistantMessage: (
    conversationId: string,
    content: string,
    clientMessageId: string,
    routeContext: Record<string, string>,
  ) => request<AssistantMessageReceipt>(`/assistant/conversations/${encoded(conversationId)}/messages`, {
    method: 'POST', body: JSON.stringify({ content, clientMessageId, routeContext }),
  }),
  retryAssistantMessage: (messageId: string) =>
    request<AssistantMessageReceipt>(`/assistant/messages/${encoded(messageId)}/retry`, { method: 'POST' }),
}
