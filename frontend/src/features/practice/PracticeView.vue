<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, CircleDot, RefreshCw, Send, Timer, TriangleAlert } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { streamAnalysisJob } from '@/api/client/sse'
import { useCourseContext } from '@/app/composables/useCourseContext'
import { useSessionStore } from '@/app/stores/session'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import PracticeHistoryPanel from '@/features/practice/PracticeHistoryPanel.vue'
import type { AnalysisJob, LearningPlan, PracticeQuestion, SseJobEvent, TwinState } from '@/shared/types/api'
import { analysisStatusLabel, problemMessage, userErrorMessage } from '@/shared/utils/displayText'
import { percent } from '@/shared/utils/format'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const { courseId } = useCourseContext()
const question = ref<PracticeQuestion | null>(null)
const selectedChoice = ref('')
const twinBefore = ref<TwinState | null>(null)
const twinAfter = ref<TwinState | null>(null)
const planAfter = ref<LearningPlan | null>(null)
const currentJob = ref<AnalysisJob | null>(null)
const events = ref<SseJobEvent[]>([])
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const submissionKey = ref<string | null>(null)
const activeView = ref<'practice' | 'history'>('practice')
const historyTotal = ref<number | null>(null)
const historyRefreshKey = ref(0)
const pendingPayload = ref<{
  questionId: string
  selectedChoiceId: string
  occurredAt: string
} | null>(null)
let abortController: AbortController | null = null

const studentId = computed(() => session.user?.userId ?? '')
const planTaskId = computed(() => typeof route.query.taskId === 'string' ? route.query.taskId : '')
const terminal = computed(() => currentJob.value?.status === 'COMPLETED' || currentJob.value?.status === 'FAILED')
const completed = computed(() => currentJob.value?.status === 'COMPLETED')
const deltas = computed(() => {
  if (!twinBefore.value || !twinAfter.value) return null
  const average = (state: TwinState) =>
    state.mastery.reduce((sum, item) => sum + item.probability, 0) / state.mastery.length
  return {
    mastery: average(twinAfter.value) - average(twinBefore.value),
    nextCorrect: twinAfter.value.nextCorrectProbability - twinBefore.value.nextCorrectProbability,
    risk: twinAfter.value.risk.probability - twinBefore.value.risk.probability,
    planVersion: planAfter.value?.version ?? null,
    snapshotVersion: twinAfter.value.snapshotVersion,
  }
})

const stages = [
  { key: 'job.queued', label: '答题事务已提交' },
  { key: 'job.processing', label: '学习状态分析中' },
  { key: 'predictions.computed', label: '学习状态已计算' },
  { key: 'twin.snapshot-created', label: '学习状态已更新' },
  { key: 'learning-plan.created', label: '学习计划已更新' },
  { key: 'job.completed', label: '视图刷新完成' },
]

function stageReached(key: string) {
  return events.value.some((event) => event.eventType === key) || (key === 'job.queued' && Boolean(currentJob.value))
}

async function loadQuestion(ignorePlanTask = false) {
  if (!courseId.value || !studentId.value) return
  if (ignorePlanTask && planTaskId.value) await router.replace({ query: {} })
  loading.value = true
  error.value = ''
  selectedChoice.value = ''
  submissionKey.value = null
  pendingPayload.value = null
  currentJob.value = null
  events.value = []
  twinAfter.value = null
  planAfter.value = null
  try {
    const requestedTaskId = ignorePlanTask ? '' : planTaskId.value
    const questionPromise = requestedTaskId
      ? businessApi.practicePlanTask(courseId.value, requestedTaskId).then((task) => ({
          questionId: task.questionId,
          courseId: task.courseId,
          prompt: task.prompt,
          questionType: 'SINGLE_CHOICE' as const,
          choices: JSON.parse(task.optionsJson) as Array<{ choiceId: string; label: string }>,
          skillIds: [task.skillId],
        }))
      : api.nextPractice(courseId.value)
    ;[question.value, twinBefore.value] = await Promise.all([
      questionPromise,
      api.twin(courseId.value, studentId.value),
    ])
  } catch (cause) {
    error.value = userErrorMessage(cause, '练习题读取失败。')
  } finally {
    loading.value = false
  }
}

async function waitForTerminal(job: AnalysisJob) {
  abortController?.abort()
  abortController = new AbortController()
  try {
    await streamAnalysisJob(job.jobId, {
      signal: abortController.signal,
      lastEventId: events.value.at(-1)?.sequence,
      onEvent(event) {
        if (!events.value.some((item) => item.sequence === event.sequence)) events.value.push(event)
        currentJob.value = event.job
      },
    })
  } catch (cause) {
    if (abortController.signal.aborted) throw cause
    currentJob.value = await api.job(job.jobId)
    if (!terminal.value) {
      await new Promise((resolve) => window.setTimeout(resolve, 350))
      return waitForTerminal(currentJob.value)
    }
  }
  if (!terminal.value) currentJob.value = await api.job(job.jobId)
}

