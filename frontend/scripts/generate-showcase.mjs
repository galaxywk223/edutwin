import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const output = resolve(root, 'public', 'showcase-data.json')
const manifestOutput = resolve(root, 'public', 'showcase-manifest.json')
const referenceTime = new Date('2026-07-01T08:00:00.000Z')
const seed = 42

function mulberry32(value) {
  return () => {
    value |= 0
    value = value + 0x6D2B79F5 | 0
    let next = Math.imul(value ^ value >>> 15, 1 | value)
    next = next + Math.imul(next ^ next >>> 7, 61 | next) ^ next
    return ((next ^ next >>> 14) >>> 0) / 4294967296
  }
}

const random = mulberry32(seed)
const round = (value, digits = 3) => Number(value.toFixed(digits))
const isoDays = (offset, hour = 8) => {
  const value = new Date(referenceTime)
  value.setUTCDate(value.getUTCDate() + offset)
  value.setUTCHours(hour, 0, 0, 0)
  return value.toISOString()
}
const hash = (value) => createHash('sha256').update(value).digest('hex')

const classes = ['软件工程2301班', '软件工程2302班', '计算机科学2301班', '计算机科学2302班']
const teacherNames = ['林知远', '周明澈', '陈若川']
const courseSeeds = [
  ['SE2301', '数据结构与算法', 4, 0],
  ['SE2302', '数据库系统原理', 3, 1],
  ['SE2303', '软件工程实践', 3, 2],
  ['CS2304', '机器学习基础', 3, 0],
  ['CS2305', '计算机网络', 3, 1],
  ['CS2306', '操作系统', 4, 2],
]
const skillNames = ['复杂度分析', '线性结构', '树与图', '关系建模', '查询优化', '事务并发']

const students = Array.from({ length: 96 }, (_, index) => {
  const number = index + 1
  const className = classes[index % classes.length]
  const performance = round(0.56 + random() * 0.4)
  const engagement = round(0.48 + random() * 0.48)
  const risk = round(Math.max(0.04, Math.min(0.92, 1.12 - performance * 0.72 - engagement * 0.62 + random() * 0.12)))
  return {
    studentId: `student-${String(number).padStart(3, '0')}`,
    username: `2023${String(number).padStart(4, '0')}`,
    displayName: `同学${String(number).padStart(2, '0')}`,
    studentNumber: `2023${String(number).padStart(4, '0')}`,
    college: '计算机与软件学院',
    major: index % 2 === 0 ? '软件工程' : '计算机科学与技术',
    cohortYear: 2023,
    className,
    performance,
    engagement,
    risk,
  }
})

const courses = courseSeeds.map(([code, title, credits, teacherIndex], index) => ({
  courseId: `course-${index + 1}`,
  code,
  title,
  name: title,
  termLabel: '2025-2026 学年第二学期',
  credits,
  description: `${title}课程围绕核心概念、实践任务与过程性评价组织教学。`,
  startsOn: '2026-02-23',
  status: 'PUBLISHED',
  college: '计算机与软件学院',
  department: '软件工程系',
  instructorName: teacherNames[teacherIndex],
  teacherId: `teacher-${teacherIndex + 1}`,
  sectionCount: 3,
  assessmentCount: 3,
  enrolledStudentCount: 48,
}))

const skills = skillNames.map((name, index) => ({
  skillId: `skill-${index + 1}`,
  skillCode: `KS-${String(index + 1).padStart(2, '0')}`,
  name,
  contentOrigin: 'CURATED_SYNTHETIC',
}))

const prompts = [
  '下列哪种数据结构最适合实现广度优先搜索？',
  '关系数据库中用于保证引用完整性的约束是？',
  '并发执行中避免丢失更新的关键机制是？',
  '评估分类模型时，类别不均衡场景更适合关注哪项指标？',
]
const questions = courses.flatMap((course, courseIndex) => skills.slice(0, 4).map((skill, index) => ({
  questionId: `${course.courseId}-question-${index + 1}`,
  courseId: course.courseId,
  prompt: prompts[(courseIndex + index) % prompts.length],
  questionType: 'SINGLE_CHOICE',
  choices: [
    { choiceId: 'A', label: '队列' },
    { choiceId: 'B', label: '栈' },
    { choiceId: 'C', label: '散列表' },
    { choiceId: 'D', label: '优先队列' },
  ],
  correctChoiceId: 'A',
  skillIds: [skill.skillId],
})))

