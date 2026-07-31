<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { History, RefreshCw } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { ApiError } from '@/api/client/http'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import AssignmentAnswerView from './assignments/AssignmentAnswerView.vue'
import AssignmentReviewView from './assignments/AssignmentReviewView.vue'
import AssignmentTaskList, { type TaskFilter, type TaskState } from './assignments/AssignmentTaskList.vue'
import type { AssessmentDetail, AssessmentList, AssessmentResult, AssessmentSummary } from '@/shared/types/api'
import type { AssessmentAttemptList, AttemptRequestList, LearnerAssessmentFields } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'

const { courseId } = useCourseContext()
const assessments = ref<AssessmentList | null>(null)
const active = ref<AssessmentDetail | null>(null)
const result = ref<AssessmentResult | null>(null)
const answers = ref<Record<string, string>>({})
const filter = ref<TaskFilter>('ALL')
const mode = ref<'LIST' | 'ANSWER' | 'REVIEW'>('LIST')
const loading = ref(false)
const submitting = ref(false)
const error = ref<string | null>(null)
const hasDraft = computed(() => mode.value === 'ANSWER' && Object.keys(answers.value).length > 0)
const attemptsOpen = ref(false)
const attemptTitle = ref('')
const attempts = ref<AssessmentAttemptList | null>(null)
const attemptRequests = ref<AttemptRequestList | null>(null)
const requestDialog = ref(false)
const requestAssessment = ref<(AssessmentSummary & LearnerAssessmentFields) | null>(null)
const requestForm = reactive({ requestType: 'MAKEUP' as 'MAKEUP' | 'RETAKE' | 'APPEAL', reason: '', requestedDueAt: '' })
const requesting = ref(false)

const learnerAssessments = computed(() => (assessments.value?.items ?? []) as Array<AssessmentSummary & LearnerAssessmentFields>)

function taskState(item: AssessmentSummary & LearnerAssessmentFields): TaskState {
  if (item.learnerState === 'SUBMITTED') return 'COMPLETED'
  if (item.learnerState === 'CLOSED_UNSUBMITTED' || item.learnerState === 'CANCELLED') return 'CLOSED'
  return 'PENDING'
}

async function load() {
  if (!courseId.value) return
  loading.value = true; error.value = null
  try { assessments.value = await api.publishedAssessments(courseId.value) }
  catch (reason) { error.value = userErrorMessage(reason, '课程任务读取失败') }
  finally { loading.value = false }
}

async function confirmDiscard() {
  if (!hasDraft.value) return true
  try {
    await ElMessageBox.confirm('当前答案尚未提交，返回后将丢失。', '放弃未提交答案', { type: 'warning', confirmButtonText: '放弃并返回', cancelButtonText: '继续答题' })
    return true
  } catch { return false }
}

async function back() {
  if (!await confirmDiscard()) return
  active.value = null; result.value = null; answers.value = {}; mode.value = 'LIST'
}

async function open(item: AssessmentSummary & LearnerAssessmentFields) {
  if (!await confirmDiscard()) return
  const state = taskState(item)
  if (state === 'CLOSED') {
    ElMessage.warning('该任务已截止，且没有可回顾的历史提交。')
    return
  }
  loading.value = true
  try {
    if (state === 'COMPLETED') {
      const [detail, submission] = await Promise.all([api.assessment(item.assessmentId), api.assessmentSubmission(item.assessmentId)])
      active.value = detail; result.value = submission; answers.value = {}; mode.value = 'REVIEW'
    } else {
      active.value = await api.assessment(item.assessmentId)
      result.value = null; answers.value = {}; mode.value = 'ANSWER'
    }
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '任务读取失败'))
  } finally { loading.value = false }
}

async function openAttempts(item: AssessmentSummary & LearnerAssessmentFields) {
  attemptsOpen.value = true
  attemptTitle.value = item.title
  attempts.value = null
  attemptRequests.value = null
  try {
    const [history, requests] = await Promise.all([
      businessApi.assessmentAttempts(item.assessmentId),
      businessApi.studentAttemptRequests(item.assessmentId),
    ])
    attempts.value = history
    attemptRequests.value = requests
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '作答记录读取失败'))
  }
}

function openAttemptRequest(item: AssessmentSummary & LearnerAssessmentFields) {
  requestAssessment.value = item
  Object.assign(requestForm, { requestType: 'MAKEUP', reason: '', requestedDueAt: '' })
  requestDialog.value = true
}

async function submitAttemptRequest() {
  if (!requestAssessment.value || !requestForm.reason.trim()) {
    ElMessage.warning('申请原因不能为空')
    return
  }
  requesting.value = true
  try {
    await businessApi.requestAttempt(
      requestAssessment.value.assessmentId,
      requestForm.requestType,
      requestForm.reason.trim(),
      requestForm.requestedDueAt ? new Date(requestForm.requestedDueAt).toISOString() : null,
    )
    requestDialog.value = false
    ElMessage.success('补交申请已提交')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '补交申请提交失败'))
  } finally {
    requesting.value = false
  }
}

