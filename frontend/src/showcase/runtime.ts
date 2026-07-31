import type { AssistantEvent, SseJobEvent, UserRole } from '@/shared/types/api'

export const showcaseMode = import.meta.env.VITE_EDUTWIN_MODE === 'showcase'
export const showcaseUiMode = showcaseMode || import.meta.env.VITE_EDUTWIN_DEMO_PROFILE === 'showcase'

const STORAGE_KEY = 'edutwin.showcase.state.v1'
const roles: UserRole[] = ['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN']

interface ShowcaseStudent {
  studentId: string
  username: string
  displayName: string
  studentNumber: string
  college: string
  major: string
  cohortYear: number
  className: string
  performance: number
  engagement: number
  risk: number
}

interface ShowcaseCourse {
  courseId: string
  code: string
  title: string
  name: string
  termLabel: string
  credits: number
  description: string
  startsOn: string
  status: string
  college: string
  department: string
  instructorName: string
  teacherId: string
  sectionCount: number
  assessmentCount: number
  enrolledStudentCount: number
}

interface ShowcaseState {
  activeRole: UserRole
  notificationRead: string[]
  answers: Record<string, Array<Record<string, unknown>>>
  planGenerations: Record<string, number>
  taskStatus: Record<string, string>
  actionFeedback: Record<string, Array<Record<string, unknown>>>
  conversations: Array<Record<string, unknown>>
  messages: Record<string, Array<Record<string, unknown>>>
  counter: number
}

interface ShowcaseData {
  manifest: Record<string, unknown>
  accounts: Record<string, UserRole>
  students: ShowcaseStudent[]
  teachers: Array<{ teacherId: string; username: string; displayName: string }>
  counselor: { userId: string; username: string; displayName: string }
  administrator: { userId: string; username: string; displayName: string }
  classes: string[]
  courses: ShowcaseCourse[]
  skills: Array<Record<string, unknown>>
  questions: Array<Record<string, unknown>>
  outlines: Record<string, Record<string, unknown>>
  snapshots: Record<string, Array<Record<string, unknown>>>
  dashboards: Record<string, Record<string, unknown>>
  plans: Record<string, Record<string, unknown>>
  riskCases: Array<Record<string, unknown>>
  riskDetails: Record<string, Record<string, unknown>>
  organizations: Array<Record<string, unknown>>
  activityTrend: Array<Record<string, unknown>>
}

export interface ShowcaseResponse {
  status: number
  body?: unknown
}

let dataPromise: Promise<ShowcaseData> | null = null
let memoryState: ShowcaseState | null = null

function initialState(): ShowcaseState {
  return {
    activeRole: 'STUDENT',
    notificationRead: [],
    answers: {},
    planGenerations: {},
    taskStatus: {},
    actionFeedback: {},
    conversations: [],
    messages: {},
    counter: 1,
  }
}

function readState() {
  if (memoryState) return memoryState
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    memoryState = raw ? { ...initialState(), ...JSON.parse(raw) as ShowcaseState } : initialState()
  } catch {
    memoryState = initialState()
  }
  return memoryState
}

