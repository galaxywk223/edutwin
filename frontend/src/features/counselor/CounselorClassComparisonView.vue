<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BarChart3, RefreshCw } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { ClassComparisonResult } from '@/shared/types/business'
import { userErrorMessage } from '@/shared/utils/displayText'

use([CanvasRenderer, BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent])

const classOptions = ref<string[]>([])
const selected = ref<string[]>([])
const result = ref<ClassComparisonResult | null>(null)
const loading = ref(false)
const error = ref('')
const period = ref<'7D' | '30D' | 'TERM'>('30D')
const canCompare = computed(() => selected.value.length >= 2 && selected.value.length <= 4)

function percent(value: number | null, ratio = false) {
  if (value == null) return '暂无数据'
  return `${(ratio ? value * 100 : value).toFixed(1)}%`
}

async function loadOptions() {
  try {
    const params = new URLSearchParams({ page: '0', size: '100' })
    const page = await api.counselorStudents(params)
    classOptions.value = [...new Set(page.items.map((item) => item.className).filter(Boolean))].sort()
    if (!selected.value.length) selected.value = classOptions.value.slice(0, Math.min(2, classOptions.value.length))
    if (canCompare.value) await compare()
  } catch (cause) {
    error.value = userErrorMessage(cause, '班级范围读取失败。')
  }
}

function onSelectionChange(values: string[]) {
  if (values.length <= 4) return
  selected.value = values.slice(0, 4)
  ElMessage.warning('一次最多对比四个班级')
}

async function compare() {
  if (!canCompare.value) {
    ElMessage.warning('请选择二至四个班级')
    return
  }
  loading.value = true
  error.value = ''
  try { result.value = await businessApi.compareClasses(selected.value, period.value) }
  catch (cause) { error.value = userErrorMessage(cause, '班级对比读取失败。') }
  finally { loading.value = false }
}

const outcomeOption = computed(() => ({
  tooltip: { trigger: 'axis' }, legend: { top: 0, textStyle: { color: '#6b7280', fontSize: 11 } },
  grid: { top: 38, right: 20, bottom: 42, left: 48 },
  xAxis: { type: 'category', data: result.value?.classes.map((item) => item.className) ?? [], axisLabel: { color: '#6b7280', interval: 0 } },
  yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [
    { name: '平均成绩', type: 'bar', data: result.value?.classes.map((item) => item.averageScorePercentage ?? 0) ?? [], itemStyle: { color: '#2563eb', borderRadius: [4, 4, 0, 0] } },
    { name: '平均掌握度', type: 'bar', data: result.value?.classes.map((item) => (item.averageMastery ?? 0) * 100) ?? [], itemStyle: { color: '#0d9488', borderRadius: [4, 4, 0, 0] } },
    { name: '计划完成率', type: 'bar', data: result.value?.classes.map((item) => (item.planCompletionRate ?? 0) * 100) ?? [], itemStyle: { color: '#7c3aed', borderRadius: [4, 4, 0, 0] } },
  ],
}))

const riskOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } }, legend: { top: 0, textStyle: { color: '#6b7280', fontSize: 11 } },
  grid: { top: 38, right: 20, bottom: 42, left: 48 },
  xAxis: { type: 'category', data: result.value?.classes.map((item) => item.className) ?? [], axisLabel: { color: '#6b7280' } },
  yAxis: { type: 'value', axisLabel: { color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [
    { name: '低风险', type: 'bar', stack: 'risk', data: result.value?.classes.map((item) => item.lowRiskCourseCount) ?? [], itemStyle: { color: '#0d9488' } },
    { name: '中风险', type: 'bar', stack: 'risk', data: result.value?.classes.map((item) => item.mediumRiskCourseCount) ?? [], itemStyle: { color: '#d97706' } },
    { name: '高风险', type: 'bar', stack: 'risk', data: result.value?.classes.map((item) => item.highRiskCourseCount) ?? [], itemStyle: { color: '#dc2626' } },
  ],
}))

const trendOption = computed(() => {
  const weeks = [...new Set(result.value?.riskTrend.map((item) => item.weekStart) ?? [])]
  return {
    tooltip: { trigger: 'axis' }, legend: { top: 0, textStyle: { color: '#6b7280', fontSize: 11 } },
    grid: { top: 38, right: 24, bottom: 42, left: 48 },
    xAxis: { type: 'category', data: weeks.map((week) => week.slice(5)), axisLabel: { color: '#9ca3af' } },
    yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: (value: number) => `${value * 100}%`, color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: selected.value.map((className) => ({ name: className, type: 'line', smooth: 0.2, symbolSize: 6, data: weeks.map((week) => result.value?.riskTrend.find((item) => item.className === className && item.weekStart === week)?.averageRiskProbability ?? null) })),
  }
})

onMounted(loadOptions)
</script>

