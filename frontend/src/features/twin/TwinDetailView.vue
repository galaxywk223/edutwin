<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BarChart, HeatmapChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  ArrowLeft,
  Gauge,
  Sparkles,
  Target,
  TrendingUp,
} from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { useCourseContext } from '@/app/composables/useCourseContext'
import { useSessionStore } from '@/app/stores/session'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import RiskTag from '@/shared/components/RiskTag.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { AnalyticsPeriod, Diagnosis, StudentAnalytics, TwinHistory, TwinState } from '@/shared/types/api'
import { diagnosisDisplay, riskFeatureLabel, userErrorMessage } from '@/shared/utils/displayText'
import { dateTime, number, percent } from '@/shared/utils/format'

use([CanvasRenderer, LineChart, BarChart, HeatmapChart, GridComponent, TooltipComponent, LegendComponent, VisualMapComponent])

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const { courseId } = useCourseContext()
const twin = ref<TwinState | null>(null)
const history = ref<TwinHistory | null>(null)
const diagnosis = ref<Diagnosis | null>(null)
const activeTab = ref('current')
const loading = ref(true)
const error = ref('')
const diagnosisLoading = ref(false)
const diagnosisOpen = ref(false)
const analytics = ref<StudentAnalytics | null>(null)
const period = ref<AnalyticsPeriod>('30D')

const behaviorLabels: Record<string, string> = {
  COURSE_ACCESS: '进入课程', LESSON_VIEW: '查看课件', VIDEO_PROGRESS: '视频学习',
  RESOURCE_VIEW: '查看资源', RESOURCE_DOWNLOAD: '下载资源', DISCUSSION_VIEW: '讨论区',
  PRACTICE_START: '开始练习', PRACTICE_SUBMIT: '提交练习',
  ASSESSMENT_OPEN: '打开测验', ASSESSMENT_SUBMIT: '提交测验',
}

const studentId = computed(() =>
  typeof route.params.studentId === 'string' ? route.params.studentId : session.user?.userId ?? '',
)
const isTeacher = computed(() => session.user?.role === 'TEACHER')
const averageMastery = computed(() => {
  const skills = twin.value?.mastery ?? []
  return skills.length ? skills.reduce((sum, skill) => sum + skill.probability, 0) / skills.length : 0
})
const displayedDiagnosis = computed(() =>
  diagnosis.value ? diagnosisDisplay(diagnosis.value, twin.value) : null,
)