async function submit() {
  if (!courseId.value || !question.value || !selectedChoice.value) return
  submitting.value = true
  error.value = ''
  const payload = pendingPayload.value ?? {
    questionId: question.value.questionId,
    selectedChoiceId: selectedChoice.value,
    occurredAt: new Date().toISOString(),
  }
  pendingPayload.value = payload
  submissionKey.value ??= crypto.randomUUID()
  try {
    currentJob.value = await api.submitAnswer(courseId.value, payload, submissionKey.value)
    await waitForTerminal(currentJob.value)
    if (currentJob.value?.status === 'FAILED') {
      throw new Error(problemMessage(
        currentJob.value.failure?.errorCode,
        currentJob.value.failure?.message,
      ))
    }
    ;[twinAfter.value, planAfter.value] = await Promise.all([
      api.twin(courseId.value, studentId.value),
      api.currentPlan(courseId.value, studentId.value),
    ])
    historyRefreshKey.value += 1
  } catch (cause) {
    if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
      error.value = userErrorMessage(cause, '答题分析失败。')
    }
  } finally {
    submitting.value = false
  }
}

watch(selectedChoice, () => {
  if (!currentJob.value) {
    submissionKey.value = null
    pendingPayload.value = null
  }
})
onMounted(loadQuestion)
onBeforeUnmount(() => abortController?.abort())
</script>

<template>
  <div class="practice-page">
    <PageHeader
      title="针对性练习"
      description="答案提交后持续更新掌握度、风险评估和学习计划。"
    >
      <template #actions>
        <el-button
          v-if="activeView === 'practice'"
          :icon="RefreshCw"
          :disabled="submitting"
          @click="loadQuestion(true)"
        >
          {{ planTaskId ? '随机练习' : '换一题' }}
        </el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeView" class="practice-tabs">
      <el-tab-pane label="继续练习" name="practice">
        <PageFeedback :loading="loading" :error="!question ? error : ''" @retry="loadQuestion" />

        <template v-if="question && !loading">
          <div class="practice-layout">
        <PanelCard class="question-panel" padding="default">
          <div class="question-meta">
            <span class="question-badge">单项选择</span>
            <span>{{ planTaskId ? '学习计划任务' : '自适应推荐' }}</span>
          </div>
          <h2>{{ question.prompt }}</h2>
          <div class="choice-list" role="radiogroup" aria-label="答案选项">
            <label
              v-for="(choice, index) in question.choices"
              :key="choice.choiceId"
              :class="{ 'choice-row--selected': selectedChoice === choice.choiceId }"
            >
              <input
                v-model="selectedChoice"
                type="radio"
                :value="choice.choiceId"
                :disabled="submitting || completed"
              />
              <span class="choice-row__index">{{ String.fromCharCode(65 + index) }}</span>
              <span>{{ choice.label }}</span>
              <Check v-if="selectedChoice === choice.choiceId" :size="17" aria-hidden="true" />
            </label>
          </div>
          <div class="question-actions">
            <span>{{ question.skillIds.length }} 个关联知识点</span>
            <el-button
              type="primary"
              :icon="Send"
              :loading="submitting"
              :disabled="!selectedChoice || completed"
              @click="submit"
            >
              {{ currentJob && !terminal ? '分析处理中' : '提交答案' }}
            </el-button>
          </div>
          <div v-if="error" class="practice-error" role="alert">
            <TriangleAlert :size="18" />
            <span>{{ error }}</span>
            <el-button v-if="currentJob && !completed" size="small" @click="submit">幂等重试</el-button>
          </div>
        </PanelCard>

        <PanelCard
          title="处理进度"
          :subtitle="analysisStatusLabel(currentJob?.status)"
          class="job-panel"
        >
          <ol class="job-stages">
            <li
              v-for="stage in stages"
              :key="stage.key"
              :class="{ 'job-stages__done': stageReached(stage.key) }"
            >
              <span>
                <Check v-if="stageReached(stage.key)" :size="13" />
                <CircleDot v-else :size="13" />
              </span>
              <div>
                <strong>{{ stage.label }}</strong>
              </div>
            </li>
          </ol>
          <div v-if="currentJob" class="job-reference">
            <Timer :size="15" />
            <code>{{ currentJob.jobId }}</code>
          </div>
        </PanelCard>
      </div>

      <PanelCard
        v-if="completed && deltas"
        class="result-deltas"
        title="本次行为引起的状态变化"
        :subtitle="`画像版本 ${twinBefore?.snapshotVersion} → 版本 ${deltas.snapshotVersion}`"
      >
        <div class="delta-grid">
          <div>
            <span>平均掌握度</span>
            <strong :class="deltas.mastery >= 0 ? 'delta-up' : 'delta-down'">
              {{ deltas.mastery >= 0 ? '+' : '' }}{{ percent(deltas.mastery, 2) }}
            </strong>
          </div>
          <div>
            <span>下一题概率</span>
            <strong :class="deltas.nextCorrect >= 0 ? 'delta-up' : 'delta-down'">
              {{ deltas.nextCorrect >= 0 ? '+' : '' }}{{ percent(deltas.nextCorrect, 2) }}
            </strong>
          </div>
          <div>
            <span>风险概率</span>
            <strong :class="deltas.risk <= 0 ? 'delta-up' : 'delta-down'">
              {{ deltas.risk >= 0 ? '+' : '' }}{{ percent(deltas.risk, 2) }}
            </strong>
          </div>
          <div>
            <span>学习计划</span>
            <strong>版本 {{ deltas.planVersion }}</strong>
          </div>
        </div>
        <template #footer>
          <div class="result-actions">
            <el-button @click="loadQuestion">继续下一题</el-button>
            <el-button type="primary" @click="$router.push(courseId ? `/student/courses/${courseId}/twin` : '/student/courses')">查看最新画像</el-button>
          </div>
        </template>
          </PanelCard>
        </template>
      </el-tab-pane>

      <el-tab-pane
        :label="historyTotal == null ? '答题记录' : `答题记录 (${historyTotal})`"
        name="history"
        lazy
      >
        <PracticeHistoryPanel
          v-if="courseId"
          :course-id="courseId"
          :refresh-key="historyRefreshKey"
          @total-change="historyTotal = $event"
        />
      </el-tab-pane>
    </el-tabs>

  </div>
