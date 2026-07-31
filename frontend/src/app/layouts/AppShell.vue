<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity, AlertTriangle, BarChart3, BookOpen, BookUser, ChevronLeft, ClipboardCheck, ClipboardList,
  ChevronsUpDown, ContactRound, Database, Dumbbell, LayoutDashboard, LibraryBig, ListChecks, LogOut,
  GitCompareArrows, KeyRound, ListTodo, Menu, Network, ScrollText, Settings2, ShieldCheck, UserRoundCheck, X,
  RotateCcw,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'

import { useSessionStore } from '@/app/stores/session'
import AssistantDrawer from '@/shared/components/AssistantDrawer.vue'
import NotificationCenter from '@/shared/components/NotificationCenter.vue'
import { resetShowcaseData, showcaseUiMode } from '@/showcase/runtime'
import type { UserRole } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const mobileNavOpen = ref(false)
const switchingRole = ref(false)

const role = computed(() => session.activeRole)
const isTeacher = computed(() => role.value === 'TEACHER')
const isStudent = computed(() => role.value === 'STUDENT')
const isAdmin = computed(() => role.value === 'ADMIN')
const isCounselor = computed(() => role.value === 'COUNSELOR')
const courseId = computed(() => typeof route.params.courseId === 'string' ? route.params.courseId : null)
const currentCourse = computed(() => session.courses.find((course) => course.courseId === courseId.value) ?? null)

const navigation = computed(() => {
  if (isAdmin.value) return [
    { to: '/admin/overview', label: '系统概览', icon: ShieldCheck, match: '/admin/overview' },
    { to: '/admin/users', label: '用户与角色', icon: BookUser, match: '/admin/users' },
    { to: '/admin/organizations', label: '组织与学期', icon: Settings2, match: '/admin/organizations' },
    { to: '/admin/model-data', label: '模型与数据', icon: Database, match: '/admin/model-data' },
    { to: '/admin/ai-configuration', label: '大模型配置', icon: KeyRound, match: '/admin/ai-configuration' },
    { to: '/admin/audit', label: '审计日志', icon: ScrollText, match: '/admin/audit' },
  ]
  if (isCounselor.value) return [
    { to: '/counselor/students', label: '学生学业', icon: ContactRound, match: '/counselor/students' },
    { to: '/counselor/risk-cases', label: '风险工单', icon: AlertTriangle, match: '/counselor/risk-cases' },
    { to: '/counselor/classes/compare', label: '班级对比', icon: GitCompareArrows, match: '/counselor/classes/compare' },
  ]
  if (!courseId.value) return isTeacher.value ? [
    { to: '/teacher/courses', label: '我的课程', icon: LibraryBig, match: '/teacher/courses' },
    { to: '/teacher/risk-cases', label: '风险工单', icon: AlertTriangle, match: '/teacher/risk-cases' },
  ] : [
    { to: '/student/courses', label: '我的课程', icon: LibraryBig, match: '/student/courses' },
    { to: '/student/actions', label: '我的行动项', icon: ListTodo, match: '/student/actions' },
  ]
  if (isTeacher.value) return [
    { to: `/teacher/courses/${courseId.value}/dashboard`, label: '教学概览', icon: LayoutDashboard, match: `/teacher/courses/${courseId.value}/dashboard` },
    { to: `/teacher/courses/${courseId.value}/content`, label: '课程内容', icon: BookOpen, match: `/teacher/courses/${courseId.value}/content` },
    { to: `/teacher/courses/${courseId.value}/assessments`, label: '作业测验', icon: ClipboardCheck, match: `/teacher/courses/${courseId.value}/assessments` },
    { to: `/teacher/courses/${courseId.value}/roster`, label: '学生名单', icon: UserRoundCheck, match: `/teacher/courses/${courseId.value}/roster` },
    { to: '/teacher/risk-cases', label: '风险工单', icon: AlertTriangle, match: '/teacher/risk-cases' },
  ]
  return [
    { to: `/student/courses/${courseId.value}/overview`, label: '课程首页', icon: BookOpen, match: `/student/courses/${courseId.value}/overview` },
    { to: `/student/courses/${courseId.value}/assignments`, label: '课程任务', icon: ClipboardList, match: `/student/courses/${courseId.value}/assignments` },
    { to: `/student/courses/${courseId.value}/grades`, label: '学习成绩', icon: BarChart3, match: `/student/courses/${courseId.value}/grades` },
    { to: `/student/courses/${courseId.value}/twin`, label: '学情画像', icon: Network, match: `/student/courses/${courseId.value}/twin` },
    { to: `/student/courses/${courseId.value}/practice`, label: '针对性练习', icon: Dumbbell, match: `/student/courses/${courseId.value}/practice` },
    { to: `/student/courses/${courseId.value}/plan`, label: '学习计划', icon: ListChecks, match: `/student/courses/${courseId.value}/plan` },
    { to: '/student/actions', label: '我的行动项', icon: ListTodo, match: '/student/actions' },
  ]
})

const pageTitle = computed(() => {
  if (route.name === 'teacher-student-twin') return '学生学情画像'
  if (route.name === 'counselor-student-overview') return '学生学业详情'
  if (route.name === 'profile') return '个人中心'
  return navigation.value.find((item) => route.path.startsWith(item.match))?.label ?? 'EduTwin'
})

