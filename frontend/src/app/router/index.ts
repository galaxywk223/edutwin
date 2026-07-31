import { createRouter, createWebHashHistory, createWebHistory, type RouteLocationNormalized } from 'vue-router'

import AppShell from '@/app/layouts/AppShell.vue'
import { useSessionStore } from '@/app/stores/session'
import type { UserRole } from '@/shared/types/api'

function landing(userRole: UserRole | undefined, mustChangePassword = false) {
  if (mustChangePassword) return '/password-change'
  if (userRole === 'ADMIN') return '/admin/overview'
  if (userRole === 'COUNSELOR') return '/counselor/students'
  return userRole === 'TEACHER' ? '/teacher/courses' : '/student/courses'
}

const router = createRouter({
  history: import.meta.env.VITE_EDUTWIN_MODE === 'showcase'
    ? createWebHashHistory(import.meta.env.BASE_URL)
    : createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/features/auth/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/password-change',
      name: 'password-change',
      component: () => import('@/features/auth/PasswordChangeView.vue'),
    },
    {
      path: '/',
      component: AppShell,
      children: [
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/features/profile/ProfileView.vue'),
        },
        {
          path: 'teacher/courses',
          name: 'teacher-courses',
          component: () => import('@/features/lms/TeacherCoursesView.vue'),
          meta: { role: 'TEACHER' },
        },
        { path: 'teacher/risk-cases', name: 'teacher-risk', component: () => import('@/features/risk/RiskCaseManagerView.vue'), meta: { role: 'TEACHER' } },
        {
          path: 'teacher/courses/:courseId/dashboard',
          name: 'teacher-dashboard',
          component: () => import('@/features/teacher-dashboard/TeacherDashboardView.vue'),
          meta: { role: 'TEACHER' },
        },
        {
          path: 'teacher/courses/:courseId/content',
          name: 'teacher-content',
          component: () => import('@/features/lms/TeacherContentView.vue'),
          meta: { role: 'TEACHER' },
        },
        {
          path: 'teacher/courses/:courseId/assessments',
          name: 'teacher-assessments',
          component: () => import('@/features/lms/TeacherAssessmentsView.vue'),
          meta: { role: 'TEACHER' },
        },
        {
          path: 'teacher/courses/:courseId/roster',
          name: 'teacher-roster',
          component: () => import('@/features/lms/TeacherRosterView.vue'),
          meta: { role: 'TEACHER' },
        },
        {
          path: 'teacher/courses/:courseId/students/:studentId/twin',
          name: 'teacher-student-twin',
          component: () => import('@/features/twin/TwinDetailView.vue'),
          meta: { role: 'TEACHER' },
        },
        {
          path: 'student/courses',
          name: 'student-courses',
          component: () => import('@/features/lms/StudentCoursesView.vue'),
          meta: { role: 'STUDENT' },
        },
        { path: 'student/actions', name: 'student-actions', component: () => import('@/features/risk/StudentActionItemsView.vue'), meta: { role: 'STUDENT' } },
        {
          path: 'student/courses/:courseId/overview',
          name: 'student-course',
          component: () => import('@/features/lms/StudentCourseView.vue'),
          meta: { role: 'STUDENT' },
        },
        {
          path: 'student/courses/:courseId/assignments',
          name: 'student-assignments',
          component: () => import('@/features/lms/StudentAssignmentsView.vue'),
          meta: { role: 'STUDENT' },
        },
        {
          path: 'student/courses/:courseId/grades',
          name: 'student-grades',
          component: () => import('@/features/lms/StudentGradesView.vue'),
          meta: { role: 'STUDENT' },
        },
        {
          path: 'student/courses/:courseId/twin',
          name: 'student-twin',
          component: () => import('@/features/twin/TwinDetailView.vue'),
          meta: { role: 'STUDENT' },
        },
        {
          path: 'student/courses/:courseId/practice',
          name: 'practice',
          component: () => import('@/features/practice/PracticeView.vue'),
          meta: { role: 'STUDENT' },
        },
        {
          path: 'student/courses/:courseId/plan',
          name: 'learning-plan',
          component: () => import('@/features/learning-plan/LearningPlanView.vue'),
          meta: { role: 'STUDENT' },
        },
        { path: 'admin/overview', name: 'admin-overview', component: () => import('@/features/admin/AdminOverviewView.vue'), meta: { role: 'ADMIN' } },
        { path: 'admin/users', name: 'admin-users', component: () => import('@/features/admin/AdminUsersView.vue'), meta: { role: 'ADMIN' } },
        { path: 'admin/organizations', name: 'admin-organizations', component: () => import('@/features/admin/AdminOrganizationsView.vue'), meta: { role: 'ADMIN' } },
        { path: 'admin/model-data', name: 'admin-model-data', component: () => import('@/features/transparency/TransparencyView.vue'), meta: { role: 'ADMIN' } },
        { path: 'admin/ai-configuration', name: 'admin-ai-configuration', component: () => import('@/features/admin/AdminAiConfigurationView.vue'), meta: { role: 'ADMIN' } },
        { path: 'admin/audit', name: 'admin-audit', component: () => import('@/features/admin/AdminAuditView.vue'), meta: { role: 'ADMIN' } },
        { path: 'counselor/students', name: 'counselor-students', component: () => import('@/features/counselor/CounselorStudentsView.vue'), meta: { role: 'COUNSELOR' } },
        { path: 'counselor/risk-cases', name: 'counselor-risk', component: () => import('@/features/risk/RiskCaseManagerView.vue'), meta: { role: 'COUNSELOR' } },
        { path: 'counselor/classes/compare', name: 'counselor-compare', component: () => import('@/features/counselor/CounselorClassComparisonView.vue'), meta: { role: 'COUNSELOR' } },
        { path: 'counselor/students/:studentId', name: 'counselor-student-overview', component: () => import('@/features/counselor/CounselorStudentOverviewView.vue'), meta: { role: 'COUNSELOR' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to: RouteLocationNormalized) => {
  const session = useSessionStore()
  await session.restore()
  const activeRole = session.activeRole
  if (to.meta.public) {
    return session.isAuthenticated && to.name === 'login'
      ? landing(activeRole, session.user?.mustChangePassword)
      : true
  }
  if (!session.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (session.user?.mustChangePassword && to.name !== 'password-change') return '/password-change'
  if (to.path === '/') return landing(activeRole)
  if (to.meta.role && to.meta.role !== activeRole) return landing(activeRole)
  if (typeof to.params.courseId === 'string'
    && !session.courses.some((course) => course.courseId === to.params.courseId)) {
    return activeRole === 'TEACHER' ? '/teacher/courses' : '/student/courses'
  }
  return true
})

export default router
