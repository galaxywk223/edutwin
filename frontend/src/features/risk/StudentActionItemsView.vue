<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CalendarClock, MessageSquarePlus, RefreshCw } from '@lucide/vue'
import { ElMessage } from 'element-plus'

import { businessApi } from '@/api/client/business'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { StudentActionProjection } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'

const items = ref<StudentActionProjection[]>([])
const loading = ref(false)
const error = ref('')
const feedbackOpen = ref(false)
const feedbackBody = ref('')
const active = ref<StudentActionProjection | null>(null)
const saving = ref(false)
const activeCount = computed(() => items.value.filter((item) => ['PENDING', 'IN_PROGRESS'].includes(item.status)).length)

function statusLabel(status: string) {
  return ({ PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' } as Record<string, string>)[status] ?? status
}

async function load() {
  loading.value = true
  error.value = ''
  try { items.value = await businessApi.studentActions() }
  catch (cause) { error.value = userErrorMessage(cause, '行动项读取失败。') }
  finally { loading.value = false }
}

function openFeedback(item: StudentActionProjection) {
  active.value = item
  feedbackBody.value = ''
  feedbackOpen.value = true
}

async function submitFeedback() {
  if (!active.value || !feedbackBody.value.trim()) return
  saving.value = true
  try {
    const updated = await businessApi.addStudentActionFeedback(active.value.actionItemId, feedbackBody.value.trim())
    const index = items.value.findIndex((item) => item.actionItemId === updated.actionItemId)
    if (index >= 0) items.value[index] = updated
    feedbackOpen.value = false
    ElMessage.success('反馈已提交')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '反馈提交失败。'))
  } finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="学习行动项" description="查看教师或辅导员安排的学习行动，并反馈执行情况。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
    </PageHeader>
    <div class="action-summary"><span>待完成行动项</span><strong>{{ activeCount }}</strong><small>全部 {{ items.length }} 项</small></div>
    <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="student-action-list">
      <article v-for="item in items" :key="item.actionItemId" class="student-action">
        <div class="student-action__main">
          <span>{{ item.courseTitle }}</span>
          <h2>{{ item.title }}</h2>
          <p>{{ item.description }}</p>
          <small><CalendarClock :size="14" /> {{ item.dueAt ? `截止 ${new Date(item.dueAt).toLocaleString()}` : '无截止时间' }}</small>
        </div>
        <el-tag :type="item.status === 'COMPLETED' ? 'success' : item.status === 'CANCELLED' ? 'info' : 'warning'">{{ statusLabel(item.status) }}</el-tag>
        <div class="student-action__feedback">
          <strong>执行反馈</strong>
          <p v-if="item.resultSummary">处理结果：{{ item.resultSummary }}</p>
          <blockquote v-for="feedback in item.feedback" :key="feedback.feedbackId">{{ feedback.body }}<time>{{ new Date(feedback.createdAt).toLocaleString() }}</time></blockquote>
          <el-button :icon="MessageSquarePlus" @click="openFeedback(item)">提交反馈</el-button>
        </div>
      </article>
      <div v-if="!items.length" class="lms-empty-state">当前没有学习行动项</div>
    </section>

    <el-dialog v-model="feedbackOpen" :title="active ? `反馈 · ${active.title}` : '提交反馈'" width="min(560px, 94vw)">
      <el-input v-model="feedbackBody" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="说明当前进展、困难或完成情况" />
      <template #footer><el-button @click="feedbackOpen = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!feedbackBody.trim()" @click="submitFeedback">提交反馈</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.action-summary { width: min(320px, 100%); display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px 14px; margin-bottom: 14px; padding: 14px 16px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }
.action-summary span { color: var(--muted); font-size: 12px; }.action-summary strong { grid-row: 1 / 3; grid-column: 2; color: var(--accent-dark); font-size: 28px; }.action-summary small { color: var(--subtle); }
.student-action-list { display: grid; gap: 12px; }
.student-action { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px 18px; padding: 18px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }
.student-action__main > span { color: var(--accent-dark); font-size: 11px; font-weight: 650; }.student-action h2 { margin: 5px 0; color: var(--ink-strong); font-size: 16px; }.student-action p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }.student-action small { display: flex; align-items: center; gap: 5px; margin-top: 9px; color: var(--muted); }
.student-action__feedback { grid-column: 1 / -1; padding-top: 12px; border-top: 1px solid var(--border); }.student-action__feedback > strong { display: block; margin-bottom: 8px; font-size: 12px; }.student-action__feedback > p { margin-bottom: 8px; }
blockquote { display: flex; justify-content: space-between; gap: 12px; margin: 7px 0; padding: 10px 12px; border-left: 3px solid var(--accent); background: var(--surface-soft); color: var(--ink); font-size: 12px; } blockquote time { flex: none; color: var(--muted); font-size: 10px; }
@media (max-width: 560px) { blockquote { flex-direction: column; }.student-action { padding: 15px; } }
</style>
