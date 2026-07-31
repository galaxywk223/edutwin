import type {
  AnalysisJobStatus,
  Diagnosis,
  LearningPlan,
  ProblemDetail,
  RiskBand,
  TwinState,
} from '@/shared/types/api'
import { percent } from '@/shared/utils/format'

const riskBandLabels: Record<RiskBand, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险',
}

const analysisStatusLabels: Record<AnalysisJobStatus, string> = {
  QUEUED: '排队中',
  PROCESSING: '分析中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

const planStatusLabels: Record<LearningPlan['status'], string> = {
  ACTIVE: '生效中',
  SUPERSEDED: '已替代',
  COMPLETED: '已完成',
}

const taskStatusLabels: Record<LearningPlan['tasks'][number]['status'], string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
}

const reasonLabels: Record<string, string> = {
  LOW_MASTERY: '低掌握度',
  HIGH_RISK_CONTRIBUTION: '高风险贡献',
  STABILITY_RECOVERY: '稳定性恢复',
  SPACED_REVIEW: '间隔复习',
}

const auditActionLabels: Record<string, string> = {
  USER_CREATED: '创建用户',
  USER_UPDATED: '更新用户',
  PASSWORD_RESET: '重置密码',
  USERS_BULK_IMPORTED: '批量导入用户',
  COUNSELOR_SCOPES_REPLACED: '更新辅导员范围',
  DATASET_STATUS_CHANGED: '更新数据版本状态',
  MODEL_DEPLOYMENTS_SWAPPED: '切换模型部署',
  AI_CONFIGURATION_ACTIVATED: '更新大模型配置',
  AI_CONFIGURATION_RESTORED: '恢复环境大模型配置',
  ACTIVATE_AI_CONFIGURATION: '更新大模型配置',
  RESTORE_AI_ENVIRONMENT: '恢复环境大模型配置',
}

const auditTargetLabels: Record<string, string> = {
  USER: '用户',
  USER_IMPORT: '用户导入',
  DATASET_VERSION: '数据版本',
  MODEL_DEPLOYMENT: '模型部署',
  AI_CONFIGURATION: '大模型配置',
  ADMIN: '系统管理',
}

const modelPurposeLabels: Record<string, string> = {
  MASTERY: '知识掌握度',
  NEXT_CORRECT: '下一题正确率',
  RISK: '风险预测',
  EXPLANATION: '风险解释',
  DIAGNOSIS: '学习诊断',
  PLAN_RULES: '学习计划规则',
}

const riskFeatureLabels: Record<string, string> = {
  vle_total_clicks: '学习平台交互总量',
  vle_interaction_count: '学习平台交互次数',
  vle_active_days: '活跃学习天数',
  vle_active_weeks: '活跃学习周数',
  vle_resource_count: '学习资源使用数',
  assessment_count: '考核次数',
  assessment_scored_count: '已评分考核次数',
  assessment_mean_score: '考核平均成绩',
}

const importFieldLabels: Record<string, string> = {
  username: '账号',
  display_name: '姓名',
  role: '角色',
  enabled: '是否启用',
  student_number: '学号',
  staff_number: '工号',
  college: '学院',
  department: '部门',
  major: '专业',
  cohort_year: '年级',
  class_name: '班级',
  academic_title: '职称',
  course_code: '课程编号',
  membership_type: '课程成员类型',
  membership_status: '课程成员状态',
}

const importErrorLabels: Record<string, string> = {
  USER_IMPORT_REQUIRED: '缺少必填内容',
  USER_IMPORT_BOOLEAN_INVALID: '是否启用的填写格式错误',
  USER_IMPORT_ROLE_INVALID: '用户角色无效',
  USER_IMPORT_YEAR_INVALID: '年级格式错误',
  USER_IMPORT_DUPLICATE_USERNAME: '导入文件中账号重复',
  USER_IMPORT_ROLE_CONFLICT: '用户角色与现有账号冲突',
  USER_IMPORT_SYNTHETIC_CONFLICT: '演示账号不能通过导入修改',
  USER_IMPORT_STUDENT_NUMBER_CONFLICT: '学号已被其他账号使用',
  USER_IMPORT_STAFF_NUMBER_CONFLICT: '工号已被其他账号使用',
  USER_IMPORT_USER_NOT_FOUND: '课程成员账号不存在',
  USER_IMPORT_COURSE_NOT_FOUND: '课程编号不存在',
  USER_IMPORT_MEMBERSHIP_TYPE_INVALID: '课程成员类型无效',
  USER_IMPORT_MEMBERSHIP_STATUS_INVALID: '课程成员状态无效',
  USER_IMPORT_MEMBERSHIP_ROLE_INVALID: '该角色不能加入课程',
  USER_IMPORT_OWNER_CONFLICT: '课程已存在其他负责人',
  USER_IMPORT_HEADERS_INVALID: '表头与模板不一致',
  USER_IMPORT_FILE_EMPTY: '导入文件为空',
  USER_IMPORT_SHEET_MISSING: '缺少必需工作表',
  USER_IMPORT_CSV_ENCODING_INVALID: 'CSV 文件编码不正确',
  USER_IMPORT_CSV_INVALID: 'CSV 文件内容无效',
  USER_IMPORT_XLSX_INVALID: 'Excel 文件内容无效',
  USER_IMPORT_READ_FAILED: '导入文件读取失败',
  USER_IMPORT_FILES_INVALID: '导入文件组合不正确',
  USER_IMPORT_CSV_NAMES_INVALID: 'CSV 文件名不符合要求',
  USER_IMPORT_TEMPLATE_INVALID: '模板类型无效',
  USER_IMPORT_DIGEST_MISMATCH: '文件已发生变化，请重新预检',
}