<template>
  <div>
    <PageHeader title="班级学习对比" description="在授权范围内并列比较二至四个班级的学习结果与课程学习风险。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="compare">刷新</el-button></template>
    </PageHeader>
    <section class="comparison-picker">
      <el-select v-model="selected" multiple collapse-tags :max-collapse-tags="4" placeholder="选择二至四个班级" @change="onSelectionChange">
        <el-option v-for="name in classOptions" :key="name" :label="name" :value="name" :disabled="selected.length >= 4 && !selected.includes(name)" />
      </el-select>
      <el-button type="primary" :icon="BarChart3" :disabled="!canCompare" @click="compare">开始对比</el-button>
      <el-segmented v-model="period" :options="[{ label: '近 7 天', value: '7D' }, { label: '近 30 天', value: '30D' }, { label: '本学期', value: 'TERM' }]" @change="compare" />
      <span>{{ selected.length }} / 4 个班级</span>
    </section>
    <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="compare" />
    <template v-else-if="result">
      <section class="comparison-grid">
        <article v-for="item in result.classes" :key="item.className" class="comparison-card">
          <header><div><span>班级</span><h2>{{ item.className }}</h2></div><strong>{{ item.studentCount }}<small>人</small></strong></header>
          <dl>
            <div><dt>平均成绩</dt><dd>{{ percent(item.averageScorePercentage) }}</dd></div>
            <div><dt>平均掌握度</dt><dd>{{ percent(item.averageMastery, true) }}</dd></div>
            <div><dt>计划完成率</dt><dd>{{ percent(item.planCompletionRate, true) }}</dd></div>
            <div><dt>平均风险概率</dt><dd>{{ percent(item.averageRiskProbability, true) }}</dd></div>
          </dl>
          <div class="risk-counts"><span>中风险课程 <b>{{ item.mediumRiskCourseCount }}</b></span><span>高风险课程 <b>{{ item.highRiskCourseCount }}</b></span></div>
        </article>
      </section>
      <section class="comparison-visuals">
        <article><header><h2>学习结果对比</h2><span>成绩、掌握度与计划完成率</span></header><VChart class="comparison-chart" :option="outcomeOption" autoresize /></article>
        <article><header><h2>风险构成</h2><span>按课程学习关系统计</span></header><VChart class="comparison-chart" :option="riskOption" autoresize /></article>
        <article class="comparison-visuals__wide"><header><h2>风险变化趋势</h2><span>按周聚合历史画像</span></header><VChart class="comparison-chart" :option="trendOption" autoresize /></article>
      </section>
      <section class="lms-table-section comparison-table">
        <el-table :data="result.classes" row-key="className">
          <el-table-column prop="className" label="班级" min-width="160" fixed />
          <el-table-column prop="studentCount" label="学生" width="76" />
          <el-table-column prop="activeCourseEnrollments" label="在读课程关系" width="120" />
          <el-table-column label="平均成绩" width="105"><template #default="{ row }">{{ percent(row.averageScorePercentage) }}</template></el-table-column>
          <el-table-column label="平均掌握度" width="115"><template #default="{ row }">{{ percent(row.averageMastery, true) }}</template></el-table-column>
          <el-table-column label="平均风险概率" width="125"><template #default="{ row }">{{ percent(row.averageRiskProbability, true) }}</template></el-table-column>
          <el-table-column prop="lowRiskCourseCount" label="低风险课程" width="110" />
          <el-table-column prop="mediumRiskCourseCount" label="中风险课程" width="110" />
          <el-table-column prop="highRiskCourseCount" label="高风险课程" width="110" />
          <el-table-column label="计划完成率" width="115"><template #default="{ row }">{{ percent(row.planCompletionRate, true) }}</template></el-table-column>
        </el-table>
      </section>
    </template>
    <div v-else class="lms-empty-state comparison-empty">选择二至四个班级开始对比</div>
  </div>
</template>

<style scoped>
.comparison-picker { display: grid; grid-template-columns: minmax(260px, 1fr) auto auto auto; align-items: center; gap: 10px; margin-bottom: 16px; padding: 14px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }.comparison-picker > span { color: var(--muted); font-size: 11px; }
.comparison-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.comparison-card { min-width: 0; padding: 16px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }.comparison-card header { display: flex; justify-content: space-between; gap: 10px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }.comparison-card header span { color: var(--muted); font-size: 10px; }.comparison-card h2 { overflow-wrap: anywhere; margin: 3px 0 0; font-size: 15px; }.comparison-card header > strong { color: var(--accent-dark); font-size: 24px; }.comparison-card header small { margin-left: 2px; font-size: 10px; }
.comparison-card dl { display: grid; gap: 8px; margin: 13px 0; }.comparison-card dl > div { display: flex; justify-content: space-between; gap: 8px; }.comparison-card dt { color: var(--muted); font-size: 11px; }.comparison-card dd { margin: 0; color: var(--ink-strong); font-size: 12px; font-weight: 650; }
.risk-counts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px; }.risk-counts span { padding: 7px; border-radius: 4px; color: var(--warning); background: var(--warning-soft); font-size: 10px; }.risk-counts b { display: block; margin-top: 2px; font-size: 16px; }
.comparison-empty { min-height: 240px; }.comparison-table { overflow-x: auto; }
.comparison-visuals { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.comparison-visuals article { min-width: 0; padding: 14px 16px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }
.comparison-visuals__wide { grid-column: 1 / -1; }
.comparison-visuals header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.comparison-visuals h2 { margin: 0; font-size: 14px; }.comparison-visuals header span { color: var(--muted); font-size: 10px; }
.comparison-chart { width: 100%; height: 290px; }
@media (max-width: 1100px) { .comparison-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .comparison-picker { grid-template-columns: 1fr; }.comparison-grid, .comparison-visuals { grid-template-columns: 1fr; }.comparison-visuals__wide { grid-column: auto; } }
</style>