function writeState(state: ShowcaseState) {
  memoryState = state
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

function nextId(prefix: string) {
  const state = readState()
  const id = `${prefix}-${state.counter}`
  state.counter += 1
  writeState(state)
  return id
}

function bodyOf(init: RequestInit) {
  if (typeof init.body !== 'string') return {} as Record<string, unknown>
  try {
    return JSON.parse(init.body) as Record<string, unknown>
  } catch {
    return {} as Record<string, unknown>
  }
}

function now() {
  return new Date().toISOString()
}

async function data() {
  dataPromise ??= fetch(`${import.meta.env.BASE_URL}showcase-data.json`).then(async (response) => {
    if (!response.ok) throw new Error('展示数据加载失败。')
    return await response.json() as ShowcaseData
  })
  return dataPromise
}

function currentUser(source: ShowcaseData) {
  const role = readState().activeRole
  const identity = role === 'STUDENT'
    ? source.students[0]
    : role === 'TEACHER'
      ? source.teachers[0]
      : role === 'COUNSELOR'
        ? source.counselor
        : source.administrator
  return {
    userId: 'showcase-reviewer',
    username: `demo.${role.toLowerCase()}`,
    displayName: identity.displayName,
    role,
    activeRole: role,
    availableRoles: roles,
    mustChangePassword: false,
    accessibleCourseIds: source.courses.map((course) => course.courseId),
  }
}

function session(source: ShowcaseData) {
  return {
    accessToken: `showcase-${readState().activeRole.toLowerCase()}`,
    tokenType: 'Bearer',
    expiresAt: '2099-12-31T23:59:59.000Z',
    user: currentUser(source),
  }
}

function profile(source: ShowcaseData) {
  const user = currentUser(source)
  return {
    userId: user.userId,
    username: user.username,
    displayName: user.displayName,
    officialId: 'SHOWCASE-ONLY',
    roles,
    organizations: [{ organizationId: 'org-college', code: 'CS', displayName: '计算机与软件学院', unitType: 'COLLEGE' }],
    email: 'showcase@example.invalid',
    phone: null,
    avatarKey: 'avatar-1',
    themeColor: 'teal',
  }
}

function courseList(source: ShowcaseData) {
  const role = readState().activeRole
  return {
    items: source.courses.map((course) => ({
      courseId: course.courseId,
      code: course.code,
      name: course.title,
      termLabel: course.termLabel,
      credits: course.credits,
      college: course.college,
      department: course.department,
      instructorName: course.instructorName,
      role,
    })),
    total: source.courses.length,
  }
}

function lmsCourse(course: ShowcaseCourse, role: 'OWNER' | 'LEARNER') {
  return {
    ...course,
    assignmentRole: role,
  }
}

function riskBand(probability: number) {
  return probability >= 0.68 ? 'HIGH' : probability >= 0.38 ? 'MEDIUM' : 'LOW'
}

function bounded(value: number) {
  return Math.min(0.99, Math.max(0.01, Number(value.toFixed(4))))
}

function currentTwin(source: ShowcaseData, courseId: string) {
  const base = structuredClone(source.snapshots[courseId]?.at(-1)) as Record<string, unknown> | undefined
  if (!base) return undefined
  const answers = readState().answers[courseId] ?? []
  if (!answers.length) return base

  const impact = answers.reduce((sum, answer) => sum + (answer.correct ? 1 : -0.5), 0)
  const masteryDelta = impact * 0.012
  const risk = base.risk as Record<string, unknown>
  const probability = bounded(Number(risk.probability) - impact * 0.008)
  const lastAnswer = answers.at(-1)

  return {
    ...base,
    snapshotVersion: Number(base.snapshotVersion) + answers.length,
    capturedAt: String(lastAnswer?.occurredAt ?? now()),
    mastery: (base.mastery as Array<Record<string, unknown>>).map((skill, index) => ({
      ...skill,
      probability: bounded(Number(skill.probability) + masteryDelta * (index === 0 ? 1 : 0.55)),
    })),
    engagementScore: bounded(Number(base.engagementScore) + answers.length * 0.003),
    stabilityScore: bounded(Number(base.stabilityScore) + impact * 0.004),
    nextCorrectProbability: bounded(Number(base.nextCorrectProbability) + impact * 0.01),
    risk: { ...risk, probability, riskBand: riskBand(probability), predictedAt: String(lastAnswer?.occurredAt ?? now()) },
  }
}

function notifications() {
  const state = readState()
  const items = [
    { notificationId: 'notification-1', type: 'PLAN_UPDATED', title: '学习计划已更新', body: '规则引擎已根据最近练习结果调整任务顺序。', deepLink: '/student/courses/course-1/plan', createdAt: '2026-07-01T08:00:00.000Z', expiresAt: null },
    { notificationId: 'notification-2', type: 'RISK_ACTION', title: '行动项待反馈', body: '完成本周复习后可提交一句学习反馈。', deepLink: '/student/actions', createdAt: '2026-06-30T08:00:00.000Z', expiresAt: null },
  ].map((item) => ({ ...item, read: state.notificationRead.includes(item.notificationId) }))
  return items
}

function studentAnalytics(source: ShowcaseData, courseId: string, period: string) {
  const snapshots = source.snapshots[courseId] ?? []
  const latest = snapshots.at(-1) as Record<string, unknown> | undefined
  return {
    courseId,
    studentId: source.students[0].studentId,
    period,
    generatedAt: source.manifest.referenceTime,
    answerCount: 86,
    correctRate: 0.79,
    activeDays: 18,
    totalDurationMinutes: 524,
    currentMastery: 0.76,
    currentRisk: (latest?.risk as { probability?: number } | undefined)?.probability ?? 0.24,
    currentEngagement: latest?.engagementScore ?? 0.8,
    snapshotTrend: snapshots,
    activityTrend: source.activityTrend,
    behaviorDistribution: [
      { behaviorType: '练习作答', eventCount: 86, studentCount: 1, share: 0.45 },
      { behaviorType: '课程浏览', eventCount: 71, studentCount: 1, share: 0.37 },
      { behaviorType: '计划执行', eventCount: 34, studentCount: 1, share: 0.18 },
    ],
    activityHeatmap: Array.from({ length: 21 }, (_, index) => ({ weekday: index % 7, hour: 9 + Math.floor(index / 7) * 5, eventCount: 1 + index % 6 })),
  }
}

function planLifecycle(source: ShowcaseData, courseId: string): Record<string, unknown> & { tasks: Array<Record<string, unknown>> } {
  const base = structuredClone(source.plans[courseId]) as Record<string, unknown>
  const state = readState()
  const answerCount = state.answers[courseId]?.length ?? 0
  const generationCount = state.planGenerations[courseId] ?? 0
  const tasks = (base.tasks as Array<Record<string, unknown>>).map((task) => {
    const status = state.taskStatus[String(task.planTaskId)]
    return status ? { ...task, status, startedAt: status === 'IN_PROGRESS' ? now() : task.startedAt, skippedAt: status === 'SKIPPED' ? now() : task.skippedAt } : task
  })
  return { ...base, version: Number(base.version) + answerCount + generationCount, tasks }
}

function learningPlan(source: ShowcaseData, courseId: string) {
  const lifecycle = planLifecycle(source, courseId)
  const tasks = lifecycle.tasks as Array<Record<string, unknown>>
  const completed = tasks.filter((task) => task.status === 'COMPLETED').length
  return {
    planId: lifecycle.planId,
    studentId: lifecycle.studentId,
    courseId,
    version: lifecycle.version,
    generatedAt: source.manifest.referenceTime,
    validUntil: lifecycle.validUntil,
    status: lifecycle.status,
    completionRate: tasks.length ? completed / tasks.length : 0,
    tasks: tasks.map((task, index) => ({
      taskId: task.planTaskId,
      sequence: index + 1,
      taskType: task.taskType,
      questionId: task.questionId,
      skillId: task.skillId,
      targetMastery: 0.8,
      reasonCode: task.reasonCode,
      dueAt: task.dueAt,
      status: task.status === 'SKIPPED' ? 'COMPLETED' : task.status,
    })),
  }
}

function roster(source: ShowcaseData) {
  return {
    items: source.students.slice(0, 48).map((student) => ({ ...student, enrolled: true, enrollmentStatus: 'ACTIVE' })),
    total: 48,
    enrolledCount: 48,
  }
}

function assessments(source: ShowcaseData, courseId: string) {
  return ((source.outlines[courseId]?.assessments ?? []) as Array<Record<string, unknown>>)
}

function assessmentDetail(source: ShowcaseData, assessmentId: string) {
  const course = source.courses.find((item) => assessmentId.startsWith(item.courseId)) ?? source.courses[0]
  const summary = assessments(source, course.courseId).find((item) => item.assessmentId === assessmentId) ?? assessments(source, course.courseId)[0]
  return {
    ...summary,
    questions: source.questions.filter((item) => item.courseId === course.courseId).map((question, index) => ({
      questionId: question.questionId,
      prompt: question.prompt,
      options: question.choices,
      points: 25,
      position: index + 1,
    })),
  }
}

function gradeList(source: ShowcaseData, courseId: string) {
  return {
    items: assessments(source, courseId).map((assessment, index) => ({
      assessmentId: assessment.assessmentId,
      title: assessment.title,
      studentId: source.students[0].studentId,
      displayName: source.students[0].displayName,
      submitted: index < 2,
      score: index < 2 ? 84 + index * 4 : null,
      maxScore: 100,
      percentage: index < 2 ? 84 + index * 4 : null,
      submittedAt: index < 2 ? `2026-06-${String(12 + index * 7).padStart(2, '0')}T08:00:00.000Z` : null,
    })),
    total: 3,
  }
}

function riskDetail(source: ShowcaseData, caseId: string) {
  const detail = structuredClone(source.riskDetails[caseId] ?? source.riskDetails['risk-case-1'])
  const actions = detail.actionItems as Array<Record<string, unknown>>
  for (const action of actions) {
    action.feedback = [...(action.feedback as Array<Record<string, unknown>>), ...(readState().actionFeedback[String(action.actionItemId)] ?? [])]
  }
  return detail
}

function studentActions(source: ShowcaseData) {
  return Object.keys(source.riskDetails).slice(0, 3).flatMap((caseId) => {
    const detail = riskDetail(source, caseId)
    const riskCase = detail.riskCase as Record<string, unknown>
    return (detail.actionItems as Array<Record<string, unknown>>).map((action) => ({
      actionItemId: action.actionItemId,
      caseId,
      courseId: riskCase.courseId,
      courseTitle: riskCase.courseTitle,
      title: action.title,
      description: action.description,
      status: action.status,
      dueAt: action.dueAt,
      resultSummary: action.resultSummary,
      feedback: action.feedback,
    }))
  })
}

function adminUsers(source: ShowcaseData) {
  const createdAt = '2026-02-20T08:00:00.000Z'
  const items = [
    ...source.teachers.map((item) => ({ userId: item.teacherId, username: item.username, displayName: item.displayName, role: 'TEACHER', roles: ['TEACHER'] })),
    ...source.students.slice(0, 12).map((item) => ({ userId: item.studentId, username: item.username, displayName: item.displayName, role: 'STUDENT', roles: ['STUDENT'] })),
    { userId: source.counselor.userId, username: source.counselor.username, displayName: source.counselor.displayName, role: 'COUNSELOR', roles: ['COUNSELOR'] },
    { userId: source.administrator.userId, username: source.administrator.username, displayName: source.administrator.displayName, role: 'ADMIN', roles: ['ADMIN'] },
  ].map((item) => ({ ...item, enabled: true, mustChangePassword: false, originType: 'DEMO_SYNTHETIC', createdAt, updatedAt: createdAt }))
  return { items, total: 101 }
}

function counselorStudents(source: ShowcaseData, url: URL) {
  const page = Number(url.searchParams.get('page') ?? 0)
  const size = Number(url.searchParams.get('size') ?? 20)
  const query = (url.searchParams.get('query') ?? '').toLowerCase()
  const className = url.searchParams.get('className')
  const filtered = source.students.filter((student) => (!query || student.displayName.toLowerCase().includes(query) || student.studentNumber.includes(query)) && (!className || student.className === className))
  const mapped = filtered.map((student) => ({
    ...student,
    courseCount: 6,
    totalCredits: 20,
    averageScorePercentage: Math.round(student.performance * 100),
    averageMastery: student.performance,
    highRiskCourseCount: student.risk >= 0.68 ? 2 : student.risk >= 0.38 ? 1 : 0,
    lastActivityAt: '2026-07-01T07:30:00.000Z',
  }))
  return { items: mapped.slice(page * size, page * size + size), total: mapped.length, page, size }
}

function classComparison(source: ShowcaseData, url: URL) {
  const selected = url.searchParams.getAll('classNames')
  const names = selected.length ? selected : source.classes.slice(0, 2)
  return {
    classes: names.map((className) => {
      const group = source.students.filter((student) => student.className === className)
      const average = (key: 'performance' | 'risk') => group.reduce((sum, item) => sum + item[key], 0) / group.length
      return {
        className, studentCount: group.length, activeCourseEnrollments: group.length * 6,
        averageScorePercentage: Math.round(average('performance') * 100), averageMastery: average('performance'),
        averageRiskProbability: average('risk'), lowRiskCourseCount: 92, mediumRiskCourseCount: 42,
        highRiskCourseCount: 10, planCompletionRate: 0.68,
      }
    }),
    period: url.searchParams.get('period') ?? '30D',
    riskTrend: names.flatMap((className, classIndex) => Array.from({ length: 6 }, (_, index) => ({ className, weekStart: `2026-0${5 + Math.floor(index / 4)}-${String(4 + index * 7).padStart(2, '0')}`, averageRiskProbability: Number((0.42 - index * 0.025 + classIndex * 0.018).toFixed(3)) }))),
  }
}

function problem(status: number, detail: string, code: string): ShowcaseResponse {
  return { status, body: { title: '展示模式限制', status, detail, code, traceId: 'showcase', violations: [] } }
}

export async function resetShowcaseData() {
  localStorage.removeItem(STORAGE_KEY)
  sessionStorage.removeItem('edutwin.session.v2')
  memoryState = initialState()
  if (!showcaseMode && showcaseUiMode) {
    const base = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
    await fetch(`${base}/showcase/reset`, { method: 'POST' })
  }
}

export async function handleShowcaseRequest(path: string, init: RequestInit = {}): Promise<ShowcaseResponse> {
  const source = await data()
  const state = readState()
  const method = (init.method ?? 'GET').toUpperCase()
  const url = new URL(path, 'https://showcase.invalid')
  const pathname = url.pathname
  const payload = bodyOf(init)

  if (pathname === '/auth/login' && method === 'POST') {
    const role = source.accounts[String(payload.username)]
    if (!role) return problem(401, '请选择页面提供的演示角色。', 'SHOWCASE_ACCOUNT_REQUIRED')
    state.activeRole = role
    writeState(state)
    return { status: 200, body: session(source) }
  }
  if (pathname === '/auth/me') return { status: 200, body: currentUser(source) }
  if (pathname === '/auth/role' && method === 'POST') {
    const role = String(payload.role) as UserRole
    if (!roles.includes(role)) return problem(400, '未知角色。', 'INVALID_ROLE')
    state.activeRole = role
    writeState(state)
    return { status: 200, body: session(source) }
  }
  if (pathname === '/me/profile' && method === 'GET') return { status: 200, body: profile(source) }
  if (pathname === '/me/profile' && method === 'PUT') return { status: 200, body: { ...profile(source), ...payload } }
  if (pathname === '/courses') return { status: 200, body: courseList(source) }

  if (pathname === '/notifications/unread-count') return { status: 200, body: { count: notifications().filter((item) => !item.read).length } }
  if (pathname === '/notifications') return { status: 200, body: { items: notifications(), page: 0, size: 20, total: notifications().length } }
  if (/^\/notifications\/[^/]+\/read$/.test(pathname)) {
    const id = pathname.split('/')[2]
    if (!state.notificationRead.includes(id)) state.notificationRead.push(id)
    writeState(state)
    return { status: 204 }
  }
  if (pathname === '/notifications/read-all') {
    state.notificationRead = notifications().map((item) => item.notificationId)
    writeState(state)
    return { status: 204 }
  }

  const dashboardMatch = pathname.match(/^\/teacher\/courses\/([^/]+)\/dashboard$/)
  if (dashboardMatch) return { status: 200, body: { ...source.dashboards[dashboardMatch[1]], period: url.searchParams.get('period') ?? '30D' } }
  const analyticsMatch = pathname.match(/^\/student\/courses\/([^/]+)\/analytics$/)
  if (analyticsMatch) return { status: 200, body: studentAnalytics(source, analyticsMatch[1], url.searchParams.get('period') ?? '30D') }
  const twinMatch = pathname.match(/^\/courses\/([^/]+)\/students\/([^/]+)\/twin\/(current|history)$/)
  if (twinMatch) {
    const items = source.snapshots[twinMatch[1]] ?? []
    const current = currentTwin(source, twinMatch[1])
    return twinMatch[3] === 'current'
      ? { status: 200, body: current }
      : { status: 200, body: { items: current && readState().answers[twinMatch[1]]?.length ? [...items, current].reverse() : [...items].reverse(), total: items.length + (current && readState().answers[twinMatch[1]]?.length ? 1 : 0), nextBeforeVersion: null } }
  }

  const nextPracticeMatch = pathname.match(/^\/courses\/([^/]+)\/practice\/next$/)
  if (nextPracticeMatch) {
    const items = source.questions.filter((item) => item.courseId === nextPracticeMatch[1])
    const index = (state.answers[nextPracticeMatch[1]]?.length ?? 0) % items.length
    const question = structuredClone(items[index])
    delete question.correctChoiceId
    return { status: 200, body: question }
  }
  const planTaskMatch = pathname.match(/^\/courses\/([^/]+)\/practice\/tasks\/([^/]+)$/)
  if (planTaskMatch) {
    const plan = planLifecycle(source, planTaskMatch[1])
    const task = (plan.tasks as Array<Record<string, unknown>>).find((item) => item.planTaskId === planTaskMatch[2]) ?? (plan.tasks as Array<Record<string, unknown>>)[0]
    const question = source.questions.find((item) => item.questionId === task.questionId) ?? source.questions[0]
    return { status: 200, body: { ...task, courseId: planTaskMatch[1], prompt: question.prompt, answerType: 'SINGLE_CHOICE', optionsJson: JSON.stringify(question.choices) } }
  }
  const answersMatch = pathname.match(/^\/courses\/([^/]+)\/answers$/)
  if (answersMatch && method === 'GET') {
    const items = [...(state.answers[answersMatch[1]] ?? [])].reverse()
    return { status: 200, body: { items, total: items.length, page: 0, size: 20 } }
  }
  if (answersMatch && method === 'POST') {
    const question = source.questions.find((item) => item.questionId === payload.questionId) ?? source.questions[0]
    const choices = question.choices as Array<{ choiceId: string; label: string }>
    const selected = choices.find((item) => item.choiceId === payload.selectedChoiceId) ?? choices[0]
    const correct = choices.find((item) => item.choiceId === question.correctChoiceId) ?? choices[0]
    const history = state.answers[answersMatch[1]] ?? []
    history.push({
      answerEventId: nextId('answer'), questionId: question.questionId, prompt: question.prompt,
      selectedChoice: selected, correctChoice: correct, correct: selected.choiceId === correct.choiceId,
      skills: source.skills.filter((item) => (question.skillIds as string[]).includes(String(item.skillId))).map((item) => ({ skillId: item.skillId, name: item.name })),
      attemptNumber: 1, eventSequence: history.length + 1, responseTimeMs: 8400,
      occurredAt: payload.occurredAt ?? now(), sourceType: 'ONLINE',
    })
    state.answers[answersMatch[1]] = history
    writeState(state)
    const jobId = nextId('analysis-job')
    return { status: 200, body: analysisJob(source, answersMatch[1], jobId) }
  }
  const jobMatch = pathname.match(/^\/analysis\/jobs\/([^/]+)$/)
  if (jobMatch) return { status: 200, body: analysisJob(source, 'course-1', jobMatch[1]) }

  const planMatch = pathname.match(/^\/courses\/([^/]+)\/students\/([^/]+)\/learning-plans(?:\/current)?$/)
  if (planMatch) {
    if (method === 'POST') {
      state.planGenerations[planMatch[1]] = (state.planGenerations[planMatch[1]] ?? 0) + 1
      writeState(state)
    }
    return { status: 200, body: learningPlan(source, planMatch[1]) }
  }
  const lifecycleMatch = pathname.match(/^\/courses\/([^/]+)\/students\/([^/]+)\/learning-plans\/current\/lifecycle$/)
  if (lifecycleMatch) return { status: 200, body: planLifecycle(source, lifecycleMatch[1]) }
  const taskMutationMatch = pathname.match(/^\/courses\/([^/]+)\/students\/([^/]+)\/learning-plans\/current\/tasks\/([^/]+)\/(start|skip)$/)
  if (taskMutationMatch && method === 'POST') {
    state.taskStatus[taskMutationMatch[3]] = taskMutationMatch[4] === 'start' ? 'IN_PROGRESS' : 'SKIPPED'
    writeState(state)
    return { status: 200, body: planLifecycle(source, taskMutationMatch[1]) }
  }
  const diagnosisMatch = pathname.match(/^\/courses\/([^/]+)\/students\/([^/]+)\/diagnoses$/)
  if (diagnosisMatch) {
    const snapshot = source.snapshots[diagnosisMatch[1]]?.at(-1) as Record<string, unknown>
    const risk = snapshot.risk as { riskBand: string }
    return { status: 200, body: {
      diagnosisId: nextId('diagnosis'), schemaVersion: '1.0', generatedAt: now(),
      summary: '当前学习节奏总体稳定，规则推理识别出一个需要持续巩固的知识点。', riskBand: risk.riskBand,
      strengths: ['近期练习完成度保持稳定。'], concerns: ['复杂度分析相关题目仍存在波动。'],
      recommendedActions: ['完成学习计划中的三道巩固练习。'],
      evidence: [{ rank: 1, featureName: 'recent_correct_rate', rawValue: 0.71, direction: 'DECREASES_RISK', contribution: -0.24, baseValue: 0.34, outputUnit: 'LOG_ODDS' }],
      learningPlan: learningPlan(source, diagnosisMatch[1]),
    } }
  }

  if (pathname === '/lms/teacher/courses') return { status: 200, body: { items: source.courses.map((course) => lmsCourse(course, 'OWNER')), total: source.courses.length } }
  const managedOutlineMatch = pathname.match(/^\/lms\/teacher\/courses\/([^/]+)\/sections$/)
  const publishedOutlineMatch = pathname.match(/^\/lms\/courses\/([^/]+)$/)
  if (managedOutlineMatch || publishedOutlineMatch) {
    const courseId = (managedOutlineMatch ?? publishedOutlineMatch)![1]
    const outline = structuredClone(source.outlines[courseId])
    ;(outline.course as Record<string, unknown>).assignmentRole = managedOutlineMatch ? 'OWNER' : 'LEARNER'
    return { status: 200, body: outline }
  }
  const rosterMatch = pathname.match(/^\/lms\/teacher\/courses\/([^/]+)\/roster$/)
  if (rosterMatch) return { status: 200, body: roster(source) }
  if (/^\/lms\/teacher\/courses\/[^/]+\/knowledge-skills$/.test(pathname)) return { status: 200, body: { items: source.skills, total: source.skills.length } }
  const assessmentListMatch = pathname.match(/^\/lms\/(?:teacher\/)?courses\/([^/]+)\/assessments$/)
  if (assessmentListMatch) return { status: 200, body: { items: assessments(source, assessmentListMatch[1]), total: assessments(source, assessmentListMatch[1]).length } }
  const assessmentMatch = pathname.match(/^\/lms\/assessments\/([^/]+)$/)
  if (assessmentMatch) return { status: 200, body: assessmentDetail(source, assessmentMatch[1]) }
  const attemptMatch = pathname.match(/^\/lms\/assessments\/([^/]+)\/attempts$/)
  if (attemptMatch) return { status: 200, body: { items: [], total: 0, currentSubmissionId: null, currentScore: null } }
  const attemptRequestMatch = pathname.match(/^\/lms\/assessments\/([^/]+)\/attempt-requests$/)
  if (attemptRequestMatch && method === 'GET') return { status: 200, body: { items: [], total: 0 } }
  const submissionMatch = pathname.match(/^\/lms\/assessments\/([^/]+)\/submissions$/)
  if (submissionMatch && method === 'GET') return problem(404, '当前演示考核尚未提交。', 'SUBMISSION_NOT_FOUND')
  if (submissionMatch && method === 'POST') return { status: 200, body: { submissionId: nextId('submission'), assessmentId: submissionMatch[1], studentId: source.students[0].studentId, score: 100, maxScore: 100, percentage: 100, submittedAt: now(), answers: [] } }
  const gradeMatch = pathname.match(/^\/lms\/courses\/([^/]+)\/grades$/)
  if (gradeMatch) return { status: 200, body: gradeList(source, gradeMatch[1]) }
  const teacherGradeMatch = pathname.match(/^\/lms\/teacher\/assessments\/([^/]+)\/submissions$/)
  if (teacherGradeMatch) return { status: 200, body: gradeList(source, teacherGradeMatch[1].split('-assessment-')[0]) }

  if (pathname === '/risk/cases') return { status: 200, body: { items: source.riskCases, total: source.riskCases.length, page: 0, size: 20 } }
  const riskCaseMatch = pathname.match(/^\/risk\/cases\/([^/]+)$/)
  if (riskCaseMatch) return { status: 200, body: riskDetail(source, riskCaseMatch[1]) }
  if (pathname === '/risk/me/actions') return { status: 200, body: studentActions(source) }
  const feedbackMatch = pathname.match(/^\/risk\/me\/actions\/([^/]+)\/feedback$/)
  if (feedbackMatch && method === 'POST') {
    const feedback = { feedbackId: nextId('feedback'), studentId: source.students[0].studentId, body: String(payload.body ?? ''), createdAt: now() }
    state.actionFeedback[feedbackMatch[1]] = [...(state.actionFeedback[feedbackMatch[1]] ?? []), feedback]
    writeState(state)
    return { status: 200, body: studentActions(source).find((item) => item.actionItemId === feedbackMatch[1]) }
  }

  if (pathname === '/counselor/students') return { status: 200, body: counselorStudents(source, url) }
  const overviewMatch = pathname.match(/^\/counselor\/students\/([^/]+)\/overview$/)
  if (overviewMatch) {
    const student = source.students.find((item) => item.studentId === overviewMatch[1]) ?? source.students[0]
    const summary = counselorStudents(source, new URL('https://showcase.invalid/counselor/students')).items.find((item) => item.studentId === student.studentId)
    return { status: 200, body: { student: summary, courses: source.courses.map((course, index) => ({ courseId: course.courseId, code: course.code, title: course.title, termLabel: course.termLabel, credits: course.credits, instructors: [course.instructorName], enrollmentStatus: 'ACTIVE', completedLessons: 5 + index % 3, totalLessons: 9, submittedAssessments: 2, publishedAssessments: 3, scorePercentage: 78 + index, averageMastery: student.performance, riskBand: riskBand(student.risk), riskProbability: student.risk, lastActivityAt: '2026-07-01T07:30:00.000Z' })) } }
  }
  if (pathname === '/counselor/classes/compare') return { status: 200, body: classComparison(source, url) }

  if (pathname === '/admin/overview') return { status: 200, body: { enabledUsers: 101, teachers: 3, students: 96, counselors: 1, enabledDatasets: 1, modelDeployments: 2, generatedAt: source.manifest.referenceTime } }
  if (pathname === '/admin/users') return { status: 200, body: adminUsers(source) }
  if (pathname === '/admin/organizations') return { status: 200, body: { items: source.organizations, total: 6 } }
  if (pathname === '/admin/academic-terms') return { status: 200, body: { items: [{ termId: 'term-2026-spring', code: '2025-2026-2', displayName: '2025-2026 学年第二学期', startsOn: '2026-02-23', endsOn: '2026-07-05', enabled: true }], total: 1 } }
  if (pathname === '/admin/student-groups') return { status: 200, body: { items: source.classes.map((className) => ({ scopeType: 'CLASS', college: '计算机与软件学院', major: className.startsWith('软件') ? '软件工程' : '计算机科学与技术', cohortYear: 2023, className, studentCount: 24 })), total: 4 } }
  if (/^\/admin\/counselors\/[^/]+\/scopes$/.test(pathname)) return { status: 200, body: { items: source.classes.map((className) => ({ scopeType: 'CLASS', college: '计算机与软件学院', major: className.startsWith('软件') ? '软件工程' : '计算机科学与技术', cohortYear: 2023, className })), total: 4 } }
  if (pathname === '/admin/ai-configuration') return { status: 200, body: { source: 'ENVIRONMENT', revision: 1, enabled: false, apiBaseUrl: '', model: 'template-diagnosis-v1', apiKeyConfigured: false, writeAvailable: false, lastTestStatus: 'SKIPPED_DISABLED', lastTestedAt: null, lastTestLatencyMs: null, updatedAt: source.manifest.referenceTime, updatedBy: null } }
  if (pathname === '/admin/transparency') return { status: 200, body: transparency(source) }
  if (pathname === '/admin/datasets') return { status: 200, body: { items: [{ versionId: 'showcase-synthetic-v1', sourceKey: 'EDUTWIN_SHOWCASE', sourceName: 'EduTwin 确定性合成数据', lifecycleStatus: 'ENABLED', rowCount: 96, manifestSha256: '42'.repeat(32), processedAt: source.manifest.referenceTime, referenced: true }], total: 1 } }
  if (pathname === '/admin/model-deployments') return { status: 200, body: { items: [{ taskName: 'RISK', activeVersionId: 'rule-risk-v1', rollbackVersionId: null, deployedAt: source.manifest.referenceTime, deployedBy: 'showcase-generator' }, { taskName: 'DIAGNOSIS', activeVersionId: 'template-diagnosis-v1', rollbackVersionId: null, deployedAt: source.manifest.referenceTime, deployedBy: 'showcase-generator' }], total: 2 } }
  if (pathname === '/admin/audit-events') return { status: 200, body: auditEvents() }
  const auditMatch = pathname.match(/^\/admin\/audit-events\/([^/]+)$/)
  if (auditMatch) return { status: 200, body: auditEvents().items.find((item) => item.id === auditMatch[1]) }

  if (pathname === '/assistant/conversations' && method === 'GET') return { status: 200, body: { items: state.conversations } }
  if (pathname === '/assistant/conversations' && method === 'POST') {
    const conversation = { conversationId: nextId('conversation'), title: String(payload.title ?? '新的分析会话'), activeRole: state.activeRole, createdAt: now(), updatedAt: now() }
    state.conversations.unshift(conversation)
    state.messages[String(conversation.conversationId)] = []
    writeState(state)
    return { status: 200, body: conversation }
  }
  const conversationMatch = pathname.match(/^\/assistant\/conversations\/([^/]+)$/)
  if (conversationMatch && method === 'PUT') {
    const conversation = state.conversations.find((item) => item.conversationId === conversationMatch[1])
    if (conversation) conversation.title = payload.title
    writeState(state)
    return { status: 200, body: conversation }
  }
  if (conversationMatch && method === 'DELETE') {
    state.conversations = state.conversations.filter((item) => item.conversationId !== conversationMatch[1])
    delete state.messages[conversationMatch[1]]
    writeState(state)
    return { status: 204 }
  }
  const messagesMatch = pathname.match(/^\/assistant\/conversations\/([^/]+)\/messages$/)
  if (messagesMatch && method === 'GET') return { status: 200, body: { items: state.messages[messagesMatch[1]] ?? [] } }
  if (messagesMatch && method === 'POST') {
    const userMessageId = nextId('message-user')
    const assistantMessageId = nextId('message-assistant')
    const messages = state.messages[messagesMatch[1]] ?? []
    messages.push(message(userMessageId, 'USER', String(payload.content ?? ''), 'COMPLETED'))
    messages.push(message(assistantMessageId, 'ASSISTANT', assistantReply(state.activeRole), 'COMPLETED'))
    state.messages[messagesMatch[1]] = messages
    writeState(state)
    return { status: 200, body: { userMessageId, assistantMessageId, status: 'PROCESSING', streamUrl: `/assistant/messages/${assistantMessageId}/events` } }
  }

  if (method !== 'GET') return problem(403, '展示模式已禁用管理类破坏性操作。', 'SHOWCASE_READ_ONLY')
  return problem(404, `展示数据未覆盖接口 ${pathname}`, 'SHOWCASE_ROUTE_NOT_FOUND')
}

function analysisJob(source: ShowcaseData, courseId: string, jobId: string) {
  return {
    jobId, studentId: source.students[0].studentId, courseId, status: 'COMPLETED', stage: 'COMPLETED',
    lastEventSequence: 3, submittedAt: now(), startedAt: now(), completedAt: now(), failure: null,
    result: { twinUrl: `/courses/${courseId}/students/${source.students[0].studentId}/twin/current`, learningPlanUrl: `/courses/${courseId}/students/${source.students[0].studentId}/learning-plans/current`, diagnosisUrl: `/courses/${courseId}/students/${source.students[0].studentId}/diagnoses` },
  }
}

function transparency(source: ShowcaseData) {
  return {
    provenance: {
      synthetic: true, matchingVersion: 'showcase-generator-v1', matchingSeed: 42, generatedAt: source.manifest.referenceTime,
      sources: [{ sourceId: 'EDUTWIN_SHOWCASE', datasetVersion: 'showcase-synthetic-v1', sourceSha256: '42'.repeat(32), manifestSha256: '24'.repeat(32), licenseName: 'Apache-2.0', licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0', sourceUrl: 'generated://showcase', rowCount: 96, processingRunId: 'showcase-seed-42', processingConfigSha256: '12'.repeat(32), splitSeed: 42 }],
    },
    models: [
      { model: { purpose: 'RISK', family: 'RULE', modelName: 'rule-risk', modelVersion: 'v1', artifactSha256: '31'.repeat(32), calibratorVersion: null }, manifestSha256: '41'.repeat(32), configSha256: '51'.repeat(32), dependencyLockSha256: '61'.repeat(32), selected: true, metrics: [{ name: 'deterministic_fixture', split: 'TEST', value: 1 }] },
      { model: { purpose: 'DIAGNOSIS', family: 'RULE', modelName: 'template-diagnosis', modelVersion: 'v1', artifactSha256: '71'.repeat(32), calibratorVersion: null }, manifestSha256: '81'.repeat(32), configSha256: '91'.repeat(32), dependencyLockSha256: 'a1'.repeat(32), selected: true, metrics: [{ name: 'schema_validity', split: 'TEST', value: 1 }] },
    ],
    generatedAt: source.manifest.referenceTime,
  }
}

function auditEvents() {
  const items = Array.from({ length: 8 }, (_, index) => ({
    id: `audit-${index + 1}`, actorUserId: 'showcase-reviewer', actorDisplayName: '作品集体验者', activeRole: roles[index % roles.length],
    action: ['ROLE_SWITCHED', 'PRACTICE_SUBMITTED', 'PLAN_GENERATED', 'ACTION_FEEDBACK_ADDED'][index % 4],
    targetType: 'SHOWCASE', targetId: `target-${index + 1}`, outcome: 'SUCCEEDED', reason: null, errorCode: null,
    beforeJson: null, afterJson: '{"synthetic":true}', metadataJson: '{"profile":"showcase"}', correlationId: `showcase-${index + 1}`,
    createdAt: `2026-07-01T0${index}:00:00.000Z`,
  }))
  return { items, total: items.length, page: 0, size: 20 }
}

function message(messageId: string, role: 'USER' | 'ASSISTANT', content: string, status: string) {
  return { messageId, role, status, content, sources: role === 'ASSISTANT' ? [{ label: '确定性合成数据', queriedAt: now(), deepLink: '/student/courses/course-1/twin' }] : [], deepLinks: role === 'ASSISTANT' ? ['/student/courses/course-1/plan'] : [], errorCode: null, retryable: false, createdAt: now(), completedAt: now() }
}

function assistantReply(role: UserRole) {
  if (role === 'TEACHER') return '当前课程共有 **48 名学生**，高风险人数较少。建议先查看“复杂度分析”薄弱知识点，并对近期活跃度下降的学生安排短周期练习。'
  if (role === 'COUNSELOR') return '四个班级的整体风险趋势正在下降。当前有 **12 个演示风险工单**，其中 2 个处于跟进中，可优先核对行动项反馈。'
  if (role === 'ADMIN') return '展示环境包含 **96 名合成学生、6 门课程**，采用规则风险推理与模板诊断，不包含受限原始数据，也不需要外部 API Key。'
  return '近期掌握度持续上升，当前建议完成“复杂度分析”巩固练习，并在学习计划中记录完成情况。该结论仅来自确定性合成数据。'
}

export async function simulateAnalysisStream(jobId: string, onEvent: (event: SseJobEvent) => void) {
  const source = await data()
  const job = analysisJob(source, 'course-1', jobId)
  onEvent({ sequence: 1, eventType: 'analysis.completed', occurredAt: now(), job } as SseJobEvent)
}

export async function simulateAssistantStream(messageId: string, onEvent: (event: AssistantEvent) => void) {
  const source = await data()
  const content = assistantReply(readState().activeRole)
  const occurredAt = now()
  const events: AssistantEvent[] = [
    { sequence: 1, messageId, type: 'message.started', occurredAt, data: {} },
    { sequence: 2, messageId, type: 'tool.started', occurredAt, data: { toolName: '读取合成学习数据' } },
    { sequence: 3, messageId, type: 'tool.completed', occurredAt, data: { toolName: '读取合成学习数据' } },
    { sequence: 4, messageId, type: 'message.delta', occurredAt, data: { delta: content } },
    { sequence: 5, messageId, type: 'message.completed', occurredAt, data: { content, sources: [{ label: '确定性合成数据', queriedAt: occurredAt, deepLink: '/student/courses/course-1/twin' }], deepLinks: ['/student/courses/course-1/plan'] } },
  ]
  for (const event of events) onEvent(event)
  void source
}
