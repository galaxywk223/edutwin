import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import ProfileView from '@/features/profile/ProfileView.vue'

const { updateProfileMock, profileValue, setProfileMock } = vi.hoisted(() => ({
  updateProfileMock: vi.fn(),
  setProfileMock: vi.fn(),
  profileValue: {
    userId: '00000000-0000-0000-0000-000000000001',
    username: 'student-01',
    displayName: '演示学生',
    officialId: '20260001',
    roles: ['STUDENT'],
    organizations: [],
    email: null,
    phone: null,
    avatarKey: 'avatar-1',
    themeColor: 'teal',
  },
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    profile: vi.fn(),
    updateProfile: updateProfileMock,
  },
}))

vi.mock('@/app/stores/session', () => ({
  useSessionStore: () => ({ profile: profileValue, setProfile: setProfileMock }),
}))

describe('ProfileView', () => {
  it('uses the API preset values when loading and saving profile preferences', async () => {
    updateProfileMock.mockResolvedValue({ ...profileValue, avatarKey: 'avatar-2', themeColor: 'violet' })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: ProfileView },
        { path: '/password-change', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(ProfileView, { global: { plugins: [ElementPlus, router] } })
    expect(wrapper.get('input[value="avatar-1"]').element).toMatchObject({ checked: true })
    expect(wrapper.get('input[value="teal"]').element).toMatchObject({ checked: true })

    await wrapper.get('input[value="avatar-2"]').setValue()
    await wrapper.get('input[value="violet"]').setValue()
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存设置'))
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(updateProfileMock).toHaveBeenCalledWith({
      email: null,
      phone: null,
      avatarKey: 'avatar-2',
      themeColor: 'violet',
    })
    expect(setProfileMock).toHaveBeenCalled()
  })
})