async function submit() {
  if (submitting.value || !active.value) return
  submitting.value = true
  try {
    await ElMessageBox.confirm('提交后答案不可修改，确认提交当前答案？', '确认提交', { type: 'warning', confirmButtonText: '确认提交' })
    result.value = await api.submitAssessment(
      active.value.assessmentId,
      active.value.questions.map((question) => ({ questionId: question.questionId, selectedChoiceId: answers.value[question.questionId] ?? '' })),
      crypto.randomUUID(),
    )
    answers.value = {}; mode.value = 'REVIEW'
    await load()
    ElMessage.success('任务已提交并完成评分')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    if (reason instanceof ApiError && reason.problem?.code === 'ASSESSMENT_CLOSED') {
      await load()
      active.value = null; answers.value = {}; mode.value = 'LIST'
      ElMessage.warning('任务已截止，答案未提交。')
    } else ElMessage.error(userErrorMessage(reason, '考核提交失败'))
  } finally { submitting.value = false }
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!hasDraft.value) return
  event.preventDefault(); event.returnValue = ''
}

onBeforeRouteLeave(async () => await confirmDiscard())
watch(courseId, async () => { active.value = null; result.value = null; answers.value = {}; mode.value = 'LIST'; await load() })
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <PageHeader title="课程任务" description="完成课程作业与测验，提交后可查看逐题回顾。">
    <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
  </PageHeader>
  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <AssignmentTaskList
    v-else-if="mode === 'LIST' && assessments"
    v-model:filter="filter"
    :items="learnerAssessments"
    @open="open"
    @request-attempt="openAttemptRequest"
    @view-attempts="openAttempts"
  />
  <AssignmentAnswerView v-else-if="mode === 'ANSWER' && active" :detail="active" :answers="answers" :submitting="submitting" @answer="(questionId, choiceId) => answers[questionId] = choiceId" @back="back" @submit="submit" />
  <AssignmentReviewView v-else-if="mode === 'REVIEW' && active && result" :detail="active" :result="result" @back="back" />

  <el-drawer v-model="attemptsOpen" :title="`${attemptTitle} · 作答记录`" size="min(720px, 96vw)">
    <div v-if="!attempts" class="lms-empty-inline">正在读取作答记录</div>
    <template v-else>
      <div class="attempt-summary">
        <History :size="20" />
        <div><span>计入成绩的最高分</span><strong>{{ attempts.currentScore == null ? '暂无成绩' : `${attempts.currentScore} 分` }}</strong></div>
      </div>
      <el-table :data="attempts.items" row-key="submissionId">
        <el-table-column prop="attemptNumber" label="次数" width="72" />
        <el-table-column label="类型" width="100"><template #default="{ row }">{{ row.attemptType === 'STANDARD' ? '正常作答' : '补交作答' }}</template></el-table-column>
        <el-table-column label="得分" width="110"><template #default="{ row }">{{ row.score }}/{{ row.maxScore }}</template></el-table-column>
        <el-table-column label="计入成绩" width="96"><template #default="{ row }"><el-tag :type="row.validForGrade ? 'success' : 'info'">{{ row.validForGrade ? '是' : '否' }}</el-tag></template></el-table-column>
        <el-table-column label="提交时间" min-width="170"><template #default="{ row }">{{ new Date(row.submittedAt).toLocaleString() }}</template></el-table-column>
      </el-table>
      <h3 class="attempt-section-title">补交申请</h3>
      <el-table :data="attemptRequests?.items ?? []" row-key="requestId">
        <el-table-column label="类型" width="100"><template #default="{ row }">{{ row.requestType === 'MAKEUP' ? '补交' : row.requestType === 'RETAKE' ? '再次作答' : '成绩复核' }}</template></el-table-column>
        <el-table-column prop="reason" label="申请原因" min-width="180" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'PENDING' ? '待审批' : row.status === 'APPROVED' ? '已批准' : '已拒绝' }}</template></el-table-column>
        <el-table-column label="个人截止" min-width="160"><template #default="{ row }">{{ row.personalDueAt ? new Date(row.personalDueAt).toLocaleString() : '—' }}</template></el-table-column>
      </el-table>
    </template>
  </el-drawer>

  <el-dialog v-model="requestDialog" title="申请补交" width="min(560px, 94vw)">
    <el-form label-position="top">
      <el-form-item label="申请类型">
        <el-segmented v-model="requestForm.requestType" :options="[{ label: '补交', value: 'MAKEUP' }, { label: '再次作答', value: 'RETAKE' }, { label: '成绩复核', value: 'APPEAL' }]" />
      </el-form-item>
      <el-form-item label="申请原因"><el-input v-model="requestForm.reason" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="期望截止时间（选填）"><el-date-picker v-model="requestForm.requestedDueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="requestDialog = false">取消</el-button><el-button type="primary" :loading="requesting" @click="submitAttemptRequest">提交申请</el-button></template>
  </el-dialog>
</template>

<style scoped>
.attempt-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--accent-dark);
  background: var(--surface-tint);
}
.attempt-summary div { display: grid; gap: 3px; }
.attempt-summary span { color: var(--muted); font-size: 12px; }
.attempt-summary strong { color: var(--ink-strong); font-size: 18px; }
.attempt-section-title { margin: 22px 0 10px; font-size: 14px; }
</style>
