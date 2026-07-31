import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'

const dataPath = process.env.EDUTWIN_SHOWCASE_DATA || new URL('./showcase-data.json', import.meta.url)
const data = JSON.parse(await readFile(dataPath, 'utf8'))
const port = Number(process.env.PORT || 8080)
const roles = ['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN']
const state = { role: 'STUDENT', answers: {}, planGenerations: {}, feedback: {}, conversations: [], messages: {}, counter: 1 }

const nextId = (prefix) => `${prefix}-${state.counter++}`
const now = () => new Date().toISOString()
const pathOf = (url) => new URL(url, 'http://showcase.local')
const json = (response, status, body) => {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  })
  response.end(body === undefined ? '' : JSON.stringify(body))
}
const problem = (response, status, detail, code) => json(response, status, {
  title: '展示模式限制', status, detail, code, traceId: 'showcase-api', violations: [],
})
const readBody = async (request) => {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  if (!chunks.length) return {}
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { return {} }
}

function currentIdentity() {
  if (state.role === 'STUDENT') return data.students[0]
  if (state.role === 'TEACHER') return data.teachers[0]
  if (state.role === 'COUNSELOR') return data.counselor
  return data.administrator
}

function currentUser() {
  return {
    userId: 'showcase-reviewer', username: `demo.${state.role.toLowerCase()}`,
    displayName: currentIdentity().displayName, role: state.role, activeRole: state.role,
    availableRoles: roles, mustChangePassword: false,
    accessibleCourseIds: data.courses.map((course) => course.courseId),
  }
}

function authSession() {
  return { accessToken: `showcase-${state.role.toLowerCase()}`, tokenType: 'Bearer', expiresAt: '2099-12-31T23:59:59.000Z', user: currentUser() }
}

function courseList() {
  return {
    items: data.courses.map((course) => ({ courseId: course.courseId, code: course.code, name: course.title, termLabel: course.termLabel, credits: course.credits, college: course.college, department: course.department, instructorName: course.instructorName, role: state.role })),
    total: data.courses.length,
  }
}

function riskBand(value) {
  return value >= 0.68 ? 'HIGH' : value >= 0.38 ? 'MEDIUM' : 'LOW'
}

function bounded(value) {
  return Math.min(0.99, Math.max(0.01, Number(value.toFixed(4))))
}

function currentTwin(courseId) {
  const base = structuredClone(data.snapshots[courseId].at(-1))
  const answers = state.answers[courseId] || []
  if (!answers.length) return base

  const impact = answers.reduce((sum, answer) => sum + (answer.correct ? 1 : -0.5), 0)
  const masteryDelta = impact * 0.012
  const probability = bounded(base.risk.probability - impact * 0.008)
  const lastAnswer = answers.at(-1)

  return {
    ...base,
    snapshotVersion: base.snapshotVersion + answers.length,
    capturedAt: lastAnswer.occurredAt,
    mastery: base.mastery.map((skill, index) => ({ ...skill, probability: bounded(skill.probability + masteryDelta * (index === 0 ? 1 : 0.55)) })),
    engagementScore: bounded(base.engagementScore + answers.length * 0.003),
    stabilityScore: bounded(base.stabilityScore + impact * 0.004),
    nextCorrectProbability: bounded(base.nextCorrectProbability + impact * 0.01),
    risk: { ...base.risk, probability, riskBand: riskBand(probability), predictedAt: lastAnswer.occurredAt },
  }
}

function profile() {
  const user = currentUser()
  return { userId: user.userId, username: user.username, displayName: user.displayName, officialId: 'SHOWCASE-ONLY', roles, organizations: [{ organizationId: 'org-college', code: 'CS', displayName: '计算机与软件学院', unitType: 'COLLEGE' }], email: 'showcase@example.invalid', phone: null, avatarKey: 'avatar-1', themeColor: 'teal' }
}

