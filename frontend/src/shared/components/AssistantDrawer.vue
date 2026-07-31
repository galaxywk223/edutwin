<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Bot, ExternalLink, LoaderCircle, MessageSquarePlus, MoreHorizontal, Pencil, RotateCcw, Send, Trash2, X } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { streamAssistantMessage } from '@/api/client/sse'
import { useSessionStore } from '@/app/stores/session'
import type { AssistantConversation, AssistantEvent, AssistantMessage, AssistantSource } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { createCachedMarkdownRenderer } from '@/shared/utils/markdown'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const loading = ref(false)
const conversations = ref<AssistantConversation[]>([])
const conversationId = ref('')
const messages = ref<AssistantMessage[]>([])
const draft = ref('')
const sending = ref(false)
const toolStatus = ref('')
const messageList = ref<HTMLElement | null>(null)
let streamController: AbortController | null = null
const markdownRenderer = createCachedMarkdownRenderer()

const currentConversation = computed(() => conversations.value.find((item) => item.conversationId === conversationId.value) ?? null)
const hasProcessingMessage = computed(() => messages.value.some((item) => item.status === 'PROCESSING'))

function newLocalMessage(messageId: string, role: 'USER' | 'ASSISTANT', content: string | null): AssistantMessage {
  return {
    messageId, role, content, status: role === 'USER' ? 'COMPLETED' : 'PROCESSING',
    sources: [], deepLinks: [], errorCode: null, retryable: false,
    createdAt: new Date().toISOString(), completedAt: role === 'USER' ? new Date().toISOString() : null,
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

async function loadConversations(selectLatest = false) {
  loading.value = true
  try {
    conversations.value = (await api.assistantConversations()).items
    if (!conversations.value.length) {
      const created = await api.createAssistantConversation()
      conversations.value = [created]
    }
    if (selectLatest || !conversations.value.some((item) => item.conversationId === conversationId.value)) {
      conversationId.value = conversations.value[0].conversationId
    } else {
      await loadMessages()
    }
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '助手会话读取失败'))
  } finally {
    loading.value = false
  }
}

async function loadMessages() {
  streamController?.abort()
  streamController = null
  toolStatus.value = ''
  markdownRenderer.clear()
  if (!conversationId.value) {
    messages.value = []
    return
  }
  loading.value = true
  try {
    messages.value = (await api.assistantMessages(conversationId.value)).items
    await scrollToBottom()
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '会话消息读取失败'))
  } finally {
    loading.value = false
  }
}

async function createConversation() {
  try {
    const created = await api.createAssistantConversation()
    conversations.value = [created, ...conversations.value]
    conversationId.value = created.conversationId
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '新建会话失败'))
  }
}

async function renameConversation() {
  if (!currentConversation.value) return
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话名称', '重命名会话', {
      inputValue: currentConversation.value.title,
      inputPattern: /\S+/,
      inputErrorMessage: '会话名称不能为空',
      inputValidator: (input) => input.length <= 120 || '会话名称不能超过 120 个字符',
    })
    const updated = await api.renameAssistantConversation(currentConversation.value.conversationId, value.trim())
    conversations.value = conversations.value.map((item) => item.conversationId === updated.conversationId ? updated : item)
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(userErrorMessage(cause, '会话重命名失败'))
  }
}

async function deleteConversation() {
  if (!currentConversation.value || hasProcessingMessage.value) return
  try {
    await ElMessageBox.confirm('删除后将清除会话正文，且无法恢复。', '删除会话', {
      type: 'warning', confirmButtonText: '确认删除',
    })
    await api.deleteAssistantConversation(currentConversation.value.conversationId)
    conversations.value = conversations.value.filter((item) => item.conversationId !== currentConversation.value?.conversationId)
    conversationId.value = ''
    if (!conversations.value.length) await createConversation()
    else conversationId.value = conversations.value[0].conversationId
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(userErrorMessage(cause, '会话删除失败'))
  }
}

