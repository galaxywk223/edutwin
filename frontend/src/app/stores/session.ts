import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { api } from '@/api/client/edutwin'
import { configureHttp } from '@/api/client/http'
import type { AuthSession, AuthenticatedUser, CourseSummary, UserProfile, UserRole } from '@/shared/types/api'

const SESSION_KEY = 'edutwin.session.v2'

function readSession(): AuthSession | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as AuthSession
    if (Date.parse(value.expiresAt) <= Date.now()) {
      sessionStorage.removeItem(SESSION_KEY)
      return null
    }
    return value
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}

function normalizeUser(user: AuthenticatedUser, fallback?: AuthenticatedUser | null): AuthenticatedUser {
  const activeRole = user.activeRole ?? user.role ?? fallback?.activeRole ?? fallback?.role ?? 'STUDENT'
  const availableRoles = user.availableRoles?.length
    ? user.availableRoles
    : fallback?.availableRoles?.length
      ? fallback.availableRoles
      : [activeRole]
  return { ...user, role: activeRole, activeRole, availableRoles }
}

function applyTheme(themeColor?: string | null) {
  const theme = (themeColor ?? 'teal').toLowerCase()
  document.documentElement.dataset.theme = ['teal', 'blue', 'violet', 'rose'].includes(theme)
    ? theme
    : 'teal'
}

export const useSessionStore = defineStore('session', () => {
  const session = ref<AuthSession | null>(readSession())
  const user = computed<AuthenticatedUser | null>(() => session.value?.user ?? null)
  const courses = ref<CourseSummary[]>([])
  const profile = ref<UserProfile | null>(null)
  const initialized = ref(false)

  configureHttp({
    getToken: () => session.value?.accessToken ?? null,
    onUnauthorized: () => clear(),
  })

  function persist(value: AuthSession | null) {
    session.value = value ? { ...value, user: normalizeUser(value.user, session.value?.user) } : null
    if (session.value) sessionStorage.setItem(SESSION_KEY, JSON.stringify(session.value))
    else sessionStorage.removeItem(SESSION_KEY)
  }

  function clear() {
    persist(null)
    courses.value = []
    profile.value = null
    delete document.documentElement.dataset.theme
  }

  async function login(username: string, password: string) {
    const value = await api.login(username, password)
    persist(value)
    if (!session.value?.user.mustChangePassword) await loadContext()
    return session.value!.user
  }

  async function loadCourses() {
    if (!session.value) return
    const response = await api.courses()
    courses.value = response.items
  }

  async function loadProfile() {
    if (!session.value) return
    try {
      profile.value = await api.profile()
      applyTheme(profile.value.themeColor)
    } catch {
      profile.value = null
      applyTheme(null)
    }
  }

  async function loadContext() {
    if (!session.value) return
    const role = session.value.user.activeRole ?? session.value.user.role
    courses.value = []
    const tasks: Promise<unknown>[] = [loadProfile()]
    if (['STUDENT', 'TEACHER'].includes(role)) tasks.push(loadCourses())
    await Promise.all(tasks)
  }

  async function switchRole(role: UserRole) {
    if (!session.value || role === (session.value.user.activeRole ?? session.value.user.role)) {
      return session.value?.user ?? null
    }
    const value = await api.switchRole(role)
    persist(value)
    await loadContext()
    return session.value!.user
  }

  function setProfile(value: UserProfile) {
    profile.value = value
    applyTheme(value.themeColor)
  }

  async function restore() {
    if (initialized.value) return
    initialized.value = true
    if (!session.value) return
    try {
      const currentUser = normalizeUser(await api.me(), session.value.user)
      persist({ ...session.value, user: currentUser })
      if (!currentUser.mustChangePassword) await loadContext()
    } catch {
      clear()
    }
  }

  return {
    session,
    user,
    courses,
    profile,
    initialized,
    isAuthenticated: computed(() => Boolean(session.value)),
    activeRole: computed(() => user.value?.activeRole ?? user.value?.role),
    availableRoles: computed(() => user.value?.availableRoles ?? (user.value ? [user.value.role] : [])),
    login,
    restore,
    loadCourses,
    loadProfile,
    switchRole,
    setProfile,
    clear,
  }
})
