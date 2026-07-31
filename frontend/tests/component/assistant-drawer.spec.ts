import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AssistantDrawer from '@/shared/components/AssistantDrawer.vue'

const { conversationsMock, messagesMock, sendMessageMock, deleteConversationMock } = vi.hoisted(() => ({
  conversationsMock: vi.fn(),
  messagesMock: vi.fn(),
  sendMessageMock: vi.fn(),
  deleteConversationMock: vi.fn(),
}))

vi.mock('@/api/client/edutwin', () => ({
  api: {
    assistantConversations: conversationsMock,
    assistantMessages: messagesMock,
    createAssistantConversation: vi.fn(),
    sendAssistantMessage: sendMessageMock,
    deleteAssistantConversation: deleteConversationMock,
  },
}))

vi.mock('@/api/client/sse', () => ({ streamAssistantMessage: vi.fn() }))

describe('AssistantDrawer', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    conversationsMock.mockReset()
    messagesMock.mockReset()
    sendMessageMock.mockReset()
    deleteConversationMock.mockReset()
  })

  it('opens the global assistant and restores the current role conversation', async () => {
    setActivePinia(createPinia())
    conversationsMock.mockResolvedValue({
      items: [{
        conversationId: 'conversation-1', title: '课程分析', activeRole: 'STUDENT',
        createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:00Z',
      }],
    })
    messagesMock.mockResolvedValue({ items: [] })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AssistantDrawer, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, router] },
    })
    await wrapper.get('[aria-label="打开 EduTwin 助手"]').trigger('click')
    await flushPromises()

    expect(conversationsMock).toHaveBeenCalledOnce()
    expect(messagesMock).toHaveBeenCalledWith('conversation-1')
    expect(document.body.textContent).toContain('EduTwin 助手')
    expect(document.body.textContent).toContain('开始一次只读数据分析')
  })

  it('sends the route context keys accepted by the backend', async () => {
    setActivePinia(createPinia())
    conversationsMock.mockResolvedValue({
      items: [{
        conversationId: 'conversation-1', title: '课程分析', activeRole: 'STUDENT',
        createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:00Z',
      }],
    })
    messagesMock.mockResolvedValue({ items: [] })
    sendMessageMock.mockResolvedValue({
      userMessageId: 'user-message-1', assistantMessageId: 'assistant-message-1',
      status: 'message.started', streamUrl: '/events',
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/student/courses/:courseId/overview', component: { template: '<div />' } }],
    })
    await router.push('/student/courses/course-1/overview?tab=progress')
    await router.isReady()

    const wrapper = mount(AssistantDrawer, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, router] },
    })
    await wrapper.get('[aria-label="打开 EduTwin 助手"]').trigger('click')
    await flushPromises()
    const textarea = document.querySelector('textarea') as HTMLTextAreaElement
    textarea.value = '分析当前课程'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    document.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(sendMessageMock).toHaveBeenCalledWith(
      'conversation-1',
      '分析当前课程',
      expect.any(String),
      { route: '/student/courses/course-1/overview?tab=progress', courseId: 'course-1' },
    )
  })

  it('disables conversation deletion while a reply is processing', async () => {
    setActivePinia(createPinia())
    conversationsMock.mockResolvedValue({
      items: [{
        conversationId: 'conversation-1', title: '生成中的会话', activeRole: 'STUDENT',
        createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:00Z',
      }],
    })
    messagesMock.mockResolvedValue({
      items: [{
        messageId: 'assistant-message-1', role: 'ASSISTANT', status: 'PROCESSING', content: null,
        sources: [], deepLinks: [], errorCode: null, retryable: false,
        createdAt: '2026-07-15T00:00:00Z', completedAt: null,
      }],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AssistantDrawer, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, router] },
    })
    await wrapper.get('[aria-label="打开 EduTwin 助手"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[aria-label="会话操作"]') as HTMLButtonElement).click()
    await flushPromises()

    const deleteItem = Array.from(document.querySelectorAll('.el-dropdown-menu__item'))
      .find((item) => item.textContent?.includes('删除会话'))
    expect(deleteItem?.classList.contains('is-disabled')).toBe(true)
    expect(deleteConversationMock).not.toHaveBeenCalled()
  })

  it('renders assistant Markdown while removing unsafe HTML and links', async () => {
    setActivePinia(createPinia())
    conversationsMock.mockResolvedValue({
      items: [{
        conversationId: 'conversation-1', title: 'Markdown', activeRole: 'STUDENT',
        createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:00Z',
      }],
    })
    messagesMock.mockResolvedValue({
      items: [{
        messageId: 'assistant-message-1', role: 'ASSISTANT', status: 'COMPLETED',
        content: '## 学情摘要\n\n**风险等级**\n\n| 指标 | 值 |\n| --- | --- |\n| 掌握度 | 82% |\n\n`courseId`\n\n<script>window.hacked=true</script>\n[危险链接](javascript:alert(1))\n[参考资料](https://example.com/report)',
        sources: [], deepLinks: [], errorCode: null, retryable: false,
        createdAt: '2026-07-15T00:00:00Z', completedAt: '2026-07-15T00:00:01Z',
      }],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AssistantDrawer, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, router] },
    })
    await wrapper.get('[aria-label="打开 EduTwin 助手"]').trigger('click')
    await flushPromises()

    const markdown = document.querySelector('.assistant-markdown') as HTMLElement
    expect(markdown.querySelector('h2')?.textContent).toBe('学情摘要')
    expect(markdown.querySelector('strong')?.textContent).toBe('风险等级')
    expect(markdown.querySelector('table')).not.toBeNull()
    expect(markdown.querySelector('code')?.textContent).toBe('courseId')
    expect(markdown.querySelector('script')).toBeNull()
    const links = markdown.querySelectorAll('a')
    expect(links[0].hasAttribute('href')).toBe(false)
    expect(links[1].getAttribute('rel')).toBe('noopener noreferrer')
    expect(links[1].getAttribute('target')).toBe('_blank')
  })
})
