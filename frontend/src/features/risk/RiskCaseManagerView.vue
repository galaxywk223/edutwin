<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, ClipboardPlus, MessageSquarePlus, RefreshCw, Search, TriangleAlert } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { businessApi } from '@/api/client/business'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { RiskCaseDetail, RiskCasePage, RiskCaseSummary } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'

const cases = ref<RiskCasePage | null>(null)
const detail = ref<RiskCaseDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const drawerOpen = ref(false)
const page = ref(1)
const filters = reactive({ status: '', assignedToMe: false })
const noteBody = ref('')
const actionDialog = ref(false)
const actionForm = reactive({ title: '', description: '', dueAt: '' })
const saving = ref(false)

const activeCount = computed(() => cases.value?.items.filter((item) => ['OPEN', 'IN_PROGRESS'].includes(item.status)).length ?? 0)
const urgentCount = computed(() => cases.value?.items.filter((item) => item.priority === 'URGENT').length ?? 0)

function statusLabel(status: string) {
  return ({ OPEN: '待处理', IN_PROGRESS: '跟进中', RESOLVED: '已解决', CLOSED: '已关闭' } as Record<string, string>)[status] ?? status
}

function priorityLabel(priority: string) {
  return ({ MEDIUM: '常规', HIGH: '优先', URGENT: '紧急' } as Record<string, string>)[priority] ?? priority
}

function actionStatusLabel(status: string) {
  return ({ PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' } as Record<string, string>)[status] ?? status
}

async function load(reset = false) {
  if (reset) page.value = 1
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ page: String(page.value - 1), size: '25' })
  if (filters.status) params.set('status', filters.status)
  try {
    cases.value = await businessApi.riskCases(params)
  } catch (cause) {
    error.value = userErrorMessage(cause, '课程学习风险事项读取失败。')
  } finally {
    loading.value = false
  }
}

async function openCase(item: RiskCaseSummary) {
  drawerOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await businessApi.riskCase(item.caseId)
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '风险事项详情读取失败。'))
  } finally {
    detailLoading.value = false
  }
}

async function transition(targetStatus: string) {
  if (!detail.value) return
  try {
    const prompt = await ElMessageBox.prompt('填写本次状态变更说明。', `变更为${statusLabel(targetStatus)}`, {
      inputPlaceholder: '处理说明', inputValidator: (value) => Boolean(value.trim()) || '处理说明不能为空',
    })
    detail.value = await businessApi.transitionRiskCase(
      detail.value.riskCase.caseId, targetStatus, prompt.value.trim(), detail.value.riskCase.version,
    )
    await load()
    ElMessage.success('风险事项状态已更新')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '状态更新失败。'))
  }
}

async function addNote() {
  if (!detail.value || !noteBody.value.trim()) return
  saving.value = true
  try {
    detail.value = await businessApi.addRiskNote(detail.value.riskCase.caseId, noteBody.value.trim())
    noteBody.value = ''
    ElMessage.success('内部记录已添加')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '内部记录添加失败。'))
  } finally {
    saving.value = false
  }
}

function openActionDialog() {
  Object.assign(actionForm, { title: '', description: '', dueAt: '' })
  actionDialog.value = true
}

async function addAction() {
  if (!detail.value || !actionForm.title.trim()) return ElMessage.warning('行动项名称不能为空')
  saving.value = true
  try {
    detail.value = await businessApi.addRiskAction(
      detail.value.riskCase.caseId,
      actionForm.title.trim(),
      actionForm.description.trim(),
      actionForm.dueAt ? new Date(actionForm.dueAt).toISOString() : null,
    )
    actionDialog.value = false
    ElMessage.success('学生行动项已创建')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '行动项创建失败。'))
  } finally {
    saving.value = false
  }
}

