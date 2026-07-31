<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BarChart, HeatmapChart, LineChart, PieChart, ScatterChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  AlertTriangle,
  ArrowRight,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Users,
} from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import RiskTag from '@/shared/components/RiskTag.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { AnalyticsPeriod, RiskBand, TeacherDashboard } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime, percent } from '@/shared/utils/format'

use([CanvasRenderer, LineChart, BarChart, PieChart, ScatterChart, HeatmapChart, GridComponent, TooltipComponent, LegendComponent, VisualMapComponent])

const { courseId } = useCourseContext()
const router = useRouter()
const dashboard = ref<TeacherDashboard | null>(null)
const loading = ref(true)
const error = ref('')
const riskFilter = ref<'ALL' | RiskBand>('ALL')
const period = ref<AnalyticsPeriod>('30D')

const behaviorLabels: Record<string, string> = {
  COURSE_ACCESS: '进入课程', LESSON_VIEW: '查看课件', VIDEO_PROGRESS: '视频学习',
  RESOURCE_VIEW: '查看资源', RESOURCE_DOWNLOAD: '下载资源', DISCUSSION_VIEW: '讨论区',
  PRACTICE_START: '开始练习', PRACTICE_SUBMIT: '提交练习',
  ASSESSMENT_OPEN: '打开测验', ASSESSMENT_SUBMIT: '提交测验',
}
const profileLabels: Record<string, string> = {
  STEADY: '稳定学习', IMPROVING: '持续进步', DISENGAGING: '参与下降',
  CRAMMING: '集中突击', RECOVERING: '恢复学习',
}

const filteredStudents = computed(() => {
  const students = dashboard.value?.students ?? []
  return riskFilter.value === 'ALL'
    ? students
    : students.filter((student) => student.riskBand === riskFilter.value)
})

const activityOption = computed(() => ({
  animationDuration: 450,
  grid: { top: 36, right: 22, bottom: 34, left: 46 },
  tooltip: { trigger: 'axis' },
  legend: { top: 0, right: 8, textStyle: { color: '#6b7280', fontSize: 11 } },
  xAxis: {
    type: 'category',
    data: dashboard.value?.activityTrend.map((point) => point.date.slice(5)) ?? [],
    boundaryGap: false,
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisTick: { show: false },
    axisLabel: { color: '#9ca3af', fontSize: 10 },
  },
  yAxis: [
    {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#9ca3af', fontSize: 10 },
    },
    {
      type: 'value',
      min: 0,
      max: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: { formatter: (value: number) => `${value * 100}%`, color: '#9ca3af', fontSize: 10 },
    },
  ],
  series: [
    {
      name: '答题数',
      type: 'bar',
      data: dashboard.value?.activityTrend.map((point) => point.answerCount) ?? [],
      barMaxWidth: 18,
      itemStyle: { color: '#99f6e4', borderRadius: [4, 4, 0, 0] },
    },
    {
      name: '正确率',
      type: 'line',
      yAxisIndex: 1,
      data: dashboard.value?.activityTrend.map((point) => point.correctRate) ?? [],
      symbolSize: 6,
      lineStyle: { color: '#0d9488', width: 2.5 },
      itemStyle: { color: '#0d9488' },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(13, 148, 136, 0.16)' },
            { offset: 1, color: 'rgba(13, 148, 136, 0.01)' },
          ],
        },
      },
    },
  ],
}))

const riskOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 人（{d}%）' },
  legend: { bottom: 0, textStyle: { color: '#6b7280', fontSize: 11 } },
  series: [{ type: 'pie', radius: ['52%', '76%'], center: ['50%', '44%'], label: { show: false },
    data: [
      { name: '高风险', value: dashboard.value?.highRiskCount ?? 0, itemStyle: { color: '#dc2626' } },
      { name: '中风险', value: dashboard.value?.mediumRiskCount ?? 0, itemStyle: { color: '#d97706' } },
      { name: '低风险', value: dashboard.value?.lowRiskCount ?? 0, itemStyle: { color: '#0d9488' } },
    ] }],
}))

const behaviorOption = computed(() => {
  const values = [...(dashboard.value?.behaviorDistribution ?? [])].slice(0, 8).reverse()
  return {
    grid: { top: 12, right: 34, bottom: 26, left: 82 }, tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: { color: '#9ca3af' } },
    yAxis: { type: 'category', data: values.map((item) => behaviorLabels[item.behaviorType] ?? item.behaviorType), axisTick: { show: false }, axisLine: { show: false }, axisLabel: { color: '#6b7280', fontSize: 10 } },
    series: [{ type: 'bar', data: values.map((item) => item.eventCount), barMaxWidth: 14, itemStyle: { color: '#2563eb', borderRadius: [0, 4, 4, 0] } }],
  }
})

