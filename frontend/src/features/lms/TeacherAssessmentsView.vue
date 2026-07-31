<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Archive, BarChart3, CalendarClock, CircleX, ClipboardCheck, ListChecks, Plus, RefreshCw, Send, Trash2 } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { AssessmentList, AssessmentMutationRequest, AssessmentQuestionRequest, AssessmentSummary, GradeList, KnowledgeSkillSummary } from '@/shared/types/api'
import type { AttemptRequest, AttemptRequestList } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'
import { assessmentStatusLabel, averageSubmittedScore } from './businessState'

const { courseId } = useCourseContext()
const assessments = ref<AssessmentList | null>(null)
const grades = ref<GradeList | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const gradesOpen = ref(false)
const requestsOpen = ref(false)
const gradeTitle = ref('')
const requestTitle = ref('')
const activeAssessmentId = ref('')
const attemptRequests = ref<AttemptRequestList | null>(null)
const skills = ref<KnowledgeSkillSummary[]>([])
const submittedAverage = computed(() => grades.value ? averageSubmittedScore(grades.value.items) : null)

const form = reactive({
  title: '', description: '', assessmentType: 'QUIZ' as 'ASSIGNMENT' | 'QUIZ', dueAt: '',
  questions: [] as AssessmentQuestionRequest[],
})

function newQuestion(index: number): AssessmentQuestionRequest {
  return {
    prompt: '', points: 1, correctChoiceId: 'A', skillIds: [],
    options: [
      { choiceId: 'A', label: '' },
      { choiceId: 'B', label: '' },
      { choiceId: 'C', label: '' },
    ].map((option) => ({ ...option, choiceId: `${option.choiceId}${index || ''}` })),
  }
}

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = null
  try {
    const [assessmentList, skillList] = await Promise.all([
      api.managedAssessments(courseId.value),
      api.courseKnowledgeSkills(courseId.value),
    ])
    assessments.value = assessmentList
    skills.value = skillList.items
  } catch (reason) {
    error.value = userErrorMessage(reason, '考核列表读取失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { title: '', description: '', assessmentType: 'QUIZ', dueAt: '' })
  form.questions.splice(0, form.questions.length, newQuestion(0))
  dialogOpen.value = true
}

function addQuestion() {
  const question = newQuestion(form.questions.length + 1)
  question.correctChoiceId = question.options[0]?.choiceId ?? ''
  form.questions.push(question)
}

function removeQuestion(index: number) {
  if (form.questions.length <= 1) return ElMessage.warning('至少保留一道题')
  form.questions.splice(index, 1)
}

async function save() {
  if (!courseId.value || !form.title.trim()) return ElMessage.warning('考核名称不能为空')
  const invalid = form.questions.some((question) =>
    !question.prompt.trim() || !question.skillIds.length
      || question.options.some((option) => !option.label.trim()),
  )
  if (invalid) return ElMessage.warning('题干、知识点和所有选项均不能为空')
  const payload: AssessmentMutationRequest = {
    title: form.title,
    description: form.description,
    assessmentType: form.assessmentType,
    dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
    questions: form.questions.map((question) => ({
      ...question,
      options: question.options.map((option) => ({ ...option })),
    })),
  }
  saving.value = true
  try {
    await api.createAssessment(courseId.value, payload)
    dialogOpen.value = false
    await load()
    ElMessage.success('考核草稿已创建')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '考核保存失败'))
  } finally {
    saving.value = false
  }
}

async function publish(item: AssessmentSummary) {
  await transitionAssessment(item, 'PUBLISHED', '发布考核')
}

async function transitionAssessment(item: AssessmentSummary, targetStatus: string, title: string) {
  try {
    const prompt = await ElMessageBox.prompt('状态变更原因将写入考核历史。', title, {
      inputPlaceholder: '状态变更原因', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空',
    })
    await ElMessageBox.confirm(`确认将“${item.title}”变更为${assessmentStatusLabel(targetStatus)}？`, '确认考核状态', {
      type: targetStatus === 'CLOSED' || targetStatus === 'ARCHIVED' ? 'warning' : 'info',
      confirmButtonText: '确认变更', cancelButtonText: '取消',
    })
    await businessApi.changeAssessmentStatus(item.assessmentId, item.status, targetStatus, prompt.value.trim())
    await load()
    ElMessage.success('考核状态已更新')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '考核状态更新失败'))
  }
}

async function extend(item: AssessmentSummary) {
  try {
    const due = await ElMessageBox.prompt('填写新的截止时间，例如 2026-07-22 18:00。', '延长考核', {
      inputPlaceholder: 'YYYY-MM-DD HH:mm', inputValidator: (value) => !Number.isNaN(Date.parse(value)) || '截止时间格式无效',
    })
    const reason = await ElMessageBox.prompt('填写延期原因。', '延期说明', {
      inputPlaceholder: '延期原因', inputValidator: (value) => Boolean(value.trim()) || '延期原因不能为空',
    })
    await businessApi.extendAssessment(item.assessmentId, new Date(due.value).toISOString(), reason.value.trim())
    await load()
    ElMessage.success('考核截止时间已更新')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '考核延期失败'))
  }
}

