import { request } from './http'
import type {
  AssessmentAttemptList,
  AttemptRequest,
  AttemptRequestList,
  ClassComparisonResult,
  DatasetImpact,
  GovernanceDatasetList,
  GovernanceDatasetVersion,
  GovernanceModelDeploymentList,
  LifecycleImpactPreview,
  PlanLifecycle,
  PracticePlanTask,
  ModelRollbackResult,
  RiskCaseDetail,
  RiskCasePage,
  StudentActionProjection,
} from '@/shared/types/business'

const encoded = encodeURIComponent

export const businessApi = {
  previewCourseStatus: (courseId: string, currentStatus: string, targetStatus: string, reason: string) =>
    request<LifecycleImpactPreview>(`/lms/teacher/courses/${encoded(courseId)}/status/preview`, {
      method: 'POST', body: JSON.stringify({ expectedCurrentStatus: currentStatus, targetStatus, reason }),
    }),
  changeCourseStatus: (courseId: string, currentStatus: string, targetStatus: string, reason: string) =>
    request(`/lms/teacher/courses/${encoded(courseId)}/status`, {
      method: 'PUT', body: JSON.stringify({ expectedCurrentStatus: currentStatus, targetStatus, reason }),
    }),
  changeSectionStatus: (sectionId: string, currentStatus: string, targetStatus: string, reason: string) =>
    request(`/lms/teacher/sections/${encoded(sectionId)}/status`, {
      method: 'PUT', body: JSON.stringify({ expectedCurrentStatus: currentStatus, targetStatus, reason }),
    }),
  changeLessonStatus: (lessonId: string, currentStatus: string, targetStatus: string, reason: string) =>
    request(`/lms/teacher/lessons/${encoded(lessonId)}/status`, {
      method: 'PUT', body: JSON.stringify({ expectedCurrentStatus: currentStatus, targetStatus, reason }),
    }),
  updateEnrollment: (courseId: string, studentId: string, enrolled: boolean, reason: string) =>
    request<void>(`/lms/teacher/courses/${encoded(courseId)}/roster/${encoded(studentId)}`, {
      method: 'PUT', body: JSON.stringify({ enrolled, reason }),
    }),
  changeAssessmentStatus: (assessmentId: string, currentStatus: string, targetStatus: string, reason: string) =>
    request(`/lms/teacher/assessments/${encoded(assessmentId)}/status`, {
      method: 'PUT', body: JSON.stringify({ expectedCurrentStatus: currentStatus, targetStatus, reason }),
    }),
  extendAssessment: (assessmentId: string, dueAt: string, reason: string) =>
    request(`/lms/teacher/assessments/${encoded(assessmentId)}/extend`, {
      method: 'POST', body: JSON.stringify({ dueAt, reason }),
    }),
  cancelAssessment: (assessmentId: string, reason: string) =>
    request(`/lms/teacher/assessments/${encoded(assessmentId)}/cancel`, {
      method: 'POST', body: JSON.stringify({ reason }),
    }),
  assessmentAttempts: (assessmentId: string) =>
    request<AssessmentAttemptList>(`/lms/assessments/${encoded(assessmentId)}/attempts`),
  teacherAttemptRequests: (assessmentId: string) =>
    request<AttemptRequestList>(`/lms/teacher/assessments/${encoded(assessmentId)}/attempt-requests`),
  studentAttemptRequests: (assessmentId: string) =>
    request<AttemptRequestList>(`/lms/assessments/${encoded(assessmentId)}/attempt-requests`),
  requestAttempt: (assessmentId: string, requestType: 'MAKEUP' | 'RETAKE' | 'APPEAL', reason: string, requestedDueAt: string | null) =>
    request<AttemptRequest>(`/lms/assessments/${encoded(assessmentId)}/attempt-requests`, {
      method: 'POST', body: JSON.stringify({ requestType, reason, requestedDueAt, studentId: null }),
    }),
  decideAttemptRequest: (requestId: string, decision: 'APPROVED' | 'REJECTED', reason: string, personalDueAt: string | null) =>
    request<AttemptRequest>(`/lms/teacher/attempt-requests/${encoded(requestId)}/decision`, {
      method: 'PUT', body: JSON.stringify({ decision, reason, personalDueAt }),
    }),
  currentPlanLifecycle: (courseId: string, studentId: string) =>
    request<PlanLifecycle>(`/courses/${encoded(courseId)}/students/${encoded(studentId)}/learning-plans/current/lifecycle`),
  startPlanTask: (courseId: string, studentId: string, taskId: string, planId: string) =>
    request<PlanLifecycle>(`/courses/${encoded(courseId)}/students/${encoded(studentId)}/learning-plans/current/tasks/${encoded(taskId)}/start`, {
      method: 'POST', body: JSON.stringify({ expectedPlanId: planId }),
    }),
  skipPlanTask: (courseId: string, studentId: string, taskId: string, planId: string, reason: string) =>
    request<PlanLifecycle>(`/courses/${encoded(courseId)}/students/${encoded(studentId)}/learning-plans/current/tasks/${encoded(taskId)}/skip`, {
      method: 'POST', body: JSON.stringify({ expectedPlanId: planId, reason }),
    }),
  practicePlanTask: (courseId: string, taskId: string) =>
    request<PracticePlanTask>(`/courses/${encoded(courseId)}/practice/tasks/${encoded(taskId)}`),
  riskCases: (params: URLSearchParams) => request<RiskCasePage>(`/risk/cases?${params.toString()}`),
  riskCase: (caseId: string) => request<RiskCaseDetail>(`/risk/cases/${encoded(caseId)}`),
  transitionRiskCase: (caseId: string, targetStatus: string, reason: string, expectedVersion: number) =>
    request<RiskCaseDetail>(`/risk/cases/${encoded(caseId)}/status`, {
      method: 'PUT', body: JSON.stringify({ targetStatus, reason, expectedVersion }),
    }),
  addRiskNote: (caseId: string, body: string) =>
    request<RiskCaseDetail>(`/risk/cases/${encoded(caseId)}/notes`, { method: 'POST', body: JSON.stringify({ body }) }),
  addRiskAction: (caseId: string, title: string, description: string, dueAt: string | null) =>
    request<RiskCaseDetail>(`/risk/cases/${encoded(caseId)}/actions`, {
      method: 'POST', body: JSON.stringify({ title, description, dueAt }),
    }),
  updateRiskAction: (caseId: string, actionId: string, targetStatus: string, resultSummary: string) =>
    request<RiskCaseDetail>(`/risk/cases/${encoded(caseId)}/actions/${encoded(actionId)}/status`, {
      method: 'PUT', body: JSON.stringify({ targetStatus, resultSummary }),
    }),
  studentActions: () => request<StudentActionProjection[]>('/risk/me/actions'),
  addStudentActionFeedback: (actionId: string, body: string) =>
    request<StudentActionProjection>(`/risk/me/actions/${encoded(actionId)}/feedback`, {
      method: 'POST', body: JSON.stringify({ body }),
    }),
  compareClasses: (classNames: string[], period: '7D' | '30D' | 'TERM' = '30D') => {
    const params = new URLSearchParams()
    classNames.forEach((name) => params.append('classNames', name))
    params.set('period', period)
    return request<ClassComparisonResult>(`/counselor/classes/compare?${params.toString()}`)
  },
  governanceDatasets: () => request<GovernanceDatasetList>('/admin/datasets'),
  datasetImpact: (versionId: string) => request<DatasetImpact>(`/admin/datasets/${encoded(versionId)}/impact`),
  changeDatasetStatus: (versionId: string, status: 'ENABLED' | 'DEPRECATED' | 'RETIRED', reason: string) =>
    request<GovernanceDatasetVersion>(`/admin/datasets/${encoded(versionId)}/status`, {
      method: 'PUT', body: JSON.stringify({ status, reason }),
    }),
  governanceDeployments: () => request<GovernanceModelDeploymentList>('/admin/model-deployments'),
  rollbackDeployment: (taskName: string, expectedActiveVersionId: string, targetVersionId: string, reason: string) =>
    request<ModelRollbackResult>(`/admin/model-deployments/${encoded(taskName)}/rollback`, {
      method: 'POST', body: JSON.stringify({ expectedActiveVersionId, targetVersionId, reason }),
    }),
}