const outlines = Object.fromEntries(courses.map((course, courseIndex) => {
  const sections = Array.from({ length: 3 }, (_, sectionIndex) => ({
    sectionId: `${course.courseId}-section-${sectionIndex + 1}`,
    title: ['基础概念与方法', '核心模型与实现', '综合实践与复盘'][sectionIndex],
    description: '由概念理解、示例推演和课堂实践构成。',
    position: sectionIndex + 1,
    status: 'PUBLISHED',
    lessons: Array.from({ length: 3 }, (_, lessonIndex) => ({
      lessonId: `${course.courseId}-lesson-${sectionIndex + 1}-${lessonIndex + 1}`,
      title: `第 ${sectionIndex * 3 + lessonIndex + 1} 讲：${skills[(courseIndex + sectionIndex + lessonIndex) % skills.length].name}`,
      summary: '结合典型问题说明概念、方法与工程边界。',
      body: '本节内容包含概念梳理、例题分析、实践任务与课后复盘。',
      resourceUrl: null,
      position: lessonIndex + 1,
      status: 'PUBLISHED',
      completed: sectionIndex * 3 + lessonIndex < 5,
    })),
  }))
  const assessments = Array.from({ length: 3 }, (_, assessmentIndex) => ({
    assessmentId: `${course.courseId}-assessment-${assessmentIndex + 1}`,
    courseId: course.courseId,
    title: ['阶段练习一', '单元测验', '综合实践任务'][assessmentIndex],
    description: '用于检验当前阶段的知识掌握和迁移应用能力。',
    assessmentType: assessmentIndex === 2 ? 'ASSIGNMENT' : 'QUIZ',
    status: 'PUBLISHED',
    dueAt: isoDays(5 + assessmentIndex * 7),
    questionCount: 4,
    submissionCount: 38 + assessmentIndex * 3,
    submitted: assessmentIndex < 2,
    score: assessmentIndex < 2 ? 84 + assessmentIndex * 4 : null,
    maxScore: 100,
  }))
  return [course.courseId, {
    course: { ...course, assignmentRole: 'LEARNER' },
    sections,
    assessments,
    completedLessonCount: 5,
    totalLessonCount: 9,
  }]
}))

const snapshots = Object.fromEntries(courses.map((course, courseIndex) => {
  const student = students[0]
  const items = Array.from({ length: 8 }, (_, index) => {
    const progress = index / 7
    const risk = round(Math.max(0.16, student.risk + 0.12 - progress * 0.2))
    return {
      studentId: student.studentId,
      courseId: course.courseId,
      snapshotVersion: index + 1,
      capturedAt: isoDays(-49 + index * 7),
      mastery: skills.slice(0, 5).map((skill, skillIndex) => ({
        skillId: skill.skillId,
        skillName: skill.name,
        probability: round(0.48 + progress * 0.28 + skillIndex * 0.025 - courseIndex * 0.006),
        estimator: 'RULE_FALLBACK',
      })),
      engagementScore: round(0.58 + progress * 0.22),
      stabilityScore: round(0.6 + progress * 0.18),
      nextCorrectProbability: round(0.55 + progress * 0.25),
      risk: {
        probability: risk,
        calibrated: false,
        riskBand: risk >= 0.68 ? 'HIGH' : risk >= 0.38 ? 'MEDIUM' : 'LOW',
        mediumThreshold: 0.38,
        highThreshold: 0.68,
        baseValue: 0.34,
        predictedAt: isoDays(-49 + index * 7),
      },
      planCompletionRate: round(progress * 0.75),
    }
  })
  return [course.courseId, items]
}))