async function cancelAssessment(item: AssessmentSummary) {
  try {
    const prompt = await ElMessageBox.prompt('取消后学生将无法继续提交，已有提交不计入成绩。', '取消考核', {
      inputPlaceholder: '取消原因', inputValidator: (value) => Boolean(value.trim()) || '取消原因不能为空',
    })
    await ElMessageBox.confirm(`确认取消“${item.title}”？`, '确认取消考核', {
      type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回',
    })
    await businessApi.cancelAssessment(item.assessmentId, prompt.value.trim())
    await load()
    ElMessage.success('考核已取消')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '考核取消失败'))
  }
}

async function openGrades(item: AssessmentSummary) {
  gradeTitle.value = item.title
  gradesOpen.value = true
  grades.value = null
  try {
    grades.value = await api.assessmentGrades(item.assessmentId)
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '成绩读取失败'))
  }
}

async function openRequests(item: AssessmentSummary) {
  activeAssessmentId.value = item.assessmentId
  requestTitle.value = item.title
  requestsOpen.value = true
  attemptRequests.value = null
  try {
    attemptRequests.value = await businessApi.teacherAttemptRequests(item.assessmentId)
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '补交申请读取失败'))
  }
}

async function decideRequest(item: AttemptRequest, decision: 'APPROVED' | 'REJECTED') {
  try {
    let personalDueAt: string | null = null
    if (decision === 'APPROVED') {
      const due = await ElMessageBox.prompt('填写本次补交的个人截止时间。', '批准补交申请', {
        inputPlaceholder: 'YYYY-MM-DD HH:mm', inputValidator: (value) => !Number.isNaN(Date.parse(value)) || '截止时间格式无效',
      })
      personalDueAt = new Date(due.value).toISOString()
    }
    const reason = await ElMessageBox.prompt('填写审批说明。', decision === 'APPROVED' ? '批准申请' : '拒绝申请', {
      inputPlaceholder: '审批说明', inputValidator: (value) => Boolean(value.trim()) || '审批说明不能为空',
    })
    await businessApi.decideAttemptRequest(item.requestId, decision, reason.value.trim(), personalDueAt)
    attemptRequests.value = await businessApi.teacherAttemptRequests(activeAssessmentId.value)
    ElMessage.success(decision === 'APPROVED' ? '补交申请已批准' : '补交申请已拒绝')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '补交申请审批失败'))
  }
}

watch(courseId, load)
onMounted(load)
</script>