function plan(courseId) {
  const value = structuredClone(data.plans[courseId])
  const answers = state.answers[courseId] || []
  return { planId: value.planId, studentId: value.studentId, courseId, version: value.version + answers.length + (state.planGenerations[courseId] || 0), generatedAt: answers.at(-1)?.occurredAt || data.manifest.referenceTime, validUntil: value.validUntil, status: value.status, completionRate: 0, tasks: value.tasks.map((task, index) => ({ taskId: task.planTaskId, sequence: index + 1, taskType: task.taskType, questionId: task.questionId, skillId: task.skillId, targetMastery: 0.8, reasonCode: task.reasonCode, dueAt: task.dueAt, status: task.status })) }
}

function planLifecycle(courseId) {
  const value = structuredClone(data.plans[courseId])
  value.version += (state.answers[courseId] || []).length + (state.planGenerations[courseId] || 0)
  return value
}

function analytics(courseId, period) {
  const snapshots = data.snapshots[courseId]
  const latest = snapshots.at(-1)
  return { courseId, studentId: data.students[0].studentId, period, generatedAt: data.manifest.referenceTime, answerCount: 86, correctRate: 0.79, activeDays: 18, totalDurationMinutes: 524, currentMastery: 0.76, currentRisk: latest.risk.probability, currentEngagement: latest.engagementScore, snapshotTrend: snapshots, activityTrend: data.activityTrend, behaviorDistribution: [{ behaviorType: '练习作答', eventCount: 86, studentCount: 1, share: 0.45 }, { behaviorType: '课程浏览', eventCount: 71, studentCount: 1, share: 0.37 }, { behaviorType: '计划执行', eventCount: 34, studentCount: 1, share: 0.18 }], activityHeatmap: Array.from({ length: 21 }, (_, index) => ({ weekday: index % 7, hour: 9 + Math.floor(index / 7) * 5, eventCount: 1 + index % 6 })) }
}

function roster() {
  return { items: data.students.slice(0, 48).map((student) => ({ ...student, enrolled: true, enrollmentStatus: 'ACTIVE' })), total: 48, enrolledCount: 48 }
}

function assessmentList(courseId) {
  return data.outlines[courseId]?.assessments || []
}

function grades(courseId) {
  return { items: assessmentList(courseId).map((assessment, index) => ({ assessmentId: assessment.assessmentId, title: assessment.title, studentId: data.students[0].studentId, displayName: data.students[0].displayName, submitted: index < 2, score: index < 2 ? 84 + index * 4 : null, maxScore: 100, percentage: index < 2 ? 84 + index * 4 : null, submittedAt: index < 2 ? `2026-06-${12 + index * 7}T08:00:00.000Z` : null })), total: 3 }
}

function riskDetail(caseId) {
  const detail = structuredClone(data.riskDetails[caseId] || data.riskDetails['risk-case-1'])
  for (const action of detail.actionItems) action.feedback.push(...(state.feedback[action.actionItemId] || []))
  return detail
}

function studentActions() {
  return Object.keys(data.riskDetails).slice(0, 3).flatMap((caseId) => {
    const detail = riskDetail(caseId)
    return detail.actionItems.map((action) => ({ actionItemId: action.actionItemId, caseId, courseId: detail.riskCase.courseId, courseTitle: detail.riskCase.courseTitle, title: action.title, description: action.description, status: action.status, dueAt: action.dueAt, resultSummary: action.resultSummary, feedback: action.feedback }))
  })
}

function counselorPage(url) {
  const page = Number(url.searchParams.get('page') || 0)
  const size = Number(url.searchParams.get('size') || 20)
  const items = data.students.map((student) => ({ ...student, courseCount: 6, totalCredits: 20, averageScorePercentage: Math.round(student.performance * 100), averageMastery: student.performance, highRiskCourseCount: student.risk >= 0.68 ? 2 : student.risk >= 0.38 ? 1 : 0, lastActivityAt: '2026-07-01T07:30:00.000Z' }))
  return { items: items.slice(page * size, page * size + size), total: items.length, page, size }
}