function applyEvent(event: AssistantEvent) {
  const message = messages.value.find((item) => item.messageId === event.messageId)
  if (!message) return
  if (event.type === 'message.delta') {
    message.content = `${message.content ?? ''}${String(event.data.text ?? '')}`
  } else if (event.type === 'tool.started') {
    toolStatus.value = '正在查询授权数据'
  } else if (event.type === 'tool.completed') {
    toolStatus.value = event.data.succeeded === false ? '数据查询未完成' : '已完成数据查询'
  } else if (event.type === 'message.completed') {
    message.status = 'COMPLETED'
    message.content = String(event.data.content ?? message.content ?? '')
    message.sources = (event.data.sources as AssistantSource[] | undefined) ?? []
    message.deepLinks = (event.data.deepLinks as string[] | undefined) ?? []
    message.completedAt = event.occurredAt
    toolStatus.value = ''
  } else if (event.type === 'message.failed') {
    message.status = 'FAILED'
    message.errorCode = String(event.data.errorCode ?? 'ASSISTANT_GENERATION_FAILED')
    message.retryable = event.data.retryable !== false
    message.completedAt = event.occurredAt
    toolStatus.value = ''
  }
  void scrollToBottom()
}

async function consumeStream(messageId: string) {
  streamController?.abort()
  const controller = new AbortController()
  streamController = controller
  let lastEventId = 0
  try {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        await streamAssistantMessage(messageId, {
          signal: controller.signal,
          lastEventId,
          onEvent: (event) => {
            if (event.sequence <= lastEventId) return
            lastEventId = event.sequence
            applyEvent(event)
          },
        })
        break
      } catch (cause) {
        if (controller.signal.aborted) return
        if (attempt === 1) throw cause
      }
    }
  } catch {
    ElMessage.warning('流式连接已中断，正在读取已保存结果')
  } finally {
    if (!controller.signal.aborted) await reconcileMessages()
    if (streamController === controller) streamController = null
  }
}

async function reconcileMessages() {
  if (!conversationId.value) return
  try {
    messages.value = (await api.assistantMessages(conversationId.value)).items
    await scrollToBottom()
  } catch {
    const processing = messages.value.find((item) => item.status === 'PROCESSING')
    if (processing) {
      processing.status = 'FAILED'
      processing.errorCode = 'ASSISTANT_STREAM_INTERRUPTED'
      processing.retryable = true
    }
  }
}

function routeContext() {
  const values: Record<string, string> = { route: route.fullPath }
  for (const key of ['courseId', 'studentId']) {
    const value = route.params[key]
    if (typeof value === 'string') values[key] = value
  }
  return values
}

async function sendMessage() {
  const content = draft.value.trim()
  if (!content || !conversationId.value || sending.value) return
  sending.value = true
  draft.value = ''
  try {
    const receipt = await api.sendAssistantMessage(
      conversationId.value, content, crypto.randomUUID(), routeContext(),
    )
    messages.value.push(
      newLocalMessage(receipt.userMessageId, 'USER', content),
      newLocalMessage(receipt.assistantMessageId, 'ASSISTANT', ''),
    )
    await scrollToBottom()
    await consumeStream(receipt.assistantMessageId)
    await loadConversations()
  } catch (cause) {
    draft.value = content
    ElMessage.error(userErrorMessage(cause, '消息发送失败'))
  } finally {
    sending.value = false
  }
}

async function retryMessage(message: AssistantMessage) {
  if (!message.retryable || sending.value) return
  sending.value = true
  try {
    const receipt = await api.retryAssistantMessage(message.messageId)
    messages.value.push(newLocalMessage(receipt.assistantMessageId, 'ASSISTANT', ''))
    await consumeStream(receipt.assistantMessageId)
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '消息重试失败'))
  } finally {
    sending.value = false
  }
}

async function followLink(path: string) {
  if (!path.startsWith('/')) return
  open.value = false
  await router.push(path)
}

watch(open, (value) => { if (value) void loadConversations() })
watch(conversationId, (value, previous) => { if (value && value !== previous) void loadMessages() })
watch(() => session.activeRole, () => {
  streamController?.abort()
  conversations.value = []
  conversationId.value = ''
  messages.value = []
  markdownRenderer.clear()
  if (open.value) void loadConversations(true)
})
onBeforeUnmount(() => {
  streamController?.abort()
  markdownRenderer.clear()
})
</script>

