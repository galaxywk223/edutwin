<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight, CalendarClock, CirclePlay, ListChecks, RefreshCw, SkipForward, Timer } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { useCourseContext } from '@/app/composables/useCourseContext'
import { useSessionStore } from '@/app/stores/session'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { PlanLifecycle, PlanTaskProjection } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'
import { planTaskStatusLabel } from '@/features/lms/businessState'

const session = useSessionStore()
const router = useRouter()
const { courseId } = useCourseContext()
const plan = ref<PlanLifecycle | null>(null)
const loading = ref(false)
const refreshing = ref(false)
const actionTaskId = ref('')
const error = ref('')

const completedCount = computed(() => plan.value?.tasks.filter((task) => task.status === 'COMPLETED').length ?? 0)
const skippedCount = computed(() => plan.value?.tasks.filter((task) => task.status === 'SKIPPED').length ?? 0)
const completionRate = computed(() => {
  const total = plan.value?.tasks.length ?? 0
  return total ? Math.round(completedCount.value / total * 100) : 0
})
const nextDue = computed(() => {
  const tasks = plan.value?.tasks.filter((task) => ['PENDING', 'IN_PROGRESS'].includes(task.status)) ?? []
  return [...tasks].sort((a, b) => Date.parse(a.dueAt) - Date.parse(b.dueAt))[0]?.dueAt ?? null
})

function planStatusLabel(status: PlanLifecycle['status']) {
  return ({ ACTIVE: '进行中', COMPLETED: '已完成', SUPERSEDED: '已有新版本', EXPIRED: '已过期' } as const)[status]
}

function statusType(status: PlanTaskProjection['status']) {
  return status === 'COMPLETED' ? 'success' : status === 'SKIPPED' ? 'info' : status === 'IN_PROGRESS' ? 'warning' : ''
}

async function load() {
  if (!courseId.value || !session.user) return
  loading.value = true
  error.value = ''
  try {
    plan.value = await businessApi.currentPlanLifecycle(courseId.value, session.user.userId)
  } catch (cause) {
    error.value = userErrorMessage(cause, '学习计划读取失败。')
  } finally {
    loading.value = false
  }
}

async function regenerate() {
  if (!courseId.value || !session.user) return
  refreshing.value = true
  try {
    await api.generatePlan(courseId.value, session.user.userId, 'MANUAL_REFRESH')
    await load()
    ElMessage.success('学习计划新版本已生成')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '计划新版本生成失败。'))
  } finally {
    refreshing.value = false
  }
}

async function startTask(task: PlanTaskProjection) {
  if (!plan.value || !courseId.value || !session.user) return
  actionTaskId.value = task.planTaskId
  try {
    plan.value = await businessApi.startPlanTask(courseId.value, session.user.userId, task.planTaskId, plan.value.planId)
    ElMessage.success('任务已开始')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '任务开始失败。'))
  } finally {
    actionTaskId.value = ''
  }
}

async function skipTask(task: PlanTaskProjection) {
  if (!plan.value || !courseId.value || !session.user) return
  try {
    const prompt = await ElMessageBox.prompt('跳过原因会保留在计划记录中。', '跳过学习任务', {
      inputPlaceholder: '填写跳过原因', inputValidator: (value) => Boolean(value.trim()) || '跳过原因不能为空',
    })
    actionTaskId.value = task.planTaskId
    plan.value = await businessApi.skipPlanTask(
      courseId.value, session.user.userId, task.planTaskId, plan.value.planId, prompt.value.trim(),
    )
    ElMessage.success('任务已跳过')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '任务跳过失败。'))
  } finally {
    actionTaskId.value = ''
  }
}

async function openPractice(task: PlanTaskProjection) {
  if (task.status === 'PENDING') await startTask(task)
  if (courseId.value) {
    await router.push({ path: `/student/courses/${courseId.value}/practice`, query: { taskId: task.planTaskId } })
  }
}

watch(courseId, load)
onMounted(load)
</script>