<template>
  <PageHeader
    title="作业与测验"
    description="创建客观题考核、发布任务并查看学生提交与自动评分结果。"
  >
    <template #actions>
      <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" :disabled="!courseId" @click="openCreate">新建考核</el-button>
    </template>
  </PageHeader>

  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <section v-else class="lms-table-section">
    <el-table :data="assessments?.items ?? []" row-key="assessmentId">
      <el-table-column label="考核" min-width="280">
        <template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.title }}</strong><span>{{ row.description || '无说明' }}</span></div></template>
      </el-table-column>
      <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.assessmentType === 'QUIZ' ? '测验' : '作业' }}</template></el-table-column>
      <el-table-column prop="questionCount" label="题目" width="72" />
      <el-table-column prop="submissionCount" label="提交" width="72" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'CANCELLED' ? 'danger' : 'warning'">{{ assessmentStatusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="截止时间" min-width="150"><template #default="{ row }">{{ row.dueAt ? new Date(row.dueAt).toLocaleString() : '不限时' }}</template></el-table-column>
      <el-table-column label="操作" min-width="430" align="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" text type="primary" :icon="Send" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" text :icon="CalendarClock" @click="extend(row)">延期</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" text :icon="CircleX" @click="transitionAssessment(row, 'CLOSED', '提前截止')">截止</el-button>
          <el-button v-if="row.status === 'PUBLISHED' || row.status === 'CLOSED'" text type="danger" :icon="CircleX" @click="cancelAssessment(row)">取消</el-button>
          <el-button v-if="row.status !== 'ARCHIVED'" text type="danger" :icon="Archive" @click="transitionAssessment(row, 'ARCHIVED', '归档考核')">归档</el-button>
          <el-button text :icon="ListChecks" @click="openRequests(row)">补交申请</el-button>
          <el-button text :icon="BarChart3" @click="openGrades(row)">成绩</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!assessments?.items.length" class="lms-empty-state"><ClipboardCheck :size="24" /> 尚未创建作业或测验</div>
  </section>

  <el-dialog v-model="dialogOpen" title="新建客观题考核" width="min(820px, 96vw)" top="4vh">
    <el-form label-position="top">
      <div class="lms-form-grid lms-form-grid--two">
        <el-form-item label="考核名称"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.assessmentType"><el-option label="测验" value="QUIZ" /><el-option label="作业" value="ASSIGNMENT" /></el-select></el-form-item>
      </div>
      <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      <el-form-item label="截止时间"><el-date-picker v-model="form.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="可选" /></el-form-item>
      <div class="lms-question-editor">
        <article v-for="(question, index) in form.questions" :key="index" class="lms-question-editor__item">
          <header><strong>第 {{ index + 1 }} 题</strong><el-button text type="danger" :icon="Trash2" @click="removeQuestion(index)">删除</el-button></header>
          <el-form-item label="题干"><el-input v-model="question.prompt" /></el-form-item>
          <el-form-item label="关联知识点">
            <el-select v-model="question.skillIds" multiple filterable placeholder="选择一个或多个课程知识点">
              <el-option v-for="skill in skills" :key="skill.skillId" :label="skill.name" :value="skill.skillId" />
            </el-select>
          </el-form-item>
          <div class="lms-option-editor" v-for="option in question.options" :key="option.choiceId">
            <el-radio v-model="question.correctChoiceId" :value="option.choiceId">正确答案</el-radio>
            <el-input v-model="option.label" :placeholder="`选项 ${option.choiceId}`" />
          </div>
          <el-form-item label="分值"><el-input-number v-model="question.points" :min="0.5" :step="0.5" /></el-form-item>
        </article>
        <el-button class="lms-add-row" :icon="Plus" @click="addQuestion">添加题目</el-button>
      </div>
    </el-form>
    <template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
  </el-dialog>

  <el-drawer v-model="gradesOpen" :title="`${gradeTitle} · 成绩`" size="min(720px, 96vw)">
    <div v-if="!grades" class="lms-empty-inline">正在读取成绩</div>
    <template v-else>
      <div class="assessment-grade-summary">
        <span>已提交平均分</span>
        <strong>{{ submittedAverage == null ? '暂无成绩' : `${submittedAverage.toFixed(1)}%` }}</strong>
        <small>仅统计有有效成绩的学生，未提交不按零分计入。</small>
      </div>
    <el-table :data="grades.items" row-key="studentId">
      <el-table-column prop="displayName" label="学生" min-width="160" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.submitted ? 'success' : 'info'">{{ row.submitted ? '已提交' : '未提交' }}</el-tag></template></el-table-column>
      <el-table-column label="得分" width="100"><template #default="{ row }">{{ row.submitted ? `${row.score}/${row.maxScore}` : '—' }}</template></el-table-column>
      <el-table-column label="百分比" width="90"><template #default="{ row }">{{ row.percentage == null ? '—' : `${row.percentage}%` }}</template></el-table-column>
      <el-table-column label="尝试次数" width="90"><template #default="{ row }">{{ row.attemptCount ?? 0 }}</template></el-table-column>
      <el-table-column label="提交时间" min-width="160"><template #default="{ row }">{{ row.submittedAt ? new Date(row.submittedAt).toLocaleString() : '—' }}</template></el-table-column>
    </el-table>
    </template>
  </el-drawer>

  <el-drawer v-model="requestsOpen" :title="`${requestTitle} · 补交申请`" size="min(760px, 96vw)">
    <div v-if="!attemptRequests" class="lms-empty-inline">正在读取补交申请</div>
    <el-table v-else :data="attemptRequests.items" row-key="requestId">
      <el-table-column label="申请类型" width="100"><template #default="{ row }">{{ row.requestType === 'MAKEUP' ? '补交' : row.requestType === 'RETAKE' ? '再次作答' : '成绩复核' }}</template></el-table-column>
      <el-table-column prop="reason" label="申请原因" min-width="180" />
      <el-table-column label="申请时间" min-width="160"><template #default="{ row }">{{ new Date(row.requestedAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">{{ row.status === 'PENDING' ? '待审批' : row.status === 'APPROVED' ? '已批准' : '已拒绝' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="150" align="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="primary" @click="decideRequest(row, 'APPROVED')">批准</el-button>
            <el-button link type="danger" @click="decideRequest(row, 'REJECTED')">拒绝</el-button>
          </template>
          <span v-else>{{ row.consumed ? '已使用' : '已处理' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="attemptRequests && !attemptRequests.items.length" class="lms-empty-state">暂无补交申请</div>
  </el-drawer>
</template>

<style scoped>
.assessment-grade-summary {
  display: grid;
  gap: 4px;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-soft);
}

.assessment-grade-summary span,
.assessment-grade-summary small { color: var(--muted); }
.assessment-grade-summary strong { color: var(--ink-strong); font-size: 22px; }
</style>