const problemMessages: Record<string, string> = {
  INVALID_CREDENTIALS: '账号或密码错误。',
  AUTHENTICATION_REQUIRED: '登录状态已失效，请重新登录。',
  ACCESS_DENIED: '当前账号没有执行该操作的权限。',
  COURSE_ACCESS_DENIED: '当前账号没有访问该课程的权限。',
  VALIDATION_FAILED: '提交内容存在无效字段，请检查后重试。',
  MALFORMED_JSON: '提交内容格式错误。',
  DATA_CONFLICT: '该操作与现有数据冲突。',
  PASSWORD_TOO_SHORT: '新密码长度不足。',
  ADMIN_PASSWORD_TOO_SHORT: '密码长度不足。',
  ASSESSMENT_CLOSED: '该任务已截止。',
  ASSESSMENT_ALREADY_SUBMITTED: '该任务已经提交。',
  ASSESSMENT_SUBMISSION_NOT_FOUND: '未找到该任务的提交记录。',
  ASSESSMENT_NOT_FOUND: '未找到该考核。',
  ASSESSMENT_NOT_PUBLISHED: '该考核尚未发布。',
  ATTEMPT_LIMIT_REACHED: '已达到允许的作答次数。',
  COURSE_NOT_FOUND: '未找到该课程。',
  STUDENT_NOT_FOUND: '未找到该学生。',
  QUESTION_NOT_FOUND: '未找到该题目。',
  PRACTICE_QUESTION_NOT_FOUND: '当前没有可用的练习题。',
  ANALYSIS_JOB_NOT_FOUND: '未找到该分析任务。',
  TWIN_SNAPSHOT_NOT_FOUND: '尚未生成学情画像。',
  PLANNING_ARTIFACT_NOT_FOUND: '尚未生成学习计划或诊断。',
  TRANSPARENCY_CATALOG_NOT_READY: '模型与数据目录尚未准备完成。',
  DEEPSEEK_TIMEOUT: '学习诊断生成超时，已切换为规则诊断。',
  DEEPSEEK_UNAVAILABLE: '智能诊断暂时不可用，已切换为规则诊断。',
  DEEPSEEK_CALL_FAILED: '智能诊断生成失败，已切换为规则诊断。',
  AI_API_KEY_REQUIRED: '启用大模型前必须配置 API Key。',
  AI_API_KEY_INVALID: 'API Key 格式无效。',
  AI_BASE_URL_INVALID: 'API 基础地址格式无效。',
  AI_BASE_URL_INSECURE: 'API 基础地址必须使用 HTTPS。',
  AI_MODEL_INVALID: '模型名称格式无效。',
  AI_CONFIGURATION_STALE: '配置已被其他管理员更新，请刷新后重试。',
  AI_CONFIGURATION_ENCRYPTION_UNAVAILABLE: '服务器尚未配置大模型密钥加密能力。',
  AI_CONFIGURATION_TEST_FAILED: 'API 地址、Key、模型或工具调用能力验证失败。',
  AI_CONFIGURATION_TEST_TIMEOUT: '大模型连接验证超时。',
  AI_TOOL_CALL_UNSUPPORTED: '该模型未完成系统要求的工具调用。',
}

const statusMessages: Record<number, string> = {
  400: '提交内容有误，请检查后重试。',
  401: '登录状态已失效，请重新登录。',
  403: '当前账号没有执行该操作的权限。',
  404: '未找到请求的数据。',
  409: '当前操作与现有数据状态冲突。',
  422: '提交内容无法处理，请检查后重试。',
  429: '操作过于频繁，请稍后重试。',
  500: '服务暂时不可用，请稍后重试。',
  502: '上游服务暂时不可用，请稍后重试。',
  503: '服务正在恢复，请稍后重试。',
  504: '请求处理超时，请稍后重试。',
}

export const metricColumns = [
  { key: 'AUC', label: '区分能力（AUC）' },
  { key: 'LOG_LOSS', label: '对数损失' },
  { key: 'ECE_15', label: '校准误差' },
  { key: 'CPU_P95_MS', label: 'CPU 延迟' },
  { key: 'PR_AUC', label: '查准率面积' },
  { key: 'BRIER_SCORE', label: '布里尔分数' },
] as const

