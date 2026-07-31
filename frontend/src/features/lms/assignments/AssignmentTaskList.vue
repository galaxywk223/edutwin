<script setup lang="ts">
import { computed } from 'vue'
import { Eye, FilePenLine, LockKeyhole } from '@lucide/vue'

import type { AssessmentSummary } from '@/shared/types/api'
import type { LearnerAssessmentFields } from '@/shared/types/business'
import { learnerStateLabel } from '../businessState'

export type TaskFilter = 'ALL' | 'PENDING' | 'COMPLETED' | 'CLOSED'
export type TaskState = Exclude<TaskFilter, 'ALL'>
type LearnerAssessment = AssessmentSummary & LearnerAssessmentFields

const props = defineProps<{ items: LearnerAssessment[]; filter: TaskFilter }>()
const emit = defineEmits<{
  'update:filter': [value: TaskFilter]
  open: [item: LearnerAssessment]
  requestAttempt: [item: LearnerAssessment]
  viewAttempts: [item: LearnerAssessment]
}>()

function state(item: LearnerAssessment): TaskState {
  if (item.learnerState === 'SUBMITTED') return 'COMPLETED'
  if (item.learnerState === 'CLOSED_UNSUBMITTED' || item.learnerState === 'CANCELLED') return 'CLOSED'
  return 'PENDING'
}

const counts = computed(() => ({
  ALL: props.items.length,
  PENDING: props.items.filter((item) => state(item) === 'PENDING').length,
  COMPLETED: props.items.filter((item) => state(item) === 'COMPLETED').length,
  CLOSED: props.items.filter((item) => state(item) === 'CLOSED').length,
}))
const filtered = computed(() => props.filter === 'ALL'
  ? props.items
  : props.items.filter((item) => state(item) === props.filter))
const labels: Record<TaskFilter, string> = { ALL: '全部', PENDING: '待完成', COMPLETED: '已完成', CLOSED: '已截止' }
</script>

<template>
  <section class="assignment-list">
    <el-tabs :model-value="filter" class="assignment-tabs" @update:model-value="emit('update:filter', $event as TaskFilter)">
      <el-tab-pane v-for="key in (Object.keys(labels) as TaskFilter[])" :key="key" :name="key">
        <template #label><span>{{ labels[key] }} <b>{{ counts[key] }}</b></span></template>
      </el-tab-pane>
    </el-tabs>
    <el-table :data="filtered" row-key="assessmentId">
      <el-table-column prop="title" label="名称" min-width="220">
        <template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.title }}</strong><span>{{ row.description || '无说明' }}</span></div></template>
      </el-table-column>
      <el-table-column label="类型" width="86"><template #default="{ row }">{{ row.assessmentType === 'QUIZ' ? '测验' : '作业' }}</template></el-table-column>
      <el-table-column prop="questionCount" label="题量" width="70" />
      <el-table-column label="截止时间" min-width="170"><template #default="{ row }">{{ row.dueAt ? new Date(row.dueAt).toLocaleString() : '不限时' }}</template></el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }"><el-tag :type="state(row) === 'COMPLETED' ? 'success' : state(row) === 'CLOSED' ? 'danger' : 'warning'">{{ learnerStateLabel(row.learnerState) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="作答" width="92"><template #default="{ row }">{{ row.attemptCount }} 次</template></el-table-column>
      <el-table-column label="分数" width="100"><template #default="{ row }">{{ row.submitted ? `${row.score}/${row.maxScore}` : '—' }}</template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button v-if="state(row) === 'PENDING'" link type="primary" :icon="FilePenLine" @click="emit('open', row)">开始答题</el-button>
          <template v-else-if="state(row) === 'COMPLETED'">
            <el-button link :icon="Eye" @click="emit('open', row)">查看回顾</el-button>
            <el-button link type="primary" @click="emit('viewAttempts', row)">作答记录</el-button>
          </template>
          <template v-else>
            <span class="assignment-closed-action"><LockKeyhole :size="14" /> 已关闭</span>
            <el-button link type="primary" @click="emit('requestAttempt', row)">申请补交</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <div class="assignment-mobile-list">
      <article v-for="item in filtered" :key="item.assessmentId">
        <div class="assignment-mobile-list__body">
          <strong>{{ item.title }}</strong>
          <span>{{ item.assessmentType === 'QUIZ' ? '测验' : '作业' }} · {{ item.questionCount }} 题 · {{ item.attemptCount }} 次作答 · {{ item.dueAt ? new Date(item.dueAt).toLocaleDateString() : '不限时' }}</span>
        </div>
        <div class="assignment-mobile-list__state">
          <el-tag :type="state(item) === 'COMPLETED' ? 'success' : state(item) === 'CLOSED' ? 'danger' : 'warning'">{{ learnerStateLabel(item.learnerState) }}</el-tag>
          <b>{{ item.submitted ? `${item.score}/${item.maxScore}` : '' }}</b>
        </div>
        <el-button v-if="state(item) === 'PENDING'" link type="primary" :icon="FilePenLine" @click="emit('open', item)">开始答题</el-button>
        <template v-else-if="state(item) === 'COMPLETED'"><el-button link :icon="Eye" @click="emit('open', item)">查看回顾</el-button><el-button link type="primary" @click="emit('viewAttempts', item)">作答记录</el-button></template>
        <template v-else><span class="assignment-closed-action"><LockKeyhole :size="14" /> 已关闭</span><el-button link type="primary" @click="emit('requestAttempt', item)">申请补交</el-button></template>
      </article>
    </div>
    <div v-if="!filtered.length" class="lms-empty-state">当前筛选条件下没有课程任务</div>
  </section>
</template>
