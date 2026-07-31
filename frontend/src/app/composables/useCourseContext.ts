import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useSessionStore } from '@/app/stores/session'

export function useCourseContext() {
  const route = useRoute()
  const router = useRouter()
  const session = useSessionStore()
  const courseId = computed(() => typeof route.params.courseId === 'string' ? route.params.courseId : null)
  const course = computed(() => session.courses.find((item) => item.courseId === courseId.value) ?? null)

  async function switchCourse(nextCourseId: string) {
    if (!route.name || !courseId.value) return
    await router.push({ name: route.name, params: { ...route.params, courseId: nextCourseId } })
  }

  return { courseId, course, switchCourse }
}
