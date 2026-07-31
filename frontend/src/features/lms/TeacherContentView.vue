<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { Archive, BookOpen, ExternalLink, FilePlus2, Plus, RefreshCw, Send } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { CourseOutline, LmsContentStatus, LmsSection } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const { courseId } = useCourseContext()
const outline = ref<CourseOutline | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const sectionDialog = ref(false)
const lessonDialog = ref(false)
const targetSection = ref<LmsSection | null>(null)

const sectionForm = reactive({ title: '', description: '', status: 'DRAFT' as LmsContentStatus })
const lessonForm = reactive({
  title: '', summary: '', body: '', resourceUrl: '', status: 'DRAFT' as LmsContentStatus,
})

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = null
  try {
    outline.value = await api.managedOutline(courseId.value)
  } catch (reason) {
    error.value = userErrorMessage(reason, '课程内容读取失败')
  } finally {
    loading.value = false
  }
}

function openSection() {
  Object.assign(sectionForm, { title: '', description: '', status: 'DRAFT' })
  sectionDialog.value = true
}

function openLesson(section: LmsSection) {
  targetSection.value = section
  Object.assign(lessonForm, { title: '', summary: '', body: '', resourceUrl: '', status: 'DRAFT' })
  lessonDialog.value = true
}

async function saveSection() {
  if (!courseId.value || !sectionForm.title.trim()) return ElMessage.warning('章节名称不能为空')
  saving.value = true
  try {
    await api.createSection(courseId.value, {
      title: sectionForm.title,
      description: sectionForm.description,
      status: sectionForm.status === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT',
    })
    sectionDialog.value = false
    await load()
    ElMessage.success('章节已创建')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '章节保存失败'))
  } finally {
    saving.value = false
  }
}

async function saveLesson() {
  if (!targetSection.value || !lessonForm.title.trim() || !lessonForm.body.trim()) {
    return ElMessage.warning('课时名称和正文不能为空')
  }
  saving.value = true
  try {
    await api.createLesson(targetSection.value.sectionId, {
      title: lessonForm.title,
      summary: lessonForm.summary,
      body: lessonForm.body,
      resourceUrl: lessonForm.resourceUrl || null,
      status: lessonForm.status === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT',
    })
    lessonDialog.value = false
    await load()
    ElMessage.success('课时已创建')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '课时保存失败'))
  } finally {
    saving.value = false
  }
}