const activityTrend = Array.from({ length: 14 }, (_, index) => ({
  date: isoDays(-13 + index).slice(0, 10),
  answerCount: 42 + Math.round(random() * 55),
  activeStudentCount: 26 + Math.round(random() * 20),
  correctRate: round(0.64 + random() * 0.24),
}))
const riskBand = (probability) => probability >= 0.68 ? 'HIGH' : probability >= 0.38 ? 'MEDIUM' : 'LOW'

const dashboards = Object.fromEntries(courses.map((course) => {
  const summaries = students.slice(0, 48).map((student, index) => ({
    studentId: student.studentId,
    displayName: student.displayName,
    riskProbability: student.risk,
    riskBand: riskBand(student.risk),
    lastActivityAt: isoDays(-(index % 8)),
    averageMastery: student.performance,
    engagementScore: student.engagement,
    answerCount: 18 + index % 23,
    behaviorProfile: student.engagement > 0.75 ? '稳定投入' : student.risk > 0.6 ? '需要关注' : '节奏波动',
  }))
  return [course.courseId, {
    courseId: course.courseId,
    generatedAt: referenceTime.toISOString(),
    studentCount: summaries.length,
    highRiskCount: summaries.filter((item) => item.riskBand === 'HIGH').length,
    mediumRiskCount: summaries.filter((item) => item.riskBand === 'MEDIUM').length,
    lowRiskCount: summaries.filter((item) => item.riskBand === 'LOW').length,
    averageRiskProbability: round(summaries.reduce((sum, item) => sum + item.riskProbability, 0) / summaries.length),
    period: '30D',
    activeStudentCount: 44,
    answerCount: 1268,
    averageCorrectRate: 0.78,
    weakSkills: skills.slice(0, 4).map((skill, index) => ({ skillId: skill.skillId, skillName: skill.name, averageMastery: round(0.56 + index * 0.045), affectedStudentCount: 19 - index * 2 })),
    activityTrend,
    students: summaries,
    behaviorDistribution: [
      { behaviorType: '练习作答', eventCount: 1268, studentCount: 47, share: 0.46 },
      { behaviorType: '课程浏览', eventCount: 984, studentCount: 48, share: 0.36 },
      { behaviorType: '计划执行', eventCount: 492, studentCount: 39, share: 0.18 },
    ],
    activityHeatmap: Array.from({ length: 28 }, (_, index) => ({ weekday: index % 7, hour: 8 + Math.floor(index / 7) * 3, eventCount: 12 + Math.round(random() * 46) })),
    studentScatter: summaries.map((item) => ({ studentId: item.studentId, displayName: item.displayName, averageMastery: item.averageMastery, riskProbability: item.riskProbability, engagementScore: item.engagementScore, behaviorProfile: item.behaviorProfile })),
  }]
}))

const plans = Object.fromEntries(courses.map((course, courseIndex) => [course.courseId, {
  planId: `${course.courseId}-plan-1`, courseId: course.courseId, studentId: students[0].studentId,
  version: 1, status: 'ACTIVE', validUntil: isoDays(14), expiredAt: null,
  tasks: skills.slice(0, 3).map((skill, index) => ({
    planTaskId: `${course.courseId}-task-${index + 1}`,
    planId: `${course.courseId}-plan-1`,
    questionId: questions.find((item) => item.courseId === course.courseId && item.skillIds.includes(skill.skillId))?.questionId ?? questions[courseIndex * 4].questionId,
    questionTitle: `${skill.name}巩固练习`, skillId: skill.skillId, skillName: skill.name,
    taskType: index === 2 ? 'REVIEW' : 'PRACTICE', reasonCode: index === 0 ? 'LOW_MASTERY' : 'SPACED_REVIEW',
    targetCount: 3, completedCount: index === 0 ? 1 : 0, status: index === 0 ? 'IN_PROGRESS' : 'PENDING',
    dueAt: isoDays(3 + index * 2), startedAt: index === 0 ? isoDays(-1) : null,
    completedAt: null, skippedAt: null, skipReason: null,
    practicePath: `/student/courses/${course.courseId}/practice`,
  })),
}]))

