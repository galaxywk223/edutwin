import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthSession } from '@/shared/types/api'

const { loginMock, coursesMock, profileMock, switchRoleMock } = vi.hoisted(() => ({
  loginMock: vi.fn(),
  coursesMock: vi.fn(),
  profileMock: vi.fn(),
  switchRoleMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    login: loginMock,
    courses: coursesMock,
    profile: profileMock,
    switchRole: switchRoleMock,
    me: vi.fn(),
  },
}))

import { useSessionStore } from '@/app/stores/session'

function session(mustChangePassword: boolean): AuthSession {
  return {
    accessToken: 'token',
    tokenType: 'Bearer',
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    user: {
      userId: '00000000-0000-0000-0000-000000000001',
      username: 'student',
      displayName: 'Student',
      role: 'STUDENT',
      activeRole: 'STUDENT',
      availableRoles: ['STUDENT', 'ADMIN'],
      mustChangePassword,
      accessibleCourseIds: [],
    },
  }
}

describe('session login', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    loginMock.mockReset()
    coursesMock.mockReset()
    profileMock.mockReset()
    switchRoleMock.mockReset()
    profileMock.mockResolvedValue({
      userId: '00000000-0000-0000-0000-000000000001', username: 'student',
      displayName: 'Student', officialId: '20260001', roles: ['STUDENT', 'ADMIN'],
      organizations: [], email: null, phone: null, avatarKey: 'avatar-1', themeColor: 'teal',
    })
  })

  it('does not request protected course data before a required password change', async () => {
    loginMock.mockResolvedValue(session(true))

    const store = useSessionStore()
    const user = await store.login('student', 'temporary-password')

    expect(user.mustChangePassword).toBe(true)
    expect(coursesMock).not.toHaveBeenCalled()
    expect(store.isAuthenticated).toBe(true)
  })

  it('loads course context after a normal student login', async () => {
    loginMock.mockResolvedValue(session(false))
    coursesMock.mockResolvedValue({
      items: [{
        courseId: '00000000-0000-0000-0000-000000000002',
        code: 'CS101',
        name: 'Programming',
        termLabel: '2026',
        credits: 3,
        college: null,
        department: null,
        instructorName: 'Teacher',
        role: 'STUDENT',
      }],
      total: 1,
    })

    const store = useSessionStore()
    await store.login('student', 'regular-password')

    expect(coursesMock).toHaveBeenCalledOnce()
    expect(store.courses).toHaveLength(1)
  })

  it('replaces the token and role context without another password prompt', async () => {
    loginMock.mockResolvedValue(session(false))
    coursesMock.mockResolvedValue({ items: [], total: 0 })
    switchRoleMock.mockResolvedValue({
      ...session(false),
      accessToken: 'admin-token',
      user: {
        ...session(false).user,
        role: 'ADMIN',
        activeRole: 'ADMIN',
        availableRoles: ['STUDENT', 'ADMIN'],
      },
    })

    const store = useSessionStore()
    await store.login('student', 'regular-password')
    const user = await store.switchRole('ADMIN')

    expect(switchRoleMock).toHaveBeenCalledWith('ADMIN')
    expect(user?.activeRole).toBe('ADMIN')
    expect(store.session?.accessToken).toBe('admin-token')
    expect(store.courses).toEqual([])
  })
})