function classComparison(url) {
  const selected = url.searchParams.getAll('classNames')
  const names = selected.length ? selected : data.classes.slice(0, 2)
  return { classes: names.map((className) => ({ className, studentCount: 24, activeCourseEnrollments: 144, averageScorePercentage: 82, averageMastery: 0.76, averageRiskProbability: 0.31, lowRiskCourseCount: 92, mediumRiskCourseCount: 42, highRiskCourseCount: 10, planCompletionRate: 0.68 })), period: url.searchParams.get('period') || '30D', riskTrend: names.flatMap((className, classIndex) => Array.from({ length: 6 }, (_, index) => ({ className, weekStart: `2026-05-${String(4 + index * 7).padStart(2, '0')}`, averageRiskProbability: Number((0.42 - index * 0.025 + classIndex * 0.018).toFixed(3)) }))) }
}

function adminUsers() {
  const base = [...data.teachers.map((item) => ({ userId: item.teacherId, username: item.username, displayName: item.displayName, role: 'TEACHER', roles: ['TEACHER'] })), ...data.students.slice(0, 12).map((item) => ({ userId: item.studentId, username: item.username, displayName: item.displayName, role: 'STUDENT', roles: ['STUDENT'] }))]
  return { items: base.map((item) => ({ ...item, enabled: true, mustChangePassword: false, originType: 'DEMO_SYNTHETIC', createdAt: '2026-02-20T08:00:00.000Z', updatedAt: '2026-02-20T08:00:00.000Z' })), total: 101 }
}

function assistantReply() {
  if (state.role === 'TEACHER') return '当前课程共有 **48 名学生**。建议优先查看薄弱知识点与近期活跃度下降的学生。'
  if (state.role === 'COUNSELOR') return '四个班级的整体风险趋势正在下降，当前有 **12 个演示风险工单**。'
  if (state.role === 'ADMIN') return '展示环境包含 **96 名合成学生、6 门课程**，采用规则推理与模板诊断。'
  return '近期掌握度持续上升，建议完成“复杂度分析”巩固练习。该结论仅来自确定性合成数据。'
}

function sendSse(response, events) {
  response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
  for (const event of events) response.write(`id: ${event.sequence}\nevent: ${event.type || event.eventType}\ndata: ${JSON.stringify(event)}\n\n`)
  response.end()
}

