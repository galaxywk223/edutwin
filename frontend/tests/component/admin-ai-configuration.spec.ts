import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import AdminAiConfigurationView from '@/features/admin/AdminAiConfigurationView.vue'

const { loadMock, activateMock, restoreMock } = vi.hoisted(() => ({
  loadMock: vi.fn(),
  activateMock: vi.fn(),
  restoreMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    adminAiConfiguration: loadMock,
    activateAdminAiConfiguration: activateMock,
    restoreAdminAiEnvironment: restoreMock,
  },
}))

describe('AdminAiConfigurationView', () => {
  it('keeps the key write-only and omits a blank key when saving', async () => {
    const configuration = {
      source: 'ENVIRONMENT',
      revision: 0,
      enabled: false,
      apiBaseUrl: 'https://api.deepseek.com',
      model: 'deepseek-v4-flash',
      apiKeyConfigured: true,
      writeAvailable: true,
      lastTestStatus: 'SKIPPED_DISABLED',
      lastTestedAt: null,
      lastTestLatencyMs: null,
      updatedAt: '2026-07-16T08:00:00Z',
      updatedBy: null,
    } as const
    loadMock.mockResolvedValue(configuration)
    activateMock.mockResolvedValue({
      configuration: { ...configuration, source: 'DATABASE', revision: 1 },
      testStatus: 'SKIPPED_DISABLED',
      testLatencyMs: null,
    })

    const wrapper = mount(AdminAiConfigurationView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('环境默认')
    expect(wrapper.text()).not.toContain('sk-')
    const keyInput = wrapper.get('input[autocomplete="new-password"]')
    expect(keyInput.attributes('placeholder')).toBe('已配置，留空保持不变')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(activateMock).toHaveBeenCalledWith({
      expectedRevision: 0,
      enabled: false,
      apiBaseUrl: 'https://api.deepseek.com',
      model: 'deepseek-v4-flash',
    })
    expect(wrapper.text()).toContain('管理员配置')
  })
})