<template>
  <button class="assistant-trigger" type="button" aria-label="打开 EduTwin 助手" title="EduTwin 助手" @click="open = true">
    <Bot :size="24" :stroke-width="2" />
  </button>

  <el-drawer v-model="open" class="assistant-drawer" size="480px" :with-header="false" append-to-body>
    <section class="assistant-panel" aria-label="EduTwin 助手">
      <header class="assistant-panel__header">
        <span class="assistant-panel__mark"><Bot :size="20" /></span>
        <div><strong>EduTwin 助手</strong><span>只读分析 · {{ session.activeRole }}</span></div>
        <button class="plain-icon-button" type="button" aria-label="关闭助手" title="关闭" @click="open = false"><X :size="19" /></button>
      </header>

      <div class="assistant-conversation-bar">
        <el-select v-model="conversationId" aria-label="切换助手会话" :loading="loading">
          <el-option v-for="item in conversations" :key="item.conversationId" :label="item.title" :value="item.conversationId" />
        </el-select>
        <el-tooltip content="新建会话"><button class="plain-icon-button" type="button" aria-label="新建会话" @click="createConversation"><MessageSquarePlus :size="18" /></button></el-tooltip>
        <el-dropdown trigger="click">
          <button class="plain-icon-button" type="button" aria-label="会话操作"><MoreHorizontal :size="19" /></button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item :icon="Pencil" @click="renameConversation">重命名</el-dropdown-item><el-dropdown-item :icon="Trash2" :disabled="hasProcessingMessage" divided @click="deleteConversation">删除会话</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </div>

      <div ref="messageList" class="assistant-messages" aria-live="polite">
        <div v-if="loading && !messages.length" class="assistant-empty"><LoaderCircle class="page-feedback__spinner" :size="24" />正在读取会话</div>
        <div v-else-if="!messages.length" class="assistant-empty"><Bot :size="26" /><strong>开始一次只读数据分析</strong><span>助手会根据问题调用当前角色有权访问的数据工具。</span></div>
        <article v-for="message in messages" v-else :key="message.messageId" class="assistant-message" :class="`assistant-message--${message.role.toLowerCase()}`">
          <div class="assistant-message__bubble">
            <span v-if="message.status === 'PROCESSING' && !message.content" class="assistant-thinking"><LoaderCircle class="page-feedback__spinner" :size="16" />{{ toolStatus || '正在生成回复' }}</span>
            <div
              v-if="message.content && message.role === 'ASSISTANT'"
              class="assistant-markdown"
              v-html="markdownRenderer.render(message.messageId, message.content)"
            />
            <p v-else-if="message.content">{{ message.content }}</p>
            <div v-if="message.status === 'FAILED'" class="assistant-message__error"><span>本次回复未完成</span><el-button v-if="message.retryable" link :icon="RotateCcw" @click="retryMessage(message)">重试</el-button></div>
          </div>
          <div v-if="message.role === 'ASSISTANT' && (message.sources.length || message.deepLinks.length)" class="assistant-message__meta">
            <span v-for="source in message.sources" :key="`${source.label}-${source.deepLink}`">{{ source.label }}</span>
            <button v-for="path in message.deepLinks" :key="path" type="button" @click="followLink(path)"><ExternalLink :size="13" />打开相关页面</button>
          </div>
        </article>
      </div>

      <form class="assistant-composer" @submit.prevent="sendMessage">
        <el-input v-model="draft" type="textarea" :rows="3" resize="none" maxlength="4000" placeholder="询问当前角色可访问的学习与业务数据" @keydown.ctrl.enter.prevent="sendMessage" />
        <el-button type="primary" :icon="Send" native-type="submit" :loading="sending" :disabled="!draft.trim()" aria-label="发送消息">发送</el-button>
      </form>
    </section>
  </el-drawer>
</template>