const server = createServer(async (request, response) => {
  const url = pathOf(request.url)
  const pathname = url.pathname.replace(/^\/api\/v1/, '') || '/'
  const method = request.method || 'GET'
  const body = await readBody(request)

  if (pathname === '/healthz' || pathname === '/actuator/health/readiness') return json(response, 200, { status: 'UP', profile: process.env.EDUTWIN_DEMO_PROFILE || 'showcase' })
  if (pathname === '/showcase/manifest') return json(response, 200, data.manifest)
  if (pathname === '/showcase/reset' && method === 'POST') {
    state.role = 'STUDENT'; state.answers = {}; state.planGenerations = {}; state.feedback = {}; state.conversations = []; state.messages = {}; state.counter = 1
    return json(response, 204)
  }
  if (pathname === '/auth/login' && method === 'POST') {
    const role = data.accounts[body.username]
    if (!role) return problem(response, 401, '请选择页面提供的演示角色。', 'SHOWCASE_ACCOUNT_REQUIRED')
    state.role = role
    return json(response, 200, authSession())
  }
  if (pathname === '/auth/me') return json(response, 200, currentUser())
  if (pathname === '/auth/role' && method === 'POST') {
    if (!roles.includes(body.role)) return problem(response, 400, '未知角色。', 'INVALID_ROLE')
    state.role = body.role
    return json(response, 200, authSession())
  }
  if (pathname === '/me/profile') return json(response, 200, { ...profile(), ...(method === 'PUT' ? body : {}) })
  if (pathname === '/courses') return json(response, 200, courseList())
  if (pathname === '/notifications/unread-count') return json(response, 200, { count: 2 })
  if (pathname === '/notifications') return json(response, 200, { items: [], page: 0, size: 20, total: 0 })
  if (pathname.startsWith('/notifications/') && method === 'PUT') return json(response, 204)

  let match = pathname.match(/^\/teacher\/courses\/([^/]+)\/dashboard$/)
  if (match) return json(response, 200, { ...data.dashboards[match[1]], period: url.searchParams.get('period') || '30D' })
  match = pathname.match(/^\/student\/courses\/([^/]+)\/analytics$/)
  if (match) return json(response, 200, analytics(match[1], url.searchParams.get('period') || '30D'))
  match = pathname.match(/^\/courses\/([^/]+)\/students\/[^/]+\/twin\/(current|history)$/)
  if (match) {
    const current = currentTwin(match[1])
    const answers = state.answers[match[1]] || []
    const history = answers.length ? [...data.snapshots[match[1]], current] : data.snapshots[match[1]]
    return json(response, 200, match[2] === 'current' ? current : { items: [...history].reverse(), total: history.length, nextBeforeVersion: null })
  }

  match = pathname.match(/^\/courses\/([^/]+)\/practice\/next$/)
  if (match) {
    const questions = data.questions.filter((item) => item.courseId === match[1])
    const question = structuredClone(questions[(state.answers[match[1]] || []).length % questions.length])
    delete question.correctChoiceId
    return json(response, 200, question)
  }
  match = pathname.match(/^\/courses\/([^/]+)\/answers$/)
  if (match && method === 'GET') return json(response, 200, { items: [...(state.answers[match[1]] || [])].reverse(), total: (state.answers[match[1]] || []).length, page: 0, size: 20 })
  if (match && method === 'POST') {
    const question = data.questions.find((item) => item.questionId === body.questionId)
    const selected = question.choices.find((item) => item.choiceId === body.selectedChoiceId)
    const correct = question.choices.find((item) => item.choiceId === question.correctChoiceId)
    state.answers[match[1]] ||= []
    state.answers[match[1]].push({ answerEventId: nextId('answer'), questionId: question.questionId, prompt: question.prompt, selectedChoice: selected, correctChoice: correct, correct: selected.choiceId === correct.choiceId, skills: data.skills.filter((item) => question.skillIds.includes(item.skillId)).map((item) => ({ skillId: item.skillId, name: item.name })), attemptNumber: 1, eventSequence: state.answers[match[1]].length + 1, responseTimeMs: 8400, occurredAt: body.occurredAt || now(), sourceType: 'ONLINE' })
    return json(response, 200, analysisJob(match[1], nextId('analysis-job')))
  }
  match = pathname.match(/^\/analysis\/jobs\/([^/]+)\/events$/)
  if (match) return sendSse(response, [{ sequence: 1, eventType: 'job.completed', occurredAt: now(), job: analysisJob('course-1', match[1]) }])
  match = pathname.match(/^\/analysis\/jobs\/([^/]+)$/)
  if (match) return json(response, 200, analysisJob('course-1', match[1]))

  match = pathname.match(/^\/courses\/([^/]+)\/students\/[^/]+\/learning-plans(?:\/current)?$/)
  if (match) {
    if (method === 'POST') state.planGenerations[match[1]] = (state.planGenerations[match[1]] || 0) + 1
    return json(response, 200, plan(match[1]))
  }
  match = pathname.match(/^\/courses\/([^/]+)\/students\/[^/]+\/learning-plans\/current\/lifecycle$/)
  if (match) return json(response, 200, planLifecycle(match[1]))
  match = pathname.match(/^\/courses\/([^/]+)\/students\/[^/]+\/diagnoses$/)
  if (match) return json(response, 200, { diagnosisId: nextId('diagnosis'), schemaVersion: '1.0', generatedAt: now(), summary: '当前学习节奏总体稳定，规则推理识别出一个需要持续巩固的知识点。', riskBand: 'LOW', strengths: ['近期练习完成度保持稳定。'], concerns: ['复杂度分析相关题目仍存在波动。'], recommendedActions: ['完成学习计划中的三道巩固练习。'], evidence: [{ rank: 1, featureName: 'recent_correct_rate', rawValue: 0.71, direction: 'DECREASES_RISK', contribution: -0.24, baseValue: 0.34, outputUnit: 'LOG_ODDS' }], learningPlan: plan(match[1]) })

  if (pathname === '/lms/teacher/courses') return json(response, 200, { items: data.courses.map((course) => ({ ...course, assignmentRole: 'OWNER' })), total: data.courses.length })
  match = pathname.match(/^\/lms\/teacher\/courses\/([^/]+)\/sections$/) || pathname.match(/^\/lms\/courses\/([^/]+)$/)
  if (match) return json(response, 200, data.outlines[match[1]])
  if (/^\/lms\/teacher\/courses\/[^/]+\/roster$/.test(pathname)) return json(response, 200, roster())
  if (/^\/lms\/teacher\/courses\/[^/]+\/knowledge-skills$/.test(pathname)) return json(response, 200, { items: data.skills, total: data.skills.length })
  match = pathname.match(/^\/lms\/(?:teacher\/)?courses\/([^/]+)\/assessments$/)
  if (match) return json(response, 200, { items: assessmentList(match[1]), total: assessmentList(match[1]).length })
  match = pathname.match(/^\/lms\/assessments\/([^/]+)$/)
  if (match) {
    const course = data.courses.find((item) => match[1].startsWith(item.courseId)) || data.courses[0]
    const summary = assessmentList(course.courseId).find((item) => item.assessmentId === match[1]) || assessmentList(course.courseId)[0]
    return json(response, 200, { ...summary, questions: data.questions.filter((item) => item.courseId === course.courseId).map((question, index) => ({ questionId: question.questionId, prompt: question.prompt, options: question.choices, points: 25, position: index + 1 })) })
  }
  match = pathname.match(/^\/lms\/assessments\/([^/]+)\/attempts$/)
  if (match) return json(response, 200, { items: [], total: 0, currentSubmissionId: null, currentScore: null })
  match = pathname.match(/^\/lms\/assessments\/([^/]+)\/attempt-requests$/)
  if (match) return json(response, 200, { items: [], total: 0 })
  match = pathname.match(/^\/lms\/assessments\/([^/]+)\/submissions$/)
  if (match && method === 'GET') return problem(response, 404, '当前演示考核尚未提交。', 'SUBMISSION_NOT_FOUND')
  if (match && method === 'POST') return json(response, 200, { submissionId: nextId('submission'), assessmentId: match[1], studentId: data.students[0].studentId, score: 100, maxScore: 100, percentage: 100, submittedAt: now(), answers: [] })
  match = pathname.match(/^\/lms\/courses\/([^/]+)\/grades$/)
  if (match) return json(response, 200, grades(match[1]))

  if (pathname === '/risk/cases') return json(response, 200, { items: data.riskCases, total: data.riskCases.length, page: 0, size: 20 })
  match = pathname.match(/^\/risk\/cases\/([^/]+)$/)
  if (match) return json(response, 200, riskDetail(match[1]))
  if (pathname === '/risk/me/actions') return json(response, 200, studentActions())
  match = pathname.match(/^\/risk\/me\/actions\/([^/]+)\/feedback$/)
  if (match && method === 'POST') {
    state.feedback[match[1]] ||= []
    state.feedback[match[1]].push({ feedbackId: nextId('feedback'), studentId: data.students[0].studentId, body: body.body, createdAt: now() })
    return json(response, 200, studentActions().find((item) => item.actionItemId === match[1]))
  }

  if (pathname === '/counselor/students') return json(response, 200, counselorPage(url))
  match = pathname.match(/^\/counselor\/students\/([^/]+)\/overview$/)
  if (match) {
    const student = data.students.find((item) => item.studentId === match[1]) || data.students[0]
    return json(response, 200, { student: counselorPage(new URL('http://showcase.local')).items.find((item) => item.studentId === student.studentId), courses: data.courses.map((course) => ({ courseId: course.courseId, code: course.code, title: course.title, termLabel: course.termLabel, credits: course.credits, instructors: [course.instructorName], enrollmentStatus: 'ACTIVE', completedLessons: 6, totalLessons: 9, submittedAssessments: 2, publishedAssessments: 3, scorePercentage: 82, averageMastery: student.performance, riskBand: riskBand(student.risk), riskProbability: student.risk, lastActivityAt: '2026-07-01T07:30:00.000Z' })) })
  }
  if (pathname === '/counselor/classes/compare') return json(response, 200, classComparison(url))

  if (pathname === '/admin/overview') return json(response, 200, { enabledUsers: 101, teachers: 3, students: 96, counselors: 1, enabledDatasets: 1, modelDeployments: 2, generatedAt: data.manifest.referenceTime })
  if (pathname === '/admin/users') return json(response, 200, adminUsers())
  if (pathname === '/admin/organizations') return json(response, 200, { items: data.organizations, total: 6 })
  if (pathname === '/admin/academic-terms') return json(response, 200, { items: [{ termId: 'term-2026-spring', code: '2025-2026-2', displayName: '2025-2026 学年第二学期', startsOn: '2026-02-23', endsOn: '2026-07-05', enabled: true }], total: 1 })
  if (pathname === '/admin/student-groups') return json(response, 200, { items: data.classes.map((className) => ({ scopeType: 'CLASS', college: '计算机与软件学院', major: className.startsWith('软件') ? '软件工程' : '计算机科学与技术', cohortYear: 2023, className, studentCount: 24 })), total: 4 })
  if (/^\/admin\/counselors\/[^/]+\/scopes$/.test(pathname)) return json(response, 200, { items: data.classes.map((className) => ({ scopeType: 'CLASS', college: '计算机与软件学院', major: className.startsWith('软件') ? '软件工程' : '计算机科学与技术', cohortYear: 2023, className })), total: 4 })
  if (pathname === '/admin/ai-configuration') return json(response, 200, { source: 'ENVIRONMENT', revision: 1, enabled: false, apiBaseUrl: '', model: 'template-diagnosis-v1', apiKeyConfigured: false, writeAvailable: false, lastTestStatus: 'SKIPPED_DISABLED', lastTestedAt: null, lastTestLatencyMs: null, updatedAt: data.manifest.referenceTime, updatedBy: null })
  if (pathname === '/admin/transparency') return json(response, 200, transparency())
  if (pathname === '/admin/datasets') return json(response, 200, { items: [{ versionId: 'showcase-synthetic-v1', sourceKey: 'EDUTWIN_SHOWCASE', sourceName: 'EduTwin 确定性合成数据', lifecycleStatus: 'ENABLED', rowCount: 96, manifestSha256: '42'.repeat(32), processedAt: data.manifest.referenceTime, referenced: true }], total: 1 })
  if (pathname === '/admin/model-deployments') return json(response, 200, { items: [{ taskName: 'RISK', activeVersionId: 'rule-risk-v1', rollbackVersionId: null, deployedAt: data.manifest.referenceTime, deployedBy: 'showcase-generator' }], total: 1 })
  if (pathname === '/admin/audit-events') return json(response, 200, { items: [], total: 0, page: 0, size: 20 })

  if (pathname === '/assistant/conversations' && method === 'GET') return json(response, 200, { items: state.conversations })
  if (pathname === '/assistant/conversations' && method === 'POST') {
    const conversation = { conversationId: nextId('conversation'), title: body.title || '新的分析会话', activeRole: state.role, createdAt: now(), updatedAt: now() }
    state.conversations.unshift(conversation); state.messages[conversation.conversationId] = []
    return json(response, 200, conversation)
  }
  match = pathname.match(/^\/assistant\/conversations\/([^/]+)\/messages$/)
  if (match && method === 'GET') return json(response, 200, { items: state.messages[match[1]] || [] })
  if (match && method === 'POST') {
    const userMessageId = nextId('message-user'); const assistantMessageId = nextId('message-assistant'); const content = assistantReply()
    state.messages[match[1]] ||= []
    state.messages[match[1]].push(assistantMessage(userMessageId, 'USER', body.content), assistantMessage(assistantMessageId, 'ASSISTANT', content))
    return json(response, 200, { userMessageId, assistantMessageId, status: 'PROCESSING', streamUrl: `/assistant/messages/${assistantMessageId}/events` })
  }
  match = pathname.match(/^\/assistant\/messages\/([^/]+)\/events$/)
  if (match) {
    const content = assistantReply(); const occurredAt = now()
    return sendSse(response, [{ sequence: 1, messageId: match[1], type: 'message.started', occurredAt, data: {} }, { sequence: 2, messageId: match[1], type: 'message.delta', occurredAt, data: { text: content } }, { sequence: 3, messageId: match[1], type: 'message.completed', occurredAt, data: { content, sources: [{ label: '确定性合成数据', queriedAt: occurredAt, deepLink: '/student/courses/course-1/twin' }], deepLinks: ['/student/courses/course-1/plan'] } }])
  }

  if (method !== 'GET') return problem(response, 403, '展示模式已禁用管理类破坏性操作。', 'SHOWCASE_READ_ONLY')
  return problem(response, 404, `展示 API 未覆盖接口 ${pathname}`, 'SHOWCASE_ROUTE_NOT_FOUND')
})

