import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import AdminAuditView from '@/features/admin/AdminAuditView.vue'

const { auditEventsMock, adminUsersMock } = vi.hoisted(() => ({
  auditEventsMock: vi.fn(),
  adminUsersMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    adminAuditEvents: auditEventsMock,
    adminUsers: adminUsersMock,
    adminAuditEvent: vi.fn(),
    exportAdminAuditEvents: vi.fn(),
  },
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

describe('AdminAuditView', () => {
  it('uses actor names and keeps technical identifiers out of the default table', async () => {
    const actorUserId = 'ab38360a-1111-2222-3333-444444444444'
    const targetId = 'cd38360a-1111-2222-3333-444444444444'
    adminUsersMock.mockResolvedValue({
      items: [{
        userId: actorUserId,
        username: 'admin-reviewer',
        displayName: '审计管理员',
        role: 'ADMIN',
        roles: ['ADMIN'],
        enabled: true,
        mustChangePassword: false,
        originType: 'MANUAL',
        createdAt: '2026-07-16T08:00:00Z',
        updatedAt: '2026-07-16T08:00:00Z',
      }],
      total: 1,
    })
    auditEventsMock.mockResolvedValue({
      items: [{
        id: 'ef38360a-1111-2222-3333-444444444444',
        actorUserId,
        actorDisplayName: '审计管理员',
        activeRole: 'ADMIN',
        action: 'USER_UPDATED',
        targetType: 'USER',
        targetId,
        outcome: 'SUCCEEDED',
        reason: null,
        errorCode: null,
        beforeJson: null,
        afterJson: null,
        metadataJson: null,
        correlationId: null,
        createdAt: '2026-07-16T08:30:00Z',
      }],
      total: 1,
      page: 0,
      size: 20,
    })

    const wrapper = mount(AdminAuditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('审计管理员')
    expect(wrapper.findAllComponents({ name: 'ElOption' })
      .some((option) => option.props('label') === '审计管理员 · admin-reviewer')).toBe(true)
    expect(wrapper.text()).not.toContain(actorUserId)
    expect(wrapper.text()).not.toContain(targetId)
    expect(wrapper.text()).not.toContain(actorUserId.slice(0, 8))
  })
})