const workspaceLabel = computed(() => isTeacher.value ? '教学工作区' : isStudent.value ? '课程学习' : '')
const roleLabel = computed(() => ({ ADMIN: '系统管理员', TEACHER: '教师', STUDENT: '学生', COUNSELOR: '辅导员' })[role.value ?? 'STUDENT'])
const roleLabels: Record<UserRole, string> = { ADMIN: '系统管理员', TEACHER: '教师', STUDENT: '学生', COUNSELOR: '辅导员' }

function navigate(path: string) {
  mobileNavOpen.value = false
  void router.push(path)
}

function backToCourses() {
  navigate(isTeacher.value ? '/teacher/courses' : '/student/courses')
}

function switchCourse(nextCourseId: string) {
  const name = route.name === 'teacher-student-twin'
    ? 'teacher-dashboard'
    : route.name
  void router.push({ name: name || (isTeacher.value ? 'teacher-dashboard' : 'student-course'), params: { courseId: nextCourseId } })
}

function logout() {
  session.clear()
  void router.replace('/login')
}

async function resetDemo() {
  await resetShowcaseData()
  session.clear()
  window.location.reload()
}

function landing(targetRole: UserRole) {
  if (targetRole === 'ADMIN') return '/admin/overview'
  if (targetRole === 'COUNSELOR') return '/counselor/students'
  return targetRole === 'TEACHER' ? '/teacher/courses' : '/student/courses'
}

async function switchRole(targetRole: UserRole) {
  if (targetRole === role.value || switchingRole.value) return
  switchingRole.value = true
  try {
    await session.switchRole(targetRole)
    mobileNavOpen.value = false
    await router.replace(landing(targetRole))
    ElMessage.success(`已切换为${roleLabels[targetRole]}`)
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '角色切换失败'))
  } finally {
    switchingRole.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <aside class="app-sidebar" :class="{ 'app-sidebar--open': mobileNavOpen }">
      <div class="brand" aria-label="EduTwin">
        <span class="brand__mark"><Activity :size="20" :stroke-width="2.2" /></span>
        <div class="brand__text"><span class="brand__name">EduTwin</span><span class="brand__tagline">高校智慧教学综合平台</span></div>
      </div>
      <button class="mobile-nav-close" aria-label="关闭导航" @click="mobileNavOpen = false"><X :size="20" /></button>

      <button v-if="courseId" class="course-back" @click="backToCourses">
        <ChevronLeft :size="17" />
        返回课程列表
      </button>

      <nav class="app-nav" aria-label="主导航">
        <p class="app-nav__label">
          {{ isAdmin ? '系统管理' : isCounselor ? '学业观察' : courseId ? workspaceLabel : '课程入口' }}
        </p>
        <button
          v-for="item in navigation"
          :key="item.to"
          class="app-nav__item"
          :class="{ 'app-nav__item--active': route.path.startsWith(item.match) }"
          @click="navigate(item.to)"
        >
          <span class="app-nav__item-icon">
            <component :is="item.icon" :size="18" :stroke-width="1.9" />
          </span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="app-sidebar__footer">
        <button class="account-summary" type="button" @click="navigate('/profile')">
          <span class="account-summary__avatar">{{ session.user?.displayName.slice(0, 1) }}</span>
          <div>
            <strong>{{ session.user?.displayName }}</strong>
            <span>{{ roleLabel }}</span>
          </div>
        </button>
        <button class="icon-action" aria-label="退出登录" title="退出登录" @click="logout">
          <LogOut :size="18" />
        </button>
      </div>
    </aside>
    <div v-if="mobileNavOpen" class="mobile-nav-scrim" @click="mobileNavOpen = false" />

    <main class="app-main">
      <header class="app-header">
        <div class="header-left">
          <button class="mobile-nav-trigger" aria-label="打开导航" @click="mobileNavOpen = true"><Menu :size="20" /></button>
          <div class="header-meta"><div class="header-meta__title">{{ pageTitle }}</div><div class="header-meta__subtitle">{{ currentCourse ? `${currentCourse.code} · ${currentCourse.name}` : isAdmin ? '平台治理与运行审计' : isCounselor ? '跨课程只读学习统计' : '先选择一门课程进入工作区' }}</div></div>
        </div>
        <div v-if="courseId && currentCourse" class="course-context">
          <el-select :model-value="courseId" class="course-select" aria-label="切换课程" @change="switchCourse">
            <el-option v-for="course in session.courses" :key="course.courseId" :label="`${course.code} · ${course.name}`" :value="course.courseId" />
          </el-select>
        </div>
        <NotificationCenter />
        <div v-if="showcaseUiMode" class="showcase-status" aria-label="合成数据演示模式">
          <span>合成演示</span>
          <button class="icon-action" type="button" title="重置演示数据" aria-label="重置演示数据" @click="resetDemo">
            <RotateCcw :size="16" />
          </button>
        </div>
        <el-dropdown v-if="session.availableRoles.length > 1" trigger="click" @command="switchRole">
          <button class="role-chip role-switch" type="button" :disabled="switchingRole" aria-label="切换活动角色">
            {{ roleLabel }}<ChevronsUpDown :size="14" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="availableRole in session.availableRoles" :key="availableRole" :command="availableRole" :disabled="availableRole === role">
                {{ roleLabels[availableRole] }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <span v-else class="role-chip">{{ roleLabel }}</span>
      </header>
      <div class="app-content"><RouterView :key="route.fullPath" /></div>
    </main>
    <AssistantDrawer />
  </div>
</template>
