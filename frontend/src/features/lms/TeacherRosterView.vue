<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw, Search, UserRoundCheck } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { RosterList, RosterStudent } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const { courseId } = useCourseContext()
const roster = ref<RosterList | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const search = ref('')
const page = ref(1)
const pageSize = 30
const changing = ref(new Set<string>())

const filtered = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return roster.value?.items ?? []
  return (roster.value?.items ?? []).filter((student) =>
    `${student.displayName} ${student.studentNumber} ${student.major} ${student.className}`.toLowerCase().includes(needle),
  )
})
const pageItems = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = null
  try {
    roster.value = await api.roster(courseId.value)
  } catch (reason) {
    error.value = userErrorMessage(reason, '学生名单读取失败')
  } finally {
    loading.value = false
  }
}

async function toggle(student: RosterStudent) {
  if (!courseId.value) return
  changing.value.add(student.studentId)
  try {
    let reason = '加入课程名单'
    if (!student.enrolled) {
      const prompt = await ElMessageBox.prompt(
        '移出原因会写入名单变更历史，已有学习与提交记录仍会保留。',
        '移出课程名单',
        { inputPlaceholder: '填写移出原因', inputValidator: (value) => Boolean(value.trim()) || '移出原因不能为空' },
      )
      reason = prompt.value.trim()
      await ElMessageBox.confirm('确认移出该学生？该操作将立即撤销课程访问权限。', '确认名单变更', {
        type: 'warning', confirmButtonText: '确认移出', cancelButtonText: '取消',
      })
    }
    await businessApi.updateEnrollment(courseId.value, student.studentId, student.enrolled, reason)
    if (roster.value) {
      roster.value.enrolledCount += student.enrolled ? 1 : -1
      student.enrollmentStatus = student.enrolled ? 'ACTIVE' : 'WITHDRAWN'
    }
    ElMessage.success(student.enrolled ? '学生已加入课程' : '学生已移出课程')
  } catch (reason) {
    student.enrolled = !student.enrolled
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '名单更新失败'))
  } finally {
    changing.value.delete(student.studentId)
  }
}

watch(search, () => { page.value = 1 })
watch(courseId, load)
onMounted(load)
</script>

<template>
  <PageHeader
    title="学生名单"
    description="从现有学生账号中维护当前课程名单，名单变化立即成为服务端权限事实。"
  >
    <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
  </PageHeader>

  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else-if="roster">
    <div class="lms-summary-strip">
      <div><span>全部学生</span><strong>{{ roster.total }}</strong></div>
      <div><span>课程在读</span><strong>{{ roster.enrolledCount }}</strong></div>
      <div><span>当前结果</span><strong>{{ filtered.length }}</strong></div>
    </div>

    <section class="lms-table-section">
      <div class="lms-table-toolbar">
        <el-input v-model="search" :prefix-icon="Search" clearable placeholder="搜索姓名、学号、专业或班级" />
        <span><UserRoundCheck :size="16" /> 开关用于加入或移出课程</span>
      </div>
      <el-table :data="pageItems" row-key="studentId">
        <el-table-column label="学生" min-width="230">
          <template #default="{ row }"><div class="lms-primary-cell"><strong>{{ row.displayName }}</strong><span>{{ row.studentNumber }} · {{ row.className }}</span></div></template>
        </el-table-column>
        <el-table-column prop="major" label="专业" min-width="180" />
        <el-table-column prop="cohortYear" label="年级" width="84" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="row.enrolled ? 'success' : 'info'">{{ row.enrolled ? '在读' : '未加入' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="课程权限" width="140" align="right">
          <template #default="{ row }">
            <el-switch v-model="row.enrolled" :loading="changing.has(row.studentId)" @change="toggle(row)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="lms-pagination"><el-pagination v-model:current-page="page" layout="prev, pager, next" :page-size="pageSize" :total="filtered.length" /></div>
    </section>
  </template>
</template>