const riskCases = students.slice(0, 12).map((student, index) => ({
  caseId: `risk-case-${index + 1}`, courseId: courses[index % courses.length].courseId,
  studentId: student.studentId, studentName: student.displayName, courseTitle: courses[index % courses.length].title,
  triggerType: index % 2 ? 'ENGAGEMENT_DROP' : 'RISK_THRESHOLD', riskBand: index < 4 ? 'HIGH' : 'MEDIUM',
  title: index % 2 ? '近期学习活跃度下降' : '连续知识点掌握偏低',
  summary: '系统基于合成学习事件生成的演示风险提示，不代表真实学生判断。',
  status: index < 2 ? 'IN_PROGRESS' : 'OPEN', priority: index < 2 ? 'URGENT' : 'HIGH',
  assignedTo: 'counselor-1', assigneeName: '许清和', version: 1,
  createdAt: isoDays(-8 + index % 5), updatedAt: isoDays(-2 + index % 3), resolvedAt: null, closedAt: null,
}))

const riskDetails = Object.fromEntries(riskCases.map((riskCase, index) => [riskCase.caseId, {
  riskCase,
  assignments: [{ assignmentId: `${riskCase.caseId}-assignment-1`, fromAssignee: null, toAssignee: 'counselor-1', changedBy: 'system', reason: '演示规则自动分派', createdAt: riskCase.createdAt }],
  internalNotes: index < 2 ? [{ noteId: `${riskCase.caseId}-note-1`, authorId: 'counselor-1', authorName: '许清和', body: '已核对近期学习节奏，建议先完成短周期复习任务。', createdAt: isoDays(-1) }] : [],
  actionItems: [{
    actionItemId: `${riskCase.caseId}-action-1`, caseId: riskCase.caseId, studentId: riskCase.studentId,
    title: '完成薄弱知识点复习', description: '本周完成 3 道针对性练习并提交学习反馈。',
    status: index === 0 ? 'IN_PROGRESS' : 'PENDING', dueAt: isoDays(5), resultSummary: null,
    createdAt: riskCase.createdAt, startedAt: index === 0 ? isoDays(-1) : null, completedAt: null, feedback: [],
  }],
}]))

const organizations = [{
  organizationId: 'org-university', code: 'EDU', displayName: '示范大学', unitType: 'UNIVERSITY', parentId: null, enabled: true,
  children: [{
    organizationId: 'org-college', code: 'CS', displayName: '计算机与软件学院', unitType: 'COLLEGE', parentId: 'org-university', enabled: true,
    children: classes.map((displayName, index) => ({ organizationId: `org-class-${index + 1}`, code: `CS23${index + 1}`, displayName, unitType: 'CLASS', parentId: 'org-college', enabled: true, children: [] })),
  }],
}]

const manifest = {
  schemaVersion: 1,
  profile: 'showcase',
  seed,
  referenceTime: referenceTime.toISOString(),
  synthetic: true,
  inferenceMode: 'RULE_FALLBACK_AND_TEMPLATE_DIAGNOSIS',
  entityCounts: { students: 96, classes: 4, courses: 6, teachers: 3, counselors: 1, administrators: 1 },
  restrictedSourceRows: 0,
  externalKeysRequired: false,
}

const data = {
  manifest,
  accounts: { 'demo.student': 'STUDENT', 'demo.teacher': 'TEACHER', 'demo.counselor': 'COUNSELOR', 'demo.admin': 'ADMIN' },
  students,
  teachers: teacherNames.map((displayName, index) => ({ teacherId: `teacher-${index + 1}`, username: `teacher${index + 1}`, displayName })),
  counselor: { userId: 'counselor-1', username: 'counselor', displayName: '许清和' },
  administrator: { userId: 'admin-1', username: 'admin', displayName: '平台管理员' },
  classes, courses, skills, questions, outlines, snapshots, dashboards, plans, riskCases, riskDetails, organizations, activityTrend,
}

await mkdir(dirname(output), { recursive: true })
const serialized = `${JSON.stringify(data, null, 2)}\n`
const dataSha256 = hash(serialized)
await writeFile(output, serialized, 'utf8')
await writeFile(manifestOutput, `${JSON.stringify({ ...manifest, dataSha256 }, null, 2)}\n`, 'utf8')

console.log(`Generated ${output}`)
console.log(`SHA-256 ${dataSha256}`)
