import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import NotificationCenter from '@/shared/components/NotificationCenter.vue'

const { unreadMock, notificationsMock } = vi.hoisted(() => ({
  unreadMock: vi.fn(), notificationsMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    unreadNotifications: unreadMock,
    notifications: notificationsMock,
    markNotificationRead: vi.fn(),
    markAllNotificationsRead: vi.fn(),
  },
}))

describe('NotificationCenter', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    unreadMock.mockReset()
    notificationsMock.mockReset()
  })

  it('renders the unread badge and loads role notifications when opened', async () => {
    setActivePinia(createPinia())
    unreadMock.mockResolvedValue({ count: 2 })
    notificationsMock.mockResolvedValue({
      items: [{
        notificationId: 'notification-1', type: 'ASSESSMENT_PUBLISHED', title: '新考核已发布',
        body: '程序设计测验现已开放', deepLink: '/student/courses/course-1/assignments',
        createdAt: '2026-07-15T00:00:00Z', expiresAt: null, read: false,
      }],
      page: 0, size: 20, total: 1,
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(NotificationCenter, { attachTo: document.body, global: { plugins: [ElementPlus, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('2')

    await wrapper.get('[aria-label="打开通知中心"]').trigger('click')
    await flushPromises()
    expect(notificationsMock).toHaveBeenCalledWith(0, 20)
    expect(document.body.textContent).toContain('新考核已发布')
    wrapper.unmount()
  })
})
