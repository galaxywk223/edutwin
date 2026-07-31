<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Award, CheckCircle2, RefreshCw } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { GradeList } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const { courseId } = useCourseContext()
const grades = ref<GradeList | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const submitted = computed(() => grades.value?.items.filter((item) => item.submitted) ?? [])
const average = computed(() => {
  if (!submitted.value.length) return 0
  return Math.round(submitted.value.reduce((sum, item) => sum + (item.percentage ?? 0), 0) / submitted.value.length)
})

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = null
  try {
    grades.value = await api.studentGrades(courseId.value)
  } catch (reason) {
    error.value = userErrorMessage(reason, '成绩读取失败')
  } finally {
    loading.value = false
  }
}

watch(courseId, load)
onMounted(load)
</script>

<template>
  <PageHeader
    title="学习成绩"
    description="查看课程考核完成情况、自动评分结果和提交时间。"
  >
    <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
  </PageHeader>
  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else-if="grades">
    <div class="lms-summary-strip">
      <div><span>课程考核</span><strong>{{ grades.total }}</strong></div>
      <div><span>已提交</span><strong>{{ submitted.length }}</strong></div>
      <div><span>平均成绩</span><strong>{{ average }}%</strong></div>
    </div>
    <section class="lms-table-section">
      <el-table :data="grades.items" row-key="assessmentId">
        <el-table-column label="考核" min-width="260"><template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.title }}</strong><span>{{ row.submitted ? '已完成自动评分' : '等待提交' }}</span></div></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.submitted ? 'success' : 'info'"><CheckCircle2 v-if="row.submitted" :size="13" />{{ row.submitted ? '已提交' : '未提交' }}</el-tag></template></el-table-column>
        <el-table-column label="得分" width="120"><template #default="{ row }">{{ row.submitted ? `${row.score} / ${row.maxScore}` : '—' }}</template></el-table-column>
        <el-table-column label="成绩" width="120"><template #default="{ row }"><strong v-if="row.percentage != null" class="lms-grade-value"><Award :size="16" />{{ row.percentage }}%</strong><span v-else>—</span></template></el-table-column>
        <el-table-column label="提交时间" min-width="170"><template #default="{ row }">{{ row.submittedAt ? new Date(row.submittedAt).toLocaleString() : '—' }}</template></el-table-column>
      </el-table>
      <div v-if="!grades.items.length" class="lms-empty-state">当前课程没有已发布考核</div>
    </section>
  </template>
</template>
