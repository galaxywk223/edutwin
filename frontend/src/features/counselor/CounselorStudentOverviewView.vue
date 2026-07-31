<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, BookOpen, GraduationCap, RefreshCw, ShieldCheck, TriangleAlert } from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import RiskTag from '@/shared/components/RiskTag.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { CounselorStudentOverview, RiskBand } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const route = useRoute()
const router = useRouter()
const overview = ref<CounselorStudentOverview | null>(null)
const loading = ref(true)
const error = ref('')
const studentId = computed(() => typeof route.params.studentId === 'string' ? route.params.studentId : '')

function percentage(value: number | null, probability = false) {
  if (value == null) return '-'
  return `${Math.round(probability ? value * 100 : value)}%`
}

async function load() {
  if (!studentId.value) return
  loading.value = true
  error.value = ''
  try {
    overview.value = await api.counselorStudentOverview(studentId.value)
  } catch (cause) {
    error.value = userErrorMessage(cause, '学生跨课程统计读取失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      :title="overview?.student.displayName ?? '学生学业详情'"
      :description="overview ? `${overview.student.studentNumber} · ${overview.student.cohortYear} 级 ${overview.student.major} · ${overview.student.className}` : '跨课程学习统计'"
    >
      <template #actions>
        <el-button :icon="ArrowLeft" @click="router.push('/counselor/students')">返回列表</el-button>
        <el-button type="primary" :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <div class="readonly-notice">
      <ShieldCheck :size="18" />
      <span>该页面仅汇总课程进度、成绩、掌握度与风险，不展示原始答案且不可修改。</span>
    </div>

    <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="overview">
      <section class="stat-grid">
        <StatCard label="课程数" :value="overview.student.courseCount" caption="在读与已完成" :icon="BookOpen" />
        <StatCard label="累计学分" :value="overview.student.totalCredits" caption="课程学分合计" :icon="GraduationCap" tone="info" />
        <StatCard
          label="平均成绩"
          :value="percentage(overview.student.averageScorePercentage)"
          caption="已提交考核"
          :icon="GraduationCap"
          tone="success"
        />
        <StatCard
          label="高风险课程"
          :value="overview.student.highRiskCourseCount"
          caption="当前风险投影"
          :icon="TriangleAlert"
          tone="warning"
        />
      </section>

      <PanelCard
        class="course-statistics"
        title="课程统计"
        :subtitle="`每门课程的学习进度、成绩、掌握度和当前风险 · ${overview.courses.length} 门`"
        padding="flush"
      >
        <el-table :data="overview.courses" row-key="courseId">
          <el-table-column label="课程" min-width="230">
            <template #default="{ row }">
              <div class="lms-primary-cell">
                <strong>{{ row.title }}</strong>
                <span>
                  {{ row.code }}
                  <template v-if="row.termLabel"> · {{ row.termLabel }}</template>
                  · {{ row.credits }} 学分
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="任课教师" min-width="140">
            <template #default="{ row }">{{ row.instructors.join('、') || '-' }}</template>
          </el-table-column>
          <el-table-column label="课时进度" width="110">
            <template #default="{ row }">{{ row.completedLessons }}/{{ row.totalLessons }}</template>
          </el-table-column>
          <el-table-column label="考核提交" width="110">
            <template #default="{ row }">{{ row.submittedAssessments }}/{{ row.publishedAssessments }}</template>
          </el-table-column>
          <el-table-column label="成绩" width="90">
            <template #default="{ row }">{{ percentage(row.scorePercentage) }}</template>
          </el-table-column>
          <el-table-column label="掌握度" width="96">
            <template #default="{ row }">{{ percentage(row.averageMastery, true) }}</template>
          </el-table-column>
          <el-table-column label="风险" width="110">
            <template #default="{ row }">
              <RiskTag v-if="row.riskBand" :band="row.riskBand as RiskBand" compact />
              <span v-else class="muted-dash">暂无</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!overview.courses.length" class="lms-empty-state">该学生暂无课程统计</div>
      </PanelCard>
    </template>
  </div>
</template>

<style scoped>
.stat-grid {
  margin-bottom: 16px;
}

.course-statistics {
  margin-top: 0;
}

.muted-dash {
  color: var(--muted);
  font-size: 12px;
}
</style>