async function completeAction(actionId: string) {
  if (!detail.value) return
  try {
    const prompt = await ElMessageBox.prompt('填写行动项完成结果。', '完成行动项', {
      inputPlaceholder: '完成结果', inputValidator: (value) => Boolean(value.trim()) || '完成结果不能为空',
    })
    detail.value = await businessApi.updateRiskAction(detail.value.riskCase.caseId, actionId, 'COMPLETED', prompt.value.trim())
    ElMessage.success('行动项已完成')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '行动项更新失败。'))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="课程学习风险事项" description="集中跟进课程学习风险、处理记录与学生行动项。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load()">刷新</el-button></template>
    </PageHeader>

    <div class="risk-summary">
      <div><span>本页事项</span><strong>{{ cases?.items.length ?? 0 }}</strong></div>
      <div><span>处理中</span><strong>{{ activeCount }}</strong></div>
      <div><span>紧急事项</span><strong>{{ urgentCount }}</strong></div>
    </div>
    <section class="filter-bar risk-filters">
      <el-select v-model="filters.status" clearable placeholder="处理状态">
        <el-option label="待处理" value="OPEN" /><el-option label="跟进中" value="IN_PROGRESS" />
        <el-option label="已解决" value="RESOLVED" /><el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
    </section>

    <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load()" />
    <section v-else class="lms-table-section">
      <el-table :data="cases?.items ?? []" row-key="caseId" @row-click="openCase">
        <el-table-column label="学生与课程" min-width="220"><template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.studentName }}</strong><span>{{ row.courseTitle }}</span></div></template></el-table-column>
        <el-table-column label="事项" min-width="260"><template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.title }}</strong><span>{{ row.summary }}</span></div></template></el-table-column>
        <el-table-column label="风险程度" width="100"><template #default="{ row }"><el-tag :type="row.riskBand === 'HIGH' ? 'danger' : row.riskBand === 'MEDIUM' ? 'warning' : 'success'">{{ row.riskBand === 'HIGH' ? '高' : row.riskBand === 'MEDIUM' ? '中' : '低' }}</el-tag></template></el-table-column>
        <el-table-column label="优先级" width="90"><template #default="{ row }">{{ priorityLabel(row.priority) }}</template></el-table-column>
        <el-table-column label="负责人" width="120"><template #default="{ row }">{{ row.assigneeName || '待分配' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column label="更新时间" min-width="165"><template #default="{ row }">{{ new Date(row.updatedAt).toLocaleString() }}</template></el-table-column>
      </el-table>
      <div v-if="!cases?.items.length" class="lms-empty-state"><TriangleAlert :size="22" /> 当前没有风险事项</div>
      <div class="lms-pagination"><el-pagination v-model:current-page="page" :page-size="25" :total="cases?.total ?? 0" layout="total, prev, pager, next" @current-change="load()" /></div>
    </section>

    <el-drawer v-model="drawerOpen" title="风险事项详情" size="min(820px, 96vw)">
      <div v-if="detailLoading" class="lms-empty-inline">正在读取详情</div>
      <template v-else-if="detail">
        <header class="risk-detail-header">
          <div><span>{{ detail.riskCase.studentName }} · {{ detail.riskCase.courseTitle }}</span><h2>{{ detail.riskCase.title }}</h2><p>{{ detail.riskCase.summary }}</p></div>
          <el-tag>{{ statusLabel(detail.riskCase.status) }}</el-tag>
        </header>
        <div class="risk-detail-actions">
          <el-button v-if="detail.riskCase.status === 'OPEN'" type="primary" @click="transition('IN_PROGRESS')">开始跟进</el-button>
          <el-button v-if="detail.riskCase.status === 'IN_PROGRESS'" type="primary" :icon="CheckCircle2" @click="transition('RESOLVED')">标记解决</el-button>
          <el-button v-if="detail.riskCase.status === 'RESOLVED'" @click="transition('IN_PROGRESS')">重新跟进</el-button>
          <el-button v-if="detail.riskCase.status !== 'CLOSED'" @click="transition('CLOSED')">关闭事项</el-button>
          <el-button :icon="ClipboardPlus" @click="openActionDialog">新增行动项</el-button>
        </div>

        <section class="risk-detail-section">
          <h3>学生行动项</h3>
          <article v-for="action in detail.actionItems" :key="action.actionItemId" class="risk-action">
            <div><strong>{{ action.title }}</strong><p>{{ action.description }}</p><small>{{ action.dueAt ? `截止 ${new Date(action.dueAt).toLocaleString()}` : '无截止时间' }}</small></div>
            <el-tag>{{ actionStatusLabel(action.status) }}</el-tag>
            <el-button v-if="action.status !== 'COMPLETED' && action.status !== 'CANCELLED'" link type="primary" @click="completeAction(action.actionItemId)">完成</el-button>
          </article>
          <div v-if="!detail.actionItems.length" class="lms-empty-inline">尚未创建学生行动项</div>
        </section>

        <section class="risk-detail-section">
          <h3>内部跟进记录</h3>
          <article v-for="note in detail.internalNotes" :key="note.noteId" class="risk-note"><strong>{{ note.authorName }}</strong><p>{{ note.body }}</p><time>{{ new Date(note.createdAt).toLocaleString() }}</time></article>
          <el-input v-model="noteBody" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="记录本次跟进情况" />
          <el-button class="risk-note-submit" type="primary" :icon="MessageSquarePlus" :loading="saving" :disabled="!noteBody.trim()" @click="addNote">添加记录</el-button>
        </section>
      </template>
    </el-drawer>

    <el-dialog v-model="actionDialog" title="新增学生行动项" width="min(580px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="行动项名称"><el-input v-model="actionForm.title" /></el-form-item>
        <el-form-item label="行动说明"><el-input v-model="actionForm.description" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="actionForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="actionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="addAction">创建行动项</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.risk-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.risk-summary > div { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }
.risk-summary span { color: var(--muted); font-size: 11px; }.risk-summary strong { color: var(--ink-strong); font-size: 24px; }
.risk-filters { grid-template-columns: minmax(180px, 260px) auto; }
.lms-table-section :deep(.el-table__row) { cursor: pointer; }
.risk-detail-header { display: flex; justify-content: space-between; gap: 16px; padding-bottom: 16px; border-bottom: 1px solid var(--border); }
.risk-detail-header span { color: var(--accent-dark); font-size: 12px; }.risk-detail-header h2 { margin: 6px 0; font-size: 19px; }.risk-detail-header p { margin: 0; color: var(--muted); font-size: 13px; }
.risk-detail-actions { display: flex; flex-wrap: wrap; gap: 8px; padding: 14px 0; }
.risk-detail-section { padding: 18px 0; border-top: 1px solid var(--border); }.risk-detail-section h3 { margin: 0 0 12px; font-size: 14px; }
.risk-action { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border); }.risk-action p { margin: 3px 0; color: var(--muted); font-size: 12px; }.risk-action small { color: var(--subtle); }
.risk-note { margin-bottom: 9px; padding: 12px; border-radius: 6px; background: var(--surface-soft); }.risk-note p { margin: 5px 0; color: var(--ink); font-size: 12px; }.risk-note time { color: var(--muted); font-size: 10px; }.risk-note-submit { margin-top: 9px; }
@media (max-width: 640px) { .risk-summary { grid-template-columns: 1fr; }.risk-action { grid-template-columns: minmax(0, 1fr) auto; }.risk-action .el-button { grid-column: 1 / -1; justify-self: start; } }
</style>