export function containsChinese(value: string | null | undefined) {
  return Boolean(value && /[\u3400-\u9fff]/.test(value))
}

export function riskBandLabel(value: RiskBand) {
  return riskBandLabels[value] ?? '未知风险'
}

export function analysisStatusLabel(value: AnalysisJobStatus | null | undefined) {
  return value ? analysisStatusLabels[value] ?? '未知状态' : '等待提交'
}

export function planStatusLabel(value: LearningPlan['status']) {
  return planStatusLabels[value] ?? '未知状态'
}

export function taskStatusLabel(value: LearningPlan['tasks'][number]['status']) {
  return taskStatusLabels[value] ?? '未知状态'
}

export function planReasonLabel(value: string) {
  return reasonLabels[value] ?? '其他学习需要'
}

export function datasetStatusLabel(value: string) {
  return value === 'ENABLED' ? '已启用' : value === 'DEPRECATED' ? '已弃用' : value === 'RETIRED' ? '已退役' : '未知状态'
}

export function auditActionLabel(value: string) {
  return auditActionLabels[value] ?? '其他管理操作'
}

export function auditTargetLabel(value: string) {
  return auditTargetLabels[value] ?? '其他对象'
}

export function auditOutcomeLabel(value: string) {
  return value === 'SUCCEEDED' ? '成功' : value === 'FAILED' ? '失败' : '未知结果'
}

export function modelPurposeLabel(value: string) {
  return modelPurposeLabels[value] ?? '其他模型任务'
}

export function riskFeatureLabel(value: string) {
  return riskFeatureLabels[value] ?? '其他学习行为指标'
}

export function importFieldLabel(value: string) {
  return importFieldLabels[value] ?? (value ? '其他字段' : '整行')
}

export function importSheetLabel(value: string) {
  if (!value) return '未指定'
  if (value === 'users') return '用户'
  if (value === 'course_memberships') return '课程关系'
  return '其他工作表'
}

export function importErrorLabel(code: string) {
  return importErrorLabels[code] ?? '导入内容不符合要求'
}

export function problemMessage(code: string | null | undefined, detail?: string | null, status?: number) {
  if (code && problemMessages[code]) return problemMessages[code]
  if (code?.startsWith('USER_IMPORT_')) return importErrorLabel(code)
  if (code?.startsWith('ASSESSMENT_')) return '考核内容或当前状态不符合操作要求。'
  if (code?.startsWith('COURSE_')) return '课程内容或当前状态不符合操作要求。'
  if (code?.startsWith('ANALYSIS_')) return '学习分析暂时无法完成，请稍后重试。'
  if (code?.startsWith('ADMIN_')) return '用户管理内容或当前状态不符合操作要求。'
  if (containsChinese(detail)) return detail as string
  return (status && statusMessages[status]) || '请求处理失败，请稍后重试。'
}

export function apiProblemMessage(problem: ProblemDetail | null, status: number) {
  return problemMessage(problem?.code, problem?.detail, status)
}

export function userErrorMessage(cause: unknown, fallback: string) {
  const message = cause instanceof Error ? cause.message : null
  return containsChinese(message) ? message as string : fallback
}

export function diagnosisDisplay(diagnosis: Diagnosis, twin: TwinState | null) {
  const chineseStrengths = diagnosis.strengths.filter(containsChinese)
  const chineseConcerns = diagnosis.concerns.filter(containsChinese)
  const chineseActions = diagnosis.recommendedActions.filter(containsChinese)
  const weakestSkill = [...(twin?.mastery ?? [])].sort((a, b) => a.probability - b.probability)[0]
  const topEvidence = diagnosis.evidence[0]

  const summary = containsChinese(diagnosis.summary)
    ? diagnosis.summary
    : twin
      ? `当前风险等级为${riskBandLabel(twin.risk.riskBand)}，风险概率为${percent(twin.risk.probability)}。`
      : `当前风险等级为${riskBandLabel(diagnosis.riskBand)}。`

  return {
    summary,
    strengths: chineseStrengths.length
      ? chineseStrengths
      : ['当前学情画像已依据现有学习记录生成。'],
    concerns: chineseConcerns.length
      ? chineseConcerns
      : [topEvidence
          ? `当前影响最大的模型指标为“${riskFeatureLabel(topEvidence.featureName)}”，主要${topEvidence.direction === 'INCREASES_RISK' ? '增加风险' : '降低风险'}。`
          : '当前暂无可用的模型影响因素。'],
    recommendedActions: chineseActions.length
      ? chineseActions
      : [weakestSkill
          ? `优先完成“${weakestSkill.skillName}”相关的学习计划任务。`
          : '按当前学习计划完成针对性练习与复习任务。'],
  }
}