<style scoped>
.assistant-trigger { position: fixed; right: 24px; bottom: 24px; z-index: 24; width: 52px; height: 52px; display: grid; place-items: center; border: 1px solid rgb(255 255 255 / 32%); border-radius: 50%; color: #fff; background: var(--accent-dark); box-shadow: 0 10px 28px rgb(15 118 110 / 30%); cursor: pointer; }
.assistant-trigger:hover { background: var(--el-color-primary-dark-2); transform: translateY(-1px); }
.assistant-panel { height: 100%; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; background: var(--surface); }
.assistant-panel__header { min-height: 68px; display: flex; align-items: center; gap: 11px; padding: 12px 16px; border-bottom: 1px solid var(--border); }
.assistant-panel__mark { width: 38px; height: 38px; display: grid; place-items: center; border-radius: var(--radius-sm); color: var(--accent-dark); background: var(--accent-soft); }
.assistant-panel__header > div { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; }
.assistant-panel__header strong { color: var(--ink-strong); font-size: 15px; }
.assistant-panel__header span { color: var(--muted); font-size: 10px; }
.plain-icon-button { width: 34px; height: 34px; flex: 0 0 auto; display: grid; place-items: center; border: 0; border-radius: var(--radius-xs); color: var(--muted); background: transparent; cursor: pointer; }
.plain-icon-button:hover { color: var(--ink); background: var(--surface-soft); }
.assistant-conversation-bar { display: flex; align-items: center; gap: 5px; padding: 10px 12px; border-bottom: 1px solid var(--border); }
.assistant-conversation-bar .el-select { min-width: 0; flex: 1; }
.assistant-messages { min-height: 0; overflow-y: auto; padding: 18px 16px 24px; background: var(--surface-soft); }
.assistant-empty { min-height: 240px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--muted); text-align: center; }
.assistant-empty strong { color: var(--ink-strong); font-size: 14px; }
.assistant-empty span { max-width: 300px; font-size: 12px; line-height: 1.6; }
.assistant-message { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 16px; }
.assistant-message--user { align-items: flex-end; }
.assistant-message__bubble { max-width: 88%; padding: 11px 13px; border: 1px solid var(--border); border-radius: var(--radius-sm); color: var(--ink); background: var(--surface); box-shadow: var(--shadow-sm); }
.assistant-message--user .assistant-message__bubble { color: #fff; border-color: var(--accent-dark); background: var(--accent-dark); }
.assistant-message p { margin: 0; font-size: 13px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-markdown { min-width: 0; max-width: 100%; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.assistant-markdown :deep(> :first-child) { margin-top: 0; }
.assistant-markdown :deep(> :last-child) { margin-bottom: 0; }
.assistant-markdown :deep(p), .assistant-markdown :deep(ul), .assistant-markdown :deep(ol), .assistant-markdown :deep(blockquote), .assistant-markdown :deep(pre), .assistant-markdown :deep(table) { margin: 0 0 9px; }
.assistant-markdown :deep(p) { white-space: normal; }
.assistant-markdown :deep(h1), .assistant-markdown :deep(h2), .assistant-markdown :deep(h3), .assistant-markdown :deep(h4) { margin: 12px 0 6px; color: var(--ink-strong); font-size: 14px; line-height: 1.45; }
.assistant-markdown :deep(h1) { font-size: 16px; }
.assistant-markdown :deep(h2) { font-size: 15px; }
.assistant-markdown :deep(ul), .assistant-markdown :deep(ol) { padding-left: 20px; }
.assistant-markdown :deep(li + li) { margin-top: 3px; }
.assistant-markdown :deep(blockquote) { padding-left: 9px; border-left: 3px solid var(--border-strong); color: var(--muted); }
.assistant-markdown :deep(hr) { margin: 11px 0; border: 0; border-top: 1px solid var(--border); }
.assistant-markdown :deep(pre), .assistant-markdown :deep(table) { max-width: 100%; overflow-x: auto; }
.assistant-markdown :deep(pre) { padding: 9px 10px; border-radius: var(--radius-xs); background: var(--ink-strong); color: #fff; font-size: 11px; line-height: 1.55; }
.assistant-markdown :deep(code) { padding: 1px 4px; border-radius: 3px; background: var(--surface-soft); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 0.9em; }
.assistant-markdown :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.assistant-markdown :deep(table) { display: block; width: max-content; min-width: 100%; border-collapse: collapse; font-size: 11px; }
.assistant-markdown :deep(th), .assistant-markdown :deep(td) { padding: 6px 8px; border: 1px solid var(--border); text-align: left; white-space: nowrap; }
.assistant-markdown :deep(th) { color: var(--ink-strong); background: var(--surface-soft); }
.assistant-markdown :deep(a) { color: var(--accent-dark); text-decoration: underline; text-underline-offset: 2px; }
.assistant-thinking { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 12px; }
.assistant-message__error { display: flex; align-items: center; gap: 8px; color: var(--danger); font-size: 12px; }
.assistant-message__meta { max-width: 88%; display: flex; flex-wrap: wrap; gap: 5px; margin-top: 6px; }
.assistant-message__meta span, .assistant-message__meta button { min-height: 24px; display: inline-flex; align-items: center; gap: 4px; padding: 3px 7px; border: 1px solid var(--border); border-radius: var(--radius-xs); color: var(--muted); background: var(--surface); font-size: 10px; }
.assistant-message__meta button { color: var(--accent-dark); cursor: pointer; }
.assistant-composer { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 9px; padding: 12px; border-top: 1px solid var(--border); background: var(--surface); }
.assistant-composer .el-button { min-height: 38px; }
@media (max-width: 560px) { .assistant-trigger { right: 16px; bottom: 16px; } }
</style>

<style>
.assistant-drawer .el-drawer__body { padding: 0; overflow: hidden; }
@media (max-width: 560px) { .assistant-drawer.el-drawer { width: 100% !important; } }
</style>
