<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Archive, ArrowRight, Pencil, Plus, RefreshCw, Send } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { useSessionStore } from '@/app/stores/session'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { CourseMutationRequest, LmsContentStatus, LmsCourse } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const session = useSessionStore()
const router = useRouter()
const courses = ref<LmsCourse[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)

const form = reactive<CourseMutationRequest>({
  code: '',
  title: '',
  termLabel: null,
  credits: 3,
  description: '',
  startsOn: new Date().toISOString().slice(0, 10),
})

const publishedCount = computed(() => courses.value.filter((course) => course.status === 'PUBLISHED').length)
const studentTotal = computed(() => courses.value.reduce((sum, course) => sum + course.enrolledStudentCount, 0))

function statusLabel(status: LmsContentStatus) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }[status]
}

function statusType(status: LmsContentStatus) {
  return status === 'PUBLISHED' ? 'success' : status === 'DRAFT' ? 'warning' : 'info'
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await api.managedCourses()
    courses.value = result.items
  } catch (reason) {
    error.value = userErrorMessage(reason, '课程列表读取失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    code: '',
    title: '',
    termLabel: null,
    credits: 3,
    description: '',
    startsOn: new Date().toISOString().slice(0, 10),
  })
  dialogOpen.value = true
}

function openEdit(course: LmsCourse) {
  editingId.value = course.courseId
  Object.assign(form, {
    code: course.code,
    title: course.title,
    termLabel: course.termLabel,
    credits: course.credits,
    description: course.description,
    startsOn: course.startsOn,
  })
  dialogOpen.value = true
}

async function save() {
  if (!form.code.trim() || !form.title.trim() || !form.startsOn || form.credits < 0.5) {
    ElMessage.warning('课程编号、名称、学分和开始日期为必填项')
    return
  }
  saving.value = true
  try {
    if (editingId.value) await api.updateManagedCourse(editingId.value, { ...form })
    else await api.createManagedCourse({ ...form })
    dialogOpen.value = false
    await Promise.all([load(), session.loadCourses()])
    ElMessage.success(editingId.value ? '课程信息已更新' : '草稿课程已创建')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '课程保存失败'))
  } finally {
    saving.value = false
  }
}

function enter(courseId: string) {
  void router.push(`/teacher/courses/${courseId}/dashboard`)
}

async function changeStatus(course: LmsCourse, status: LmsContentStatus) {
  try {
    const prompt = await ElMessageBox.prompt(
      status === 'ARCHIVED' ? '归档原因将写入课程状态历史。' : '填写本次状态变更说明。',
      status === 'PUBLISHED' ? '发布课程' : status === 'ARCHIVED' ? '归档课程' : '恢复为草稿',
      { inputPlaceholder: '状态变更原因', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空' },
    )
    const reason = prompt.value.trim()
    const preview = await businessApi.previewCourseStatus(course.courseId, course.status, status, reason)
    const impact = [
      `${preview.affectedSectionCount} 个章节`,
      `${preview.affectedLessonCount} 个课时`,
      `${preview.affectedAssessmentCount} 项考核`,
      `${preview.affectedStudentCount} 名学生`,
      `${preview.affectedSubmissionCount} 次提交`,
    ].join('、')
    await ElMessageBox.confirm(`本次变更将影响 ${impact}。确认继续？`, '影响预览', {
      type: status === 'ARCHIVED' ? 'warning' : 'info',
      confirmButtonText: '确认变更',
      cancelButtonText: '取消',
    })
    await businessApi.changeCourseStatus(course.courseId, course.status, status, reason)
    await Promise.all([load(), session.loadCourses()])
    ElMessage.success(status === 'PUBLISHED' ? '课程已发布' : status === 'ARCHIVED' ? '课程已归档' : '课程已恢复为草稿')
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, '课程状态更新失败'))
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="课程管理"
    description="创建课程、维护基本信息并控制学生可见状态。"
  >
    <template #actions>
      <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
    </template>
  </PageHeader>

  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else>
    <div class="lms-summary-strip">
      <div><span>课程总数</span><strong>{{ courses.length }}</strong></div>
      <div><span>已发布</span><strong>{{ publishedCount }}</strong></div>
      <div><span>在读学生</span><strong>{{ studentTotal }}</strong></div>
    </div>

    <section class="lms-table-section">
      <el-table :data="courses" row-key="courseId">
        <el-table-column label="课程" min-width="260">
          <template #default="{ row }">
            <div class="lms-primary-cell">
              <strong>{{ row.title }}</strong>
              <span>{{ row.code }}<template v-if="row.termLabel"> · {{ row.termLabel }}</template> · {{ row.credits }} 学分</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sectionCount" label="章节" width="76" />
        <el-table-column prop="assessmentCount" label="考核" width="76" />
        <el-table-column prop="enrolledStudentCount" label="学生" width="76" />
        <el-table-column prop="instructorName" label="任课教师" width="110" />
        <el-table-column prop="startsOn" label="开始日期" width="120" />
        <el-table-column label="操作" width="330" align="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="ArrowRight" @click="enter(row.courseId)">进入课程</el-button>
            <el-button v-if="row.assignmentRole === 'OWNER'" text :icon="Pencil" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="row.assignmentRole === 'OWNER' && row.status !== 'PUBLISHED'"
              text
              type="primary"
              :icon="Send"
              @click="changeStatus(row, 'PUBLISHED')"
            >发布</el-button>
            <el-button
              v-if="row.assignmentRole === 'OWNER' && row.status !== 'ARCHIVED'"
              text
              type="danger"
              :icon="Archive"
              @click="changeStatus(row, 'ARCHIVED')"
            >归档</el-button>
            <el-button
              v-if="row.assignmentRole === 'OWNER' && row.status === 'ARCHIVED'"
              text
              :icon="Pencil"
              @click="changeStatus(row, 'DRAFT')"
            >恢复草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!courses.length" class="lms-empty-state">尚未创建课程</div>
    </section>
  </template>

  <el-dialog v-model="dialogOpen" :title="editingId ? '编辑课程' : '新建课程'" width="min(620px, 94vw)">
    <el-form label-position="top">
      <div class="lms-form-grid lms-form-grid--two">
        <el-form-item label="课程编号"><el-input v-model="form.code" placeholder="例如 ET-101" /></el-form-item>
        <el-form-item label="课程学分"><el-input-number v-model="form.credits" :min="0.5" :max="20" :step="0.5" :precision="1" /></el-form-item>
      </div>
      <el-form-item label="课程名称"><el-input v-model="form.title" /></el-form-item>
      <el-form-item label="学期（选填）"><el-input v-model="form.termLabel" clearable placeholder="例如 2026 春，仅用于展示" /></el-form-item>
      <el-form-item label="课程简介"><el-input v-model="form.description" type="textarea" :rows="4" /></el-form-item>
      <el-form-item label="开始日期"><el-date-picker v-model="form.startsOn" type="date" value-format="YYYY-MM-DD" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogOpen = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存课程</el-button>
    </template>
  </el-dialog>
</template>
