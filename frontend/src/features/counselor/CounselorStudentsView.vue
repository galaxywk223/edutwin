<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowRight, BookOpen, RefreshCw, Search, ShieldCheck, TriangleAlert, Users } from '@lucide/vue'
import { useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { CounselorStudentPage } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const router = useRouter()
const students = ref<CounselorStudentPage | null>(null)
const loading = ref(true)
const error = ref('')
const currentPage = ref(1)
const pageSize = ref(25)
const filters = reactive({ query: '', cohortYear: undefined as number | undefined, className: '', riskBand: '' })

const courseTotal = computed(() => students.value?.items.reduce((sum, item) => sum + item.courseCount, 0) ?? 0)
const highRiskTotal = computed(() => students.value?.items.reduce((sum, item) => sum + item.highRiskCourseCount, 0) ?? 0)

function percentage(value: number | null) {
  return value == null ? '-' : `${Math.round(value)}%`
}

async function load(resetPage = false) {
  if (resetPage) currentPage.value = 1
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ page: String(currentPage.value - 1), size: String(pageSize.value) })
  if (filters.query.trim()) params.set('query', filters.query.trim())
  if (filters.cohortYear) params.set('cohortYear', String(filters.cohortYear))
  if (filters.className.trim()) params.set('className', filters.className.trim())
  if (filters.riskBand) params.set('riskBand', filters.riskBand)
  try {
    students.value = await api.counselorStudents(params)
  } catch (cause) {
    error.value = userErrorMessage(cause, '学生学业数据读取失败。')
  } finally {
    loading.value = false
  }
}

function openStudent(studentId: string) {
  void router.push(`/counselor/students/${studentId}`)
}

onMounted(() => void load())
</script>

<template>
  <div>
    <PageHeader
      title="学生学业"
      description="按已分配年级与班级查看学生跨课程学习统计。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load()">刷新</el-button>
      </template>
    </PageHeader>

    <div class="readonly-notice">
      <ShieldCheck :size="18" />
      <span>只读视图不展示原始答题内容，不提供课程、成绩或学习记录修改操作。</span>
    </div>

    <section class="filter-bar counselor-filter-bar">
      <el-input v-model="filters.query" clearable placeholder="姓名、学号或账号" @keyup.enter="load(true)" />
      <el-input-number v-model="filters.cohortYear" :controls="false" :min="2000" :max="2100" placeholder="入学年份" />
      <el-input v-model="filters.className" clearable placeholder="班级名称" @keyup.enter="load(true)" />
      <el-select v-model="filters.riskBand" clearable placeholder="风险状态">
        <el-option label="高风险" value="HIGH" />
        <el-option label="中风险" value="MEDIUM" />
        <el-option label="低风险" value="LOW" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
    </section>

    <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load()" />
    <template v-else-if="students">
      <section class="stat-grid">
        <StatCard label="范围内学生" :value="students.total" caption="当前筛选结果" :icon="Users" tone="info" />
        <StatCard label="本页课程关系" :value="courseTotal" caption="学生-课程计数" :icon="BookOpen" />
        <StatCard label="本页高风险课程" :value="highRiskTotal" caption="需优先关注" :icon="TriangleAlert" tone="warning" />
        <StatCard label="当前页容量" :value="pageSize" caption="分页大小" tone="success" />
      </section>

      <section class="lms-table-section counselor-table">
        <el-table :data="students.items" row-key="studentId" @row-click="(row: { studentId: string }) => openStudent(row.studentId)">
          <el-table-column label="学生" min-width="190">
            <template #default="{ row }">
              <div class="lms-primary-cell">
                <strong>{{ row.displayName }}</strong>
                <span>{{ row.studentNumber }} · {{ row.username }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="班级" min-width="210">
            <template #default="{ row }">
              <div class="lms-primary-cell">
                <strong>{{ row.className }}</strong>
                <span>{{ row.cohortYear }} 级 · {{ row.major }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="courseCount" label="课程" width="76" />
          <el-table-column label="学分" width="86">
            <template #default="{ row }">{{ row.totalCredits }}</template>
          </el-table-column>
          <el-table-column label="平均成绩" width="100">
            <template #default="{ row }">{{ percentage(row.averageScorePercentage) }}</template>
          </el-table-column>
          <el-table-column label="平均掌握度" width="110">
            <template #default="{ row }">
              {{ percentage(row.averageMastery == null ? null : row.averageMastery * 100) }}
            </template>
          </el-table-column>
          <el-table-column label="高风险" width="88">
            <template #default="{ row }">
              <el-tag :type="row.highRiskCourseCount ? 'danger' : 'success'">{{ row.highRiskCourseCount }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="ArrowRight" @click.stop="openStudent(row.studentId)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="lms-pagination">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[25, 50, 100]"
            :total="students.total"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="load(true)"
          />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.counselor-filter-bar {
  grid-template-columns: minmax(180px, 1.5fr) 130px minmax(150px, 1fr) 130px auto;
}

.stat-grid {
  margin-bottom: 16px;
}

.counselor-table :deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 900px) {
  .counselor-filter-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .counselor-filter-bar {
    grid-template-columns: 1fr;
  }
}
</style>