async function changeContentStatus(
  kind: 'section' | 'lesson',
  id: string,
  currentStatus: LmsContentStatus,
  targetStatus: LmsContentStatus,
) {
  const subject = kind === 'section' ? '章节' : '课时'
  const targetLabel = targetStatus === 'PUBLISHED' ? '发布' : targetStatus === 'DRAFT' ? '转为草稿' : '归档'
  try {
    const prompt = await ElMessageBox.prompt(`填写${subject}${targetLabel}原因。`, `${targetLabel}${subject}`, {
      inputPlaceholder: '状态变更原因',
      inputValidator: (value) => Boolean(value.trim()) || '原因不能为空',
    })
    await ElMessageBox.confirm(
      targetStatus === 'ARCHIVED' ? `归档后${subject}将不再出现在当前内容列表中。` : `确认将${subject}${targetLabel}？`,
      '确认状态变更',
      { type: targetStatus === 'ARCHIVED' ? 'warning' : 'info', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    if (kind === 'section') await businessApi.changeSectionStatus(id, currentStatus, targetStatus, prompt.value.trim())
    else await businessApi.changeLessonStatus(id, currentStatus, targetStatus, prompt.value.trim())
    await load()
    ElMessage.success(`${subject}状态已更新`)
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    ElMessage.error(userErrorMessage(reason, `${subject}状态更新失败`))
  }
}

watch(courseId, load)
onMounted(load)
</script>

<template>
  <PageHeader
    title="内容建设"
    description="按章节组织课时、正文与外部学习资源，草稿内容不会向学生展示。"
  >
    <template #actions>
      <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" :disabled="!courseId" @click="openSection">新建章节</el-button>
    </template>
  </PageHeader>

  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else-if="outline">
    <div class="lms-course-banner">
      <div>
        <span>{{ outline.course.code }}<template v-if="outline.course.termLabel"> · {{ outline.course.termLabel }}</template></span>
        <h2>{{ outline.course.title }}</h2>
        <p>{{ outline.course.description || '尚未填写课程简介' }}</p>
      </div>
      <el-tag :type="outline.course.status === 'PUBLISHED' ? 'success' : 'warning'">
        {{ outline.course.status === 'PUBLISHED' ? '课程已发布' : '课程草稿' }}
      </el-tag>
    </div>

    <div class="lms-outline">
      <section v-for="section in outline.sections" :key="section.sectionId" class="lms-outline__section">
        <header>
          <div>
            <span>第 {{ section.position }} 章</span>
            <h3>{{ section.title }}</h3>
            <p>{{ section.description }}</p>
          </div>
          <div class="lms-inline-actions">
            <el-tag size="small" :type="section.status === 'PUBLISHED' ? 'success' : 'warning'">
              {{ section.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </el-tag>
            <el-button :icon="FilePlus2" @click="openLesson(section)">添加课时</el-button>
            <el-button
              v-if="section.status === 'DRAFT'"
              type="primary"
              :icon="Send"
              @click="changeContentStatus('section', section.sectionId, section.status, 'PUBLISHED')"
            >发布</el-button>
            <el-button
              v-else
              :icon="RefreshCw"
              @click="changeContentStatus('section', section.sectionId, section.status, 'DRAFT')"
            >转为草稿</el-button>
            <el-button
              type="danger"
              :icon="Archive"
              @click="changeContentStatus('section', section.sectionId, section.status, 'ARCHIVED')"
            >归档</el-button>
          </div>
        </header>
        <div class="lms-lesson-list">
          <article v-for="lesson in section.lessons" :key="lesson.lessonId" class="lms-lesson-row">
            <span class="lms-lesson-row__index"><BookOpen :size="17" /></span>
            <div>
              <strong>{{ lesson.position }}. {{ lesson.title }}</strong>
              <p>{{ lesson.summary || '无课时摘要' }}</p>
            </div>
            <a v-if="lesson.resourceUrl" :href="lesson.resourceUrl" target="_blank" rel="noreferrer" title="打开资源">
              <ExternalLink :size="16" />
            </a>
            <el-tag size="small" :type="lesson.status === 'PUBLISHED' ? 'success' : 'warning'">
              {{ lesson.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </el-tag>
            <el-button
              link
              type="primary"
              :icon="lesson.status === 'DRAFT' ? Send : RefreshCw"
              @click="changeContentStatus('lesson', lesson.lessonId, lesson.status, lesson.status === 'DRAFT' ? 'PUBLISHED' : 'DRAFT')"
            >{{ lesson.status === 'DRAFT' ? '发布' : '转为草稿' }}</el-button>
            <el-button
              link
              type="danger"
              :icon="Archive"
              @click="changeContentStatus('lesson', lesson.lessonId, lesson.status, 'ARCHIVED')"
            >归档</el-button>
          </article>
          <div v-if="!section.lessons.length" class="lms-empty-inline">本章节尚无课时</div>
        </div>
      </section>
      <div v-if="!outline.sections.length" class="lms-empty-state">课程尚无章节，先创建第一个章节</div>
    </div>
  </template>

  <el-dialog v-model="sectionDialog" title="新建章节" width="min(560px, 94vw)">
    <el-form label-position="top">
      <el-form-item label="章节名称"><el-input v-model="sectionForm.title" /></el-form-item>
      <el-form-item label="章节说明"><el-input v-model="sectionForm.description" type="textarea" :rows="3" /></el-form-item>
      <el-form-item label="可见状态">
        <el-radio-group v-model="sectionForm.status">
          <el-radio-button value="DRAFT">草稿</el-radio-button>
          <el-radio-button value="PUBLISHED">发布</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer><el-button @click="sectionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveSection">保存章节</el-button></template>
  </el-dialog>

  <el-dialog v-model="lessonDialog" :title="`添加课时 · ${targetSection?.title ?? ''}`" width="min(680px, 94vw)">
    <el-form label-position="top">
      <el-form-item label="课时名称"><el-input v-model="lessonForm.title" /></el-form-item>
      <el-form-item label="课时摘要"><el-input v-model="lessonForm.summary" /></el-form-item>
      <el-form-item label="正文"><el-input v-model="lessonForm.body" type="textarea" :rows="7" /></el-form-item>
      <el-form-item label="外部资源链接"><el-input v-model="lessonForm.resourceUrl" placeholder="https://..." /></el-form-item>
      <el-form-item label="可见状态">
        <el-radio-group v-model="lessonForm.status"><el-radio-button value="DRAFT">草稿</el-radio-button><el-radio-button value="PUBLISHED">发布</el-radio-button></el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer><el-button @click="lessonDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveLesson">保存课时</el-button></template>
  </el-dialog>
</template>

<style scoped>
.lms-inline-actions { flex-wrap: wrap; justify-content: flex-end; }
.lms-lesson-row { grid-template-columns: 34px minmax(0, 1fr) auto auto auto auto; }
@media (max-width: 760px) {
  .lms-lesson-row { grid-template-columns: 30px minmax(0, 1fr) auto; padding: 9px 0; }
  .lms-lesson-row > .el-button { grid-column: 2 / -1; justify-self: start; }
}
</style>
