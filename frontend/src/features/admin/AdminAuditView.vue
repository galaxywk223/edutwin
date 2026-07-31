<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Download, RefreshCw, ScrollText } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import type { AdminAuditEvent, AdminUser } from '@/shared/types/api'
import { auditActionLabel, auditOutcomeLabel, auditTargetLabel, userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const items = ref<AdminAuditEvent[]>([])
const actors = ref<AdminUser[]>([])
const total = ref(0)
const detail = ref<AdminAuditEvent | null>(null)
const detailOpen = ref(false)
const loading = ref(true)
const exporting = ref(false)
const error = ref('')
const filters = reactive({
  page: 0,
  size: 20,
  range: [] as string[],
  actorUserId: '',
  action: '',
  targetType: '',
  outcome: '',
})

function query(includePage = true) {
  const params = new URLSearchParams()
  if (includePage) {
    params.set('page', String(filters.page))
    params.set('size', String(filters.size))
  }
  if (filters.range.length === 2) {
    params.set('from', filters.range[0])
    params.set('to', filters.range[1])
  }
  if (filters.actorUserId.trim()) params.set('actorUserId', filters.actorUserId.trim())
  if (filters.action.trim()) params.set('action', filters.action.trim())
  if (filters.targetType.trim()) params.set('targetType', filters.targetType.trim())
  if (filters.outcome) params.set('outcome', filters.outcome)
  return params
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result, users] = await Promise.all([api.adminAuditEvents(query()), api.adminUsers()])
    items.value = result.items
    total.value = result.total
    actors.value = users.items
  } catch (cause) {
    error.value = userErrorMessage(cause, '审计日志读取失败。')
  } finally {
    loading.value = false
  }
}

async function reset() {
  Object.assign(filters, { page: 0, size: 20, range: [], actorUserId: '', action: '', targetType: '', outcome: '' })
  await load()
}

async function openDetail(item: AdminAuditEvent) {
  try {
    detail.value = await api.adminAuditEvent(item.id)
    detailOpen.value = true
  } catch (cause) {
    error.value = userErrorMessage(cause, '审计详情读取失败。')
  }
}

async function exportCsv() {
  exporting.value = true
  try {
    const blob = await api.exportAdminAuditEvents(query(false))
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'edutwin-audit.csv'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (cause) {
    error.value = userErrorMessage(cause, '审计日志导出失败。')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      title="审计日志"
      description="管理员治理操作的不可变记录。"
    >
      <template #actions>
        <el-button :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        <el-button type="primary" :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <section v-if="!loading && !error" class="audit-filters">
      <el-date-picker v-model="filters.range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ssZ" start-placeholder="开始时间" end-placeholder="结束时间" />
      <el-select v-model="filters.actorUserId" placeholder="全部操作者" clearable filterable>
        <el-option
          v-for="actor in actors"
          :key="actor.userId"
          :label="`${actor.displayName} · ${actor.username}`"
          :value="actor.userId"
        />
      </el-select>
      <el-input v-model="filters.action" placeholder="动作代码" clearable />
      <el-input v-model="filters.targetType" placeholder="对象类型" clearable />
      <el-select v-model="filters.outcome" placeholder="全部结果" clearable>
        <el-option label="成功" value="SUCCEEDED" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-button type="primary" @click="filters.page = 0; load()">筛选</el-button>
      <el-button @click="reset">重置</el-button>
    </section>

    <PanelCard
      v-if="!loading && !error"
      title="操作记录"
      :subtitle="`${total} 条事件`"
      padding="flush"
    >
      <template #extra>
        <ScrollText :size="18" class="audit-icon" />
      </template>
      <el-table :data="items" row-key="id" @row-click="openDetail">
        <el-table-column label="时间" min-width="175">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="动作" min-width="180">
          <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column label="业务对象" width="140">
          <template #default="{ row }">{{ auditTargetLabel(row.targetType) }}</template>
        </el-table-column>
        <el-table-column label="操作者" min-width="145">
          <template #default="{ row }">{{ row.actorDisplayName }}</template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">{{ auditOutcomeLabel(row.outcome) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!items.length" class="lms-empty-state">暂无审计事件</div>
      <el-pagination
        v-if="total > filters.size"
        :current-page="filters.page + 1"
        v-model:page-size="filters.size"
        :page-sizes="[20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @current-change="(page: number) => { filters.page = page - 1; load() }"
        @size-change="() => { filters.page = 0; load() }"
      />
    </PanelCard>

    <el-drawer v-model="detailOpen" title="审计详情" size="min(560px, 100%)">
      <dl v-if="detail" class="audit-detail">
        <dt>动作</dt><dd>{{ auditActionLabel(detail.action) }}</dd>
        <dt>操作者</dt><dd>{{ detail.actorDisplayName }}</dd>
        <dt>活动角色</dt><dd>{{ detail.activeRole ?? '历史记录未提供' }}</dd>
        <dt>目标标识</dt><dd>{{ detail.targetId }}</dd>
        <dt>关联标识</dt><dd>{{ detail.correlationId ?? '历史记录未提供' }}</dd>
        <dt>结果</dt><dd>{{ auditOutcomeLabel(detail.outcome) }}</dd>
        <dt>原因</dt><dd>{{ detail.reason ?? '未填写' }}</dd>
        <dt>错误代码</dt><dd>{{ detail.errorCode ?? '无' }}</dd>
        <dt>变更前</dt><dd><pre>{{ detail.beforeJson ?? '无' }}</pre></dd>
        <dt>变更后</dt><dd><pre>{{ detail.afterJson ?? '无' }}</pre></dd>
        <dt>元数据</dt><dd><pre>{{ detail.metadataJson ?? '无' }}</pre></dd>
      </dl>
    </el-drawer>
  </div>
</template>

<style scoped>
.audit-icon {
  color: var(--accent);
}

.audit-filters {
  display: grid;
  grid-template-columns: minmax(260px, 1.4fr) repeat(4, minmax(140px, 1fr)) auto auto;
  gap: 10px;
  margin-bottom: 16px;
}

.audit-detail {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 12px;
  margin: 0;
}

.audit-detail dt { color: var(--muted); }
.audit-detail dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.audit-detail pre { margin: 0; white-space: pre-wrap; font-size: 11px; }

@media (max-width: 980px) {
  .audit-filters { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 560px) {
  .audit-filters { grid-template-columns: 1fr; }
}
</style>