function analysisJob(courseId, jobId) {
  return { jobId, studentId: data.students[0].studentId, courseId, status: 'COMPLETED', stage: 'COMPLETED', lastEventSequence: 1, submittedAt: now(), startedAt: now(), completedAt: now(), failure: null, result: { twinUrl: `/courses/${courseId}/students/${data.students[0].studentId}/twin/current`, learningPlanUrl: `/courses/${courseId}/students/${data.students[0].studentId}/learning-plans/current`, diagnosisUrl: `/courses/${courseId}/students/${data.students[0].studentId}/diagnoses` } }
}

function assistantMessage(messageId, role, content) {
  return { messageId, role, status: 'COMPLETED', content, sources: role === 'ASSISTANT' ? [{ label: '确定性合成数据', queriedAt: now(), deepLink: '/student/courses/course-1/twin' }] : [], deepLinks: role === 'ASSISTANT' ? ['/student/courses/course-1/plan'] : [], errorCode: null, retryable: false, createdAt: now(), completedAt: now() }
}

function transparency() {
  return { provenance: { synthetic: true, matchingVersion: 'showcase-generator-v1', matchingSeed: 42, generatedAt: data.manifest.referenceTime, sources: [{ sourceId: 'EDUTWIN_SHOWCASE', datasetVersion: 'showcase-synthetic-v1', sourceSha256: '42'.repeat(32), manifestSha256: '24'.repeat(32), licenseName: 'Apache-2.0', licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0', sourceUrl: 'generated://showcase', rowCount: 96, processingRunId: 'showcase-seed-42', processingConfigSha256: '12'.repeat(32), splitSeed: 42 }] }, models: [{ model: { purpose: 'RISK', family: 'RULE', modelName: 'rule-risk', modelVersion: 'v1', artifactSha256: '31'.repeat(32), calibratorVersion: null }, manifestSha256: '41'.repeat(32), configSha256: '51'.repeat(32), dependencyLockSha256: '61'.repeat(32), selected: true, metrics: [{ name: 'deterministic_fixture', split: 'TEST', value: 1 }] }], generatedAt: data.manifest.referenceTime }
}

server.listen(port, '0.0.0.0', () => console.log(`EduTwin showcase API listening on ${port}`))