</template>

<style scoped>
.practice-tabs :deep(.el-tabs__header) {
  margin: 0 0 14px;
}

.practice-tabs :deep(.el-tabs__item) {
  min-width: 96px;
  font-weight: 600;
}

.practice-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(290px, 0.7fr);
  gap: 14px;
}

.question-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}

.question-badge {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  color: var(--accent-dark);
  background: var(--accent-soft);
  font-size: 11px;
  font-weight: 650;
}

.question-meta code {
  color: var(--accent-dark);
}

.question-panel h2 {
  max-width: 760px;
  margin: 20px 0 24px;
  font-size: 20px;
  line-height: 1.55;
  font-weight: 620;
  color: var(--ink-strong);
}

.choice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.choice-list label {
  min-height: 54px;
  display: grid;
  grid-template-columns: 26px 30px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 9px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  transition:
    border 140ms ease,
    background 140ms ease,
    box-shadow 140ms ease;
}

.choice-list label:hover {
  border-color: var(--border-strong);
  background: var(--surface-soft);
}

.choice-list input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
}

.choice-row--selected {
  border-color: var(--accent) !important;
  color: var(--accent-dark);
  background: var(--surface-tint) !important;
  box-shadow: 0 0 0 3px rgb(13 148 136 / 10%);
}

.choice-row__index {
  color: var(--muted);
  font-weight: 650;
}

.question-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.question-actions > span {
  color: var(--muted);
  font-size: 12px;
}

.practice-error {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 16px;
  padding: 11px 13px;
  border-radius: 10px;
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 12px;
}

.practice-error span {
  flex: 1;
}

.job-stages {
  margin: 0;
  padding: 0;
  list-style: none;
}

.job-stages li {
  min-height: 54px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 9px;
  position: relative;
  color: var(--subtle);
}

.job-stages li::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 28px;
  bottom: -4px;
  width: 1px;
  background: var(--border);
}

.job-stages li:last-child::before {
  display: none;
}

.job-stages li > span {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  z-index: 1;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: #fff;
}

.job-stages li div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-top: 4px;
}

.job-stages strong {
  font-size: 12px;
  font-weight: 600;
}

.job-stages small {
  font-family: "Cascadia Code", Consolas, monospace;
  font-size: 9px;
}

.job-stages__done {
  color: var(--accent-dark) !important;
}

.job-stages__done > span {
  color: #fff;
  border-color: var(--accent);
  background: var(--accent);
}

.job-reference {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
  color: var(--muted);
}

.job-reference code {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 10px;
}

.result-deltas {
  margin-top: 14px;
}

.delta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.delta-grid > div {
  min-height: 92px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface-soft);
}

.delta-grid span {
  color: var(--muted);
  font-size: 11px;
}

.delta-grid strong {
  font-size: 22px;
}

.delta-up {
  color: var(--success);
}

.delta-down {
  color: var(--danger);
}

.result-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 900px) {
  .practice-layout {
    grid-template-columns: 1fr;
  }

  .delta-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .question-panel h2 {
    font-size: 17px;
  }

  .choice-list label {
    grid-template-columns: 20px 24px minmax(0, 1fr) 18px;
    padding: 8px 10px;
  }

  .delta-grid {
    grid-template-columns: 1fr;
  }

  .result-actions {
    flex-wrap: wrap;
  }
}
</style>