const historyOption = computed(() => {
  const ordered = [...(history.value?.items ?? [])].reverse()
  return {
    animationDuration: 450,
    grid: { top: 36, right: 28, bottom: 34, left: 46 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 8, textStyle: { color: '#6b7280', fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: ordered.map((item) => `版本 ${item.snapshotVersion}`),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#e5e7eb' } },
      axisTick: { show: false },
      axisLabel: { color: '#9ca3af', fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { formatter: (value: number) => `${value * 100}%`, color: '#9ca3af', fontSize: 10 },
    },
    series: [
      {
        name: '平均掌握度',
        type: 'line',
        smooth: 0.25,
        data: ordered.map((item) =>
          item.mastery.reduce((sum, skill) => sum + skill.probability, 0) / item.mastery.length,
        ),
        symbolSize: 6,
        lineStyle: { color: '#0d9488', width: 2.5 },
        itemStyle: { color: '#0d9488' },
      },
      {
        name: '风险概率',
        type: 'line',
        smooth: 0.25,
        data: ordered.map((item) => item.risk.probability),
        symbolSize: 6,
        lineStyle: { color: '#dc2626', width: 2 },
        itemStyle: { color: '#dc2626' },
      },
      {
        name: '下一题正确率',
        type: 'line',
        smooth: 0.25,
        data: ordered.map((item) => item.nextCorrectProbability),
        symbolSize: 5,
        lineStyle: { color: '#d97706', width: 1.5, type: 'dashed' },
        itemStyle: { color: '#d97706' },
      },
    ],
  }
})

const behaviorOption = computed(() => {
  const values = [...(analytics.value?.behaviorDistribution ?? [])].slice(0, 8).reverse()
  return {
    grid: { top: 12, right: 28, bottom: 28, left: 88 }, tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: { color: '#9ca3af' } },
    yAxis: { type: 'category', data: values.map((item) => behaviorLabels[item.behaviorType] ?? item.behaviorType), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#6b7280', fontSize: 10 } },
    series: [{ type: 'bar', data: values.map((item) => item.eventCount), barMaxWidth: 15, itemStyle: { color: '#0d9488', borderRadius: [0, 4, 4, 0] } }],
  }
})

const heatmapOption = computed(() => ({
  tooltip: { formatter: (params: { value: number[] }) => `周${'一二三四五六日'[params.value[1]]} ${params.value[0]}:00<br/>${params.value[2]} 次活动` },
  grid: { top: 18, right: 24, bottom: 52, left: 44 },
  xAxis: { type: 'category', data: Array.from({ length: 24 }, (_, hour) => `${hour}`), splitArea: { show: true }, axisLabel: { interval: 2, color: '#9ca3af' } },
  yAxis: { type: 'category', data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'], splitArea: { show: true }, axisLabel: { color: '#6b7280' } },
  visualMap: { min: 0, max: Math.max(1, ...(analytics.value?.activityHeatmap.map((item) => item.eventCount) ?? [1])), orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: ['#ecfdf5', '#34d399', '#047857'] }, textStyle: { fontSize: 9, color: '#9ca3af' } },
  series: [{ type: 'heatmap', data: analytics.value?.activityHeatmap.map((item) => [item.hour, item.weekday - 1, item.eventCount]) ?? [] }],
}))

async function load() {
  if (!courseId.value || !studentId.value) return
  loading.value = true
  error.value = ''
  try {
    const requests = await Promise.all([
      api.twin(courseId.value, studentId.value),
      api.twinHistory(courseId.value, studentId.value, 30),
      isTeacher.value ? Promise.resolve(null) : api.studentAnalytics(courseId.value, period.value),
    ])
    ;[twin.value, history.value, analytics.value] = requests
  } catch (cause) {
    error.value = userErrorMessage(cause, '学情画像读取失败。')
  } finally {
    loading.value = false
  }
}

async function generateDiagnosis() {
  if (!courseId.value || !twin.value) return
  diagnosisLoading.value = true
  try {
    diagnosis.value = await api.diagnosis(courseId.value, studentId.value)
    diagnosisOpen.value = true
  } catch (cause) {
    error.value = userErrorMessage(cause, '诊断生成失败。')
  } finally {
    diagnosisLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="twin-page">
    <PageHeader
      title="学生学情画像"
      :description="`${isTeacher ? '所选学生' : '本人'} · 当前学习状态与历史变化。`"
    >
      <template #title>
        <div class="twin-title-row">
          <el-button
            v-if="isTeacher"
            text
            :icon="ArrowLeft"
            class="back-button"
            @click="router.push(courseId ? `/teacher/courses/${courseId}/dashboard` : '/teacher/courses')"
          >
            返回驾驶舱
          </el-button>
          <h1>学生学情画像</h1>
        </div>
      </template>
      <template #actions>
        <el-segmented v-if="!isTeacher" v-model="period" :options="[{ label: '近 7 天', value: '7D' }, { label: '近 30 天', value: '30D' }, { label: '本学期', value: 'TERM' }]" @change="load" />
        <el-button
          type="primary"
          :icon="Sparkles"
          :loading="diagnosisLoading"
          :disabled="!twin"
          @click="generateDiagnosis"
        >
          生成诊断
        </el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <template v-if="twin && history && !loading">
      <div class="twin-status-line">
        <RiskTag :band="twin.risk.riskBand" />
        <span>画像版本 {{ twin.snapshotVersion }} · {{ dateTime(twin.capturedAt) }}</span>
      </div>

      <section class="stat-grid">
        <StatCard
          label="平均掌握度"
          :value="percent(averageMastery)"
          caption="当前知识状态"
          :icon="Target"
          tone="success"
        />
        <StatCard
          label="下一题正确率"
          :value="percent(twin.nextCorrectProbability)"
          caption="当前学习预测"
          :icon="TrendingUp"
          tone="info"
        />
        <StatCard
          label="课程学习风险"
          :value="percent(twin.risk.probability)"
          caption="当前风险评估"
          :icon="Gauge"
          :tone="twin.risk.riskBand === 'HIGH' ? 'danger' : 'warning'"
        />
        <StatCard
          label="计划完成度"
          :value="percent(twin.planCompletionRate)"
          caption="当前规则计划"
          :icon="Sparkles"
        />
      </section>

      <PanelCard class="twin-tabs-card" padding="flush">
        <el-tabs v-model="activeTab" class="twin-tabs">
          <el-tab-pane label="当前画像" name="current">
            <section class="twin-current">
              <div class="mastery-panel">
                <div class="section-heading">
                  <h2>知识掌握向量</h2>
                  <span>{{ twin.mastery.length }} 个知识点</span>
                </div>
                <div class="mastery-list">
                  <div
                    v-for="skill in [...twin.mastery].sort((a, b) => a.probability - b.probability)"
                    :key="skill.skillId"
                    class="mastery-row"
                  >
                    <div>
                      <strong>{{ skill.skillName }}</strong>
                    </div>
                    <div class="mastery-row__meter">
                      <i>
                        <b
                          :class="{ 'mastery-row__low': skill.probability < 0.5 }"
                          :style="{ width: percent(skill.probability) }"
                        />
                      </i>
                      <strong>{{ percent(skill.probability) }}</strong>
                    </div>
                  </div>
                </div>
              </div>

              <aside class="twin-dimensions">
                <div class="section-heading"><h2>学情指标</h2></div>
                <div class="dimension-row">
                  <span>参与度</span><strong>{{ percent(twin.engagementScore) }}</strong>
                  <i><b :style="{ width: percent(twin.engagementScore) }" /></i>
                </div>
                <div class="dimension-row">
                  <span>持续稳定性</span><strong>{{ percent(twin.stabilityScore) }}</strong>
                  <i><b :style="{ width: percent(twin.stabilityScore) }" /></i>
                </div>
                <div class="dimension-row">
                  <span>风险中阈值</span><strong>{{ percent(twin.risk.mediumThreshold) }}</strong>
                  <i><b class="dimension-row__warning" :style="{ width: percent(twin.risk.mediumThreshold) }" /></i>
                </div>
                <div class="dimension-row">
                  <span>风险高阈值</span><strong>{{ percent(twin.risk.highThreshold) }}</strong>
                  <i><b class="dimension-row__danger" :style="{ width: percent(twin.risk.highThreshold) }" /></i>
                </div>
                <dl class="model-facts">
                  <dt>预测时间</dt><dd>{{ dateTime(twin.risk.predictedAt) }}</dd>
                </dl>
              </aside>
            </section>
          </el-tab-pane>

          <el-tab-pane :label="`变化趋势 (${history.total})`" name="history">
            <section class="history-panel">
              <div class="section-heading">
                <h2>学情变化趋势</h2>
                <span>按状态版本排序</span>
              </div>
              <VChart class="history-chart" :option="historyOption" autoresize />
              <el-table :data="history.items" height="310" row-key="snapshotVersion">
                <el-table-column prop="snapshotVersion" label="版本" width="76">
                  <template #default="{ row }">版本 {{ row.snapshotVersion }}</template>
                </el-table-column>
                <el-table-column label="捕获时间" min-width="140">
                  <template #default="{ row }">{{ dateTime(row.capturedAt) }}</template>
                </el-table-column>
                <el-table-column label="平均掌握" width="110">
                  <template #default="{ row }">
                    {{
                      percent(
                        row.mastery.reduce(
                          (sum: number, item: { probability: number }) => sum + item.probability,
                          0,
                        ) / row.mastery.length,
                      )
                    }}
                  </template>
                </el-table-column>
                <el-table-column label="下一题" width="92">
                  <template #default="{ row }">{{ percent(row.nextCorrectProbability) }}</template>
                </el-table-column>
                <el-table-column label="风险" width="100">
                  <template #default="{ row }"><RiskTag :band="row.risk.riskBand" compact /></template>
                </el-table-column>
              </el-table>
            </section>
          </el-tab-pane>

          <el-tab-pane v-if="!isTeacher" label="行为分析" name="behavior">
            <section v-if="analytics" class="behavior-panel">
              <div class="behavior-summary">
                <dl><dt>期间答题</dt><dd>{{ analytics.answerCount }}</dd></dl>
                <dl><dt>答题正确率</dt><dd>{{ percent(analytics.correctRate) }}</dd></dl>
                <dl><dt>活跃天数</dt><dd>{{ analytics.activeDays }}</dd></dl>
                <dl><dt>学习时长</dt><dd>{{ analytics.totalDurationMinutes }} 分钟</dd></dl>
              </div>
              <div class="behavior-grid">
                <section>
                  <div class="section-heading"><h2>学习行为构成</h2><span>按事件量排序</span></div>
                  <VChart class="behavior-chart" :option="behaviorOption" autoresize />
                </section>
                <section>
                  <div class="section-heading"><h2>活跃时段</h2><span>按星期与小时聚合</span></div>
                  <VChart class="behavior-chart" :option="heatmapOption" autoresize />
                </section>
              </div>
            </section>
          </el-tab-pane>
        </el-tabs>
      </PanelCard>
    </template>

    <el-drawer v-model="diagnosisOpen" title="结构化学习诊断" size="min(680px, 94vw)">
      <div v-if="diagnosis && displayedDiagnosis" class="diagnosis-drawer">
        <p class="diagnosis-summary">{{ displayedDiagnosis.summary }}</p>
        <div class="diagnosis-columns">
          <section>
            <h3>优势</h3>
            <ul><li v-for="item in displayedDiagnosis.strengths" :key="item">{{ item }}</li></ul>
          </section>
          <section>
            <h3>关注点</h3>
            <ul><li v-for="item in displayedDiagnosis.concerns" :key="item">{{ item }}</li></ul>
          </section>
        </div>
        <section>
          <h3>建议行动</h3>
          <ol><li v-for="item in displayedDiagnosis.recommendedActions" :key="item">{{ item }}</li></ol>
        </section>
        <section>
          <h3>风险影响因素</h3>
          <el-table :data="diagnosis.evidence" row-key="rank">
            <el-table-column prop="rank" label="#" width="44" />
            <el-table-column label="因素" min-width="170">
              <template #default="{ row }">{{ riskFeatureLabel(row.featureName) }}</template>
            </el-table-column>
            <el-table-column label="原始值" width="90">
              <template #default="{ row }">{{ number(row.rawValue) }}</template>
            </el-table-column>
            <el-table-column label="方向" width="100">
              <template #default="{ row }">
                <span :class="row.contribution > 0 ? 'evidence-up' : 'evidence-down'">
                  {{ row.contribution > 0 ? '增加风险' : '降低风险' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="影响值（SHAP）" width="125">
              <template #default="{ row }">{{ number(row.contribution) }}</template>
            </el-table-column>
          </el-table>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.twin-title-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.back-button {
  margin-left: -10px;
  color: var(--muted);
}

.twin-status-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
  color: var(--muted);
  font-size: 12px;
}

.twin-tabs-card {
  margin-top: 14px;
}

.twin-tabs {
  padding: 4px 4px 0;
}

.twin-tabs :deep(.el-tabs__header) {
  margin: 0 16px 0;
}

.twin-tabs :deep(.el-tabs__content) {
  padding: 8px 18px 18px;
}

.twin-current {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 0.72fr);
  gap: 28px;
  padding-top: 8px;
}

.mastery-list {
  max-height: 550px;
  overflow: auto;
  padding-right: 8px;
}

.mastery-row {
  min-height: 54px;
  display: grid;
  grid-template-columns: minmax(130px, 0.7fr) minmax(210px, 1.3fr);
  align-items: center;
  gap: 18px;
  border-bottom: 1px solid var(--border);
}

.mastery-row > div:first-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mastery-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  white-space: nowrap;
}

.mastery-row span {
  color: var(--subtle);
  font-size: 10px;
}

.mastery-row__meter {
  display: grid;
  grid-template-columns: minmax(80px, 1fr) 44px;
  align-items: center;
  gap: 12px;
}

.mastery-row__meter i,
.dimension-row i {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--border);
}

.mastery-row__meter b,
.dimension-row b {
  height: 100%;
  display: block;
  border-radius: 999px;
  background: linear-gradient(90deg, #2dd4bf, #0d9488);
}

.mastery-row__meter .mastery-row__low {
  background: linear-gradient(90deg, #fbbf24, #d97706);
}

.mastery-row__meter strong {
  text-align: right;
}

.twin-dimensions {
  padding-left: 24px;
  border-left: 1px solid var(--border);
}

.dimension-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 46px;
  gap: 9px;
  margin-bottom: 18px;
}

.dimension-row span {
  color: var(--muted);
  font-size: 12px;
}

.dimension-row strong {
  font-size: 12px;
  text-align: right;
}

.dimension-row i {
  grid-column: 1 / -1;
}

.dimension-row .dimension-row__warning {
  background: linear-gradient(90deg, #fbbf24, #d97706);
}

.dimension-row .dimension-row__danger {
  background: linear-gradient(90deg, #f87171, #dc2626);
}

.model-facts {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 10px;
  margin: 28px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.model-facts dt {
  color: var(--muted);
  font-size: 11px;
}

.model-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 11px;
  text-align: right;
}

.history-panel {
  padding-top: 8px;
}

.history-chart {
  width: 100%;
  height: 330px;
  margin-bottom: 16px;
}

.behavior-panel { padding-top: 8px; }
.behavior-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-bottom: 1px solid var(--border); }
.behavior-summary dl { margin: 0; padding: 12px 18px; border-right: 1px solid var(--border); }
.behavior-summary dl:last-child { border-right: 0; }
.behavior-summary dt { color: var(--muted); font-size: 11px; }
.behavior-summary dd { margin: 4px 0 0; color: var(--ink-strong); font-size: 20px; font-weight: 700; }
.behavior-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px; padding-top: 18px; }
.behavior-chart { width: 100%; height: 310px; }

.diagnosis-mode {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  color: var(--accent-dark);
  background: var(--surface-tint);
  border-radius: var(--radius-sm);
}

.diagnosis-mode div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.diagnosis-mode strong {
  font-size: 12px;
}

.diagnosis-mode span {
  color: var(--muted);
  font-size: 11px;
}

.diagnosis-summary {
  margin: 22px 0;
  font-size: 15px;
  line-height: 1.75;
}

.diagnosis-drawer section {
  margin-top: 24px;
}

.diagnosis-drawer h3 {
  margin: 0 0 10px;
  font-size: 13px;
}

.diagnosis-drawer ul,
.diagnosis-drawer ol {
  margin: 0;
  padding-left: 20px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}

.diagnosis-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.evidence-up {
  color: var(--danger);
}

.evidence-down {
  color: var(--success);
}

@media (max-width: 960px) {
  .twin-current {
    grid-template-columns: 1fr;
  }

  .behavior-grid { grid-template-columns: 1fr; }

  .twin-dimensions {
    padding: 20px 0 0;
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}

@media (max-width: 600px) {
  .mastery-row {
    grid-template-columns: 1fr;
    gap: 8px;
    padding: 12px 0;
  }

  .diagnosis-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .history-chart {
    height: 270px;
  }

  .behavior-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .behavior-summary dl:nth-child(2) { border-right: 0; }
}
</style>
