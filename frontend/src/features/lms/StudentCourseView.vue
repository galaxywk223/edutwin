<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Check, ExternalLink, PlayCircle, RefreshCw } from '@lucide/vue'
import { ElMessage } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { useCourseContext } from '@/app/composables/useCourseContext'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import type { CourseOutline, LmsLesson } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const { courseId } = useCourseContext()
const outline = ref<CourseOutline | null>(null)
const activeLesson = ref<LmsLesson | null>(null)
const loading = ref(false)
const completing = ref(false)
const error = ref<string | null>(null)

const progress = computed(() => {
  if (!outline.value?.totalLessonCount) return 0
  return Math.round((outline.value.completedLessonCount / outline.value.totalLessonCount) * 100)
})

async function load() {
  if (!courseId.value) return
  loading.value = true
  error.value = null
  try {
    outline.value = await api.publishedCourse(courseId.value)
    const lessons = outline.value.sections.flatMap((section) => section.lessons)
    activeLesson.value = lessons.find((lesson) => lesson.lessonId === activeLesson.value?.lessonId) ?? lessons[0] ?? null
  } catch (reason) {
    error.value = userErrorMessage(reason, '课程内容读取失败')
  } finally {
    loading.value = false
  }
}

async function complete() {
  if (!courseId.value || !activeLesson.value || activeLesson.value.completed) return
  completing.value = true
  try {
    await api.completeLesson(courseId.value, activeLesson.value.lessonId)
    activeLesson.value.completed = true
    if (outline.value) outline.value.completedLessonCount += 1
    ElMessage.success('课时进度已记录')
  } catch (reason) {
    ElMessage.error(userErrorMessage(reason, '课时完成状态更新失败'))
  } finally {
    completing.value = false
  }
}

watch(courseId, load)
onMounted(load)
</script>

<template>
  <PageHeader
    title="课程首页"
    description="按课程目录学习已发布内容并记录课时完成进度。"
  >
    <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></template>
  </PageHeader>

  <PageFeedback v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else-if="outline">
    <div class="lms-course-banner lms-course-banner--student">
      <div>
        <span>{{ outline.course.code }}<template v-if="outline.course.termLabel"> · {{ outline.course.termLabel }}</template></span>
        <h2>{{ outline.course.title }}</h2>
        <p>{{ outline.course.description }}</p>
      </div>
      <div class="lms-progress-summary">
        <strong>{{ progress }}%</strong>
        <span>{{ outline.completedLessonCount }}/{{ outline.totalLessonCount }} 课时</span>
      </div>
    </div>

    <div class="lms-learning-layout">
      <aside class="lms-course-nav">
        <section v-for="section in outline.sections" :key="section.sectionId">
          <header><span>第 {{ section.position }} 章</span><strong>{{ section.title }}</strong></header>
          <button
            v-for="lesson in section.lessons"
            :key="lesson.lessonId"
            :class="{ 'is-active': activeLesson?.lessonId === lesson.lessonId }"
            @click="activeLesson = lesson"
          >
            <Check v-if="lesson.completed" :size="15" />
            <PlayCircle v-else :size="15" />
            <span>{{ lesson.position }}. {{ lesson.title }}</span>
          </button>
        </section>
      </aside>

      <article v-if="activeLesson" class="lms-lesson-reader">
        <header>
          <div><span>课时 {{ activeLesson.position }}</span><h2>{{ activeLesson.title }}</h2><p>{{ activeLesson.summary }}</p></div>
          <el-button v-if="!activeLesson.completed" type="primary" :loading="completing" @click="complete">完成课时</el-button>
          <el-tag v-else type="success" effect="light"><Check :size="14" /> 已完成</el-tag>
        </header>
        <div class="lms-lesson-body">{{ activeLesson.body }}</div>
        <a v-if="activeLesson.resourceUrl" class="lms-resource-link" :href="activeLesson.resourceUrl" target="_blank" rel="noreferrer">
          <ExternalLink :size="16" /> 打开补充资源
        </a>
      </article>
      <div v-else class="lms-empty-state">当前课程尚无已发布课时</div>
    </div>
  </template>
</template>