const heatmapOption = computed(() => ({
  tooltip: { formatter: (params: { value: number[] }) => `周${'一二三四五六日'[params.value[1] - 1]} ${params.value[0]}:00<br/>${params.value[2]} 次活动` },
  grid: { top: 18, right: 28, bottom: 52, left: 42 },
  xAxis: { type: 'category', data: Array.from({ length: 24 }, (_, hour) => `${hour}`), splitArea: { show: true }, axisLabel: { color: '#9ca3af', interval: 2 } },
  yAxis: { type: 'category', data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'], splitArea: { show: true }, axisLabel: { color: '#6b7280' } },
  visualMap: { min: 0, max: Math.max(1, ...(dashboard.value?.activityHeatmap ?? []).map((item) => item.eventCount), 1), calculable: false, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: ['#eff6ff', '#60a5fa', '#1d4ed8'] }, textStyle: { fontSize: 9, color: '#9ca3af' } },
  series: [{ type: 'heatmap', data: (dashboard.value?.activityHeatmap ?? []).map((item) => [item.hour, item.weekday - 1, item.eventCount]), emphasis: { itemStyle: { borderColor: '#111827', borderWidth: 1 } } }],
}))

const scatterOption = computed(() => ({
  tooltip: { formatter: (params: { data: { name: string; value: number[]; profile: string } }) => `${params.data.name}<br/>掌握度 ${percent(params.data.value[0])}<br/>风险 ${percent(params.data.value[1])}<br/>${profileLabels[params.data.profile] ?? params.data.profile}` },
  grid: { top: 20, right: 24, bottom: 42, left: 48 },
  xAxis: { name: '平均掌握度', min: 0, max: 1, nameLocation: 'middle', nameGap: 28, axisLabel: { formatter: (value: number) => `${value * 100}%`, color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
  yAxis: { name: '风险概率', min: 0, max: 1, axisLabel: { formatter: (value: number) => `${value * 100}%`, color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{ type: 'scatter', symbolSize: 9, itemStyle: { color: '#7c3aed', opacity: 0.62 }, data: (dashboard.value?.studentScatter ?? []).map((item) => ({ name: item.displayName, profile: item.behaviorProfile, value: [item.averageMastery, item.riskProbability, item.engagementScore] })) }],
}))

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = ''
  try {
    dashboard.value = await api.dashboard(courseId.value, period.value)
  } catch (cause) {
    error.value = userErrorMessage(cause, '教师驾驶舱读取失败。')
  } finally {
    loading.value = false
  }
}

function openStudent(studentId: string) {
  if (!courseId.value) return
  void router.push(`/teacher/courses/${courseId.value}/students/${studentId}/twin`)
}

onMounted(load)
</script>

<template>
  <div class="dashboard-page">
    <PageHeader
      title="教学概览"
      description="课程风险、学习活动和知识薄弱点按当前学习状态聚合，支持风险筛选与学生下钻。"
    >
      <template #actions>
        <el-segmented v-model="period" :options="[{ label: '近 7 天', value: '7D' }, { label: '近 30 天', value: '30D' }, { label: '本学期', value: 'TERM' }]" @change="load" />
        <el-button type="primary" :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <template v-if="dashboard && !loading">
      <section class="stat-grid">
        <StatCard
          label="活跃学生"
          :value="dashboard.activeStudentCount"
          :caption="`课程共 ${dashboard.studentCount} 人`"
          :icon="Users"
          tone="info"
        />
        <StatCard
          label="期间答题"
          :value="dashboard.answerCount"
          :caption="`正确率 ${percent(dashboard.averageCorrectRate)}`"
          :icon="AlertTriangle"
          tone="warning"
        />
        <StatCard
          label="高风险"
          :value="dashboard.highRiskCount"
          caption="需优先关注"
          :icon="ShieldAlert"
          tone="danger"
        />
        <StatCard
          label="低风险"
          :value="dashboard.lowRiskCount"
          :caption="`中风险 ${dashboard.mediumRiskCount} 人`"
          :icon="ShieldCheck"
          tone="success"
        />
      </section>

      <section class="dashboard-overview">
        <PanelCard
          title="近期开课活动"
          :subtitle="`${dateTime(dashboard.generatedAt)} 更新`"
        >
          <VChart class="activity-chart" :option="activityOption" autoresize />
        </PanelCard>

        <PanelCard title="风险构成" :subtitle="`平均风险 ${percent(dashboard.averageRiskProbability)}`">
          <VChart class="compact-chart" :option="riskOption" autoresize />
        </PanelCard>

        <PanelCard title="学习行为分布" subtitle="按事件量排序">
          <VChart class="compact-chart" :option="behaviorOption" autoresize />
        </PanelCard>

        <PanelCard title="薄弱知识点" subtitle="按平均掌握度排序">
          <ol class="weak-skills">
            <li v-for="(skill, index) in dashboard.weakSkills.slice(0, 6)" :key="skill.skillId">
              <span class="weak-skills__rank">{{ index + 1 }}</span>
              <div class="weak-skills__meta">
                <strong>{{ skill.skillName }}</strong>
                <span>{{ skill.affectedStudentCount }} 名学生受影响</span>
              </div>
              <div class="weak-skills__value">
                <span>{{ percent(skill.averageMastery) }}</span>
                <i><b :style="{ width: percent(skill.averageMastery) }" /></i>
              </div>
            </li>
          </ol>
        </PanelCard>
      </section>

      <section class="dashboard-analysis">
        <PanelCard title="活跃时段热力图" subtitle="按星期与小时聚合学习活动">
          <VChart class="analysis-chart" :option="heatmapOption" autoresize />
        </PanelCard>
        <PanelCard title="掌握度与风险关系" subtitle="每个点代表一名学生">
          <VChart class="analysis-chart" :option="scatterOption" autoresize />
        </PanelCard>
      </section>

      <PanelCard
        class="student-panel"
        title="学生风险列表"
        :subtitle="`显示 ${filteredStudents.length} / ${dashboard.students.length} 名学生 · 点击行查看学情画像`"
        padding="flush"
      >
        <template #extra>
          <el-radio-group v-model="riskFilter" size="small" aria-label="风险筛选">
            <el-radio-button value="ALL">全部</el-radio-button>
            <el-radio-button value="HIGH">高</el-radio-button>
            <el-radio-button value="MEDIUM">中</el-radio-button>
            <el-radio-button value="LOW">低</el-radio-button>
          </el-radio-group>
        </template>

        <el-table
          :data="filteredStudents"
          height="430"
          row-key="studentId"
          class="student-table"
          @row-click="(row: { studentId: string }) => openStudent(row.studentId)"
        >
          <el-table-column prop="displayName" label="学生" min-width="170">
            <template #default="{ row }">
              <button class="student-link" @click.stop="openStudent(row.studentId)">
                <span>{{ row.displayName }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="风险层级" width="110">
            <template #default="{ row }"><RiskTag :band="row.riskBand" compact /></template>
          </el-table-column>
          <el-table-column
            label="风险概率"
            width="115"
            sortable
            :sort-method="(a: { riskProbability: number }, b: { riskProbability: number }) => a.riskProbability - b.riskProbability"
          >
            <template #default="{ row }"><strong>{{ percent(row.riskProbability) }}</strong></template>
          </el-table-column>
          <el-table-column label="平均掌握" width="105" sortable prop="averageMastery">
            <template #default="{ row }">{{ percent(row.averageMastery) }}</template>
          </el-table-column>
          <el-table-column label="参与度" width="92">
            <template #default="{ row }">{{ percent(row.engagementScore) }}</template>
          </el-table-column>
          <el-table-column label="行为特征" width="110">
            <template #default="{ row }">{{ profileLabels[row.behaviorProfile] ?? row.behaviorProfile }}</template>
          </el-table-column>
          <el-table-column prop="answerCount" label="答题" width="76" sortable />
          <el-table-column label="最近活动" min-width="145">
            <template #default="{ row }">{{ dateTime(row.lastActivityAt) }}</template>
          </el-table-column>
          <el-table-column width="54" align="right">
            <template #default><ArrowRight :size="16" class="row-arrow" /></template>
          </el-table-column>
        </el-table>
      </PanelCard>
    </template>

  </div>
</template>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.dashboard-overview {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(250px, 0.75fr);
  gap: 14px;
  margin: 14px 0;
}

.dashboard-analysis { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.compact-chart { width: 100%; height: 260px; }
.analysis-chart { width: 100%; height: 320px; }

.activity-chart {
  width: 100%;
  height: 320px;
}

.weak-skills {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.weak-skills li {
  min-height: 56px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 96px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--border);
}

.weak-skills li:last-child {
  border-bottom: 0;
}

.weak-skills__rank {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--accent-dark);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 700;
}

.weak-skills__meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.weak-skills__meta strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  white-space: nowrap;
}

.weak-skills__meta span {
  color: var(--muted);
  font-size: 11px;
}

.weak-skills__value {
  display: grid;
  grid-template-columns: 40px 1fr;
  align-items: center;
  gap: 8px;
}

.weak-skills__value > span {
  color: var(--ink);
  font-size: 12px;
  font-weight: 650;
  text-align: right;
}

.weak-skills__value i {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--border);
}

.weak-skills__value b {
  height: 100%;
  display: block;
  border-radius: 999px;
  background: linear-gradient(90deg, #2dd4bf, #0d9488);
}

.student-panel {
  margin-top: 0;
}

.student-table {
  cursor: pointer;
}

.student-link {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0;
  border: 0;
  color: var(--ink);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.student-link span {
  font-size: 13px;
  font-weight: 650;
}

.row-arrow {
  color: var(--subtle);
}

@media (max-width: 980px) {
  .dashboard-overview, .dashboard-analysis {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .activity-chart {
    height: 270px;
  }
}
</style>