<template>
  <div class="plan-page">
    <PageHeader title="学习计划" description="按当前学习状态执行任务，进度和有效期由服务端统一维护。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="ListChecks" :loading="refreshing" :disabled="!plan" @click="regenerate">生成新版本</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />
    <template v-if="plan && !loading">
      <div v-if="plan.status === 'EXPIRED'" class="plan-notice plan-notice--warning">
        当前计划已于 {{ plan.expiredAt ? dateTime(plan.expiredAt) : dateTime(plan.validUntil) }} 过期，任务只读保留。
      </div>
      <div v-else-if="plan.status === 'SUPERSEDED'" class="plan-notice">当前为历史版本，已由新计划替代。</div>

      <section class="stat-grid">
        <StatCard label="计划状态" :value="planStatusLabel(plan.status)" :caption="`版本 ${plan.version}`" :icon="ListChecks" />
        <StatCard label="完成进度" :value="`${completionRate}%`" :caption="`${completedCount} / ${plan.tasks.length} 项`" :icon="CirclePlay" tone="success" />
        <StatCard label="已跳过" :value="skippedCount" caption="保留跳过原因" :icon="SkipForward" tone="warning" />
        <StatCard label="下一截止" :value="nextDue ? dateTime(nextDue).slice(0, 10) : '无待办'" :caption="nextDue ? dateTime(nextDue) : '当前无待完成任务'" :icon="Timer" />
      </section>

      <PanelCard class="plan-tasks-card" title="任务清单" :subtitle="`${dateTime(plan.validUntil)} 前有效`" padding="flush">
        <template #extra><CalendarClock :size="18" class="plan-icon" /></template>
        <div class="plan-progress-line"><span :style="{ width: `${completionRate}%` }" /></div>
        <div class="plan-task-list">
          <article v-for="task in plan.tasks" :key="task.planTaskId" class="plan-task">
            <div class="plan-task__content">
              <span>{{ task.taskType === 'PRACTICE' ? '针对性练习' : '复习任务' }}</span>
              <h3>{{ task.questionTitle }}</h3>
              <p>{{ task.skillName }} · 已完成 {{ task.completedCount }}/{{ task.targetCount }} · 截止 {{ dateTime(task.dueAt) }}</p>
              <small v-if="task.status === 'SKIPPED'">跳过原因：{{ task.skipReason }}</small>
            </div>
            <el-tag :type="statusType(task.status)">{{ planTaskStatusLabel(task.status) }}</el-tag>
            <div class="plan-task__actions">
              <el-button
                v-if="plan.status === 'ACTIVE' && task.status === 'PENDING'"
                :loading="actionTaskId === task.planTaskId"
                :icon="CirclePlay"
                @click="startTask(task)"
              >开始</el-button>
              <el-button
                v-if="plan.status === 'ACTIVE' && ['PENDING', 'IN_PROGRESS'].includes(task.status)"
                type="primary"
                :icon="ArrowRight"
                @click="openPractice(task)"
              >进入练习</el-button>
              <el-button
                v-if="plan.status === 'ACTIVE' && ['PENDING', 'IN_PROGRESS'].includes(task.status)"
                :loading="actionTaskId === task.planTaskId"
                :icon="SkipForward"
                @click="skipTask(task)"
              >跳过</el-button>
            </div>
          </article>
          <div v-if="!plan.tasks.length" class="lms-empty-state">当前计划没有学习任务</div>
        </div>
      </PanelCard>
    </template>
  </div>
</template>

<style scoped>
.plan-notice { margin-bottom: 14px; padding: 11px 14px; border: 1px solid var(--border); border-radius: 6px; color: var(--muted); background: var(--surface-soft); font-size: 12px; }
.plan-notice--warning { color: var(--warning); border-color: #fed7aa; background: var(--warning-soft); }
.plan-tasks-card { margin-top: 14px; }
.plan-icon { color: var(--accent); }
.plan-progress-line { height: 6px; margin: 0 18px 8px; overflow: hidden; border-radius: 999px; background: var(--border); }
.plan-progress-line span { display: block; height: 100%; background: var(--accent); transition: width 300ms ease; }
.plan-task-list { border-top: 1px solid var(--border); }
.plan-task { min-height: 94px; display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 16px; padding: 16px 18px; border-bottom: 1px solid var(--border); }
.plan-task:last-child { border-bottom: 0; }
.plan-task__content { min-width: 0; }
.plan-task__content > span { color: var(--accent-dark); font-size: 11px; font-weight: 650; }
.plan-task h3 { margin: 4px 0; color: var(--ink-strong); font-size: 14px; }
.plan-task p, .plan-task small { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.6; }
.plan-task small { color: var(--warning); }
.plan-task__actions { display: flex; gap: 8px; }
@media (max-width: 760px) {
  .plan-task { grid-template-columns: minmax(0, 1fr) auto; }
  .plan-task__actions { grid-column: 1 / -1; flex-wrap: wrap; }
}
</style>
