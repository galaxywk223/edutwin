<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, BookOpen, GraduationCap, RefreshCw } from '@lucide/vue'
import { useRouter } from 'vue-router'

import { useSessionStore } from '@/app/stores/session'
import PageHeader from '@/shared/components/PageHeader.vue'

const session = useSessionStore()
const router = useRouter()
const query = ref('')
const loading = ref(false)

const courses = computed(() => session.courses.filter((course) =>
  `${course.code} ${course.name} ${course.termLabel ?? ''}`.toLowerCase().includes(query.value.trim().toLowerCase()),
))

async function refresh() {
  loading.value = true
  try { await session.loadCourses() }
  finally { loading.value = false }
}

function enter(courseId: string) {
  void router.push(`/student/courses/${courseId}/overview`)
}

onMounted(() => { if (!session.courses.length) void refresh() })
</script>

<template>
  <div class="course-entry-page">
    <PageHeader
      title="我的课程"
      description="选择课程后进入课程内容、任务、成绩与学习分析。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <div class="course-entry-toolbar">
      <el-input v-model="query" clearable placeholder="搜索课程代码、名称或学期" />
      <span>{{ courses.length }} 门课程</span>
    </div>

    <section v-if="courses.length" class="course-entry-grid">
      <article
        v-for="course in courses"
        :key="course.courseId"
        class="course-entry-card"
        @click="enter(course.courseId)"
      >
        <div class="course-entry-card__icon"><BookOpen :size="22" /></div>
        <div class="course-entry-card__body">
          <div class="course-entry-card__meta">
            <span>{{ course.code }}</span>
            <span v-if="course.termLabel">{{ course.termLabel }}</span>
          </div>
          <h2>{{ course.name }}</h2>
          <p>{{ course.instructorName || '任课教师待定' }}</p>
          <div class="course-entry-card__footer">
            <span><GraduationCap :size="15" /> {{ course.credits }} 学分</span>
            <el-button text type="primary" :icon="ArrowRight" @click.stop="enter(course.courseId)">
              进入课程
            </el-button>
          </div>
        </div>
      </article>
    </section>
    <div v-else class="lms-empty-state lms-empty-state--large">当前没有可进入的课程</div>
  </div>
</template>
