<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, BellRing, CheckCheck, ChevronRight, Inbox, LoaderCircle, RefreshCw } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { useSessionStore } from '@/app/stores/session'
import type { UserNotification } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const session = useSessionStore()
const router = useRouter()
const open = ref(false)
const loading = ref(false)
const items = ref<UserNotification[]>([])
const unread = ref(0)
const total = ref(0)
const page = ref(0)
const pageSize = 20
let pollTimer: number | undefined

const hasMore = computed(() => items.value.length < total.value)

async function loadUnread() {
  try { unread.value = (await api.unreadNotifications()).count }
  catch { unread.value = 0 }
}

async function load(reset = true) {
  if (loading.value) return
  loading.value = true
  try {
    const targetPage = reset ? 0 : page.value + 1
    const response = await api.notifications(targetPage, pageSize)
    items.value = reset ? response.items : [...items.value, ...response.items]
    page.value = response.page
    total.value = response.total
    await loadUnread()
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '通知读取失败'))
  } finally {
    loading.value = false
  }
}

async function markAllRead() {
  if (!unread.value) return
  try {
    await api.markAllNotificationsRead()
    items.value = items.value.map((item) => ({ ...item, read: true }))
    unread.value = 0
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '通知状态更新失败'))
  }
}

async function openNotification(item: UserNotification) {
  if (!item.read) {
    try {
      await api.markNotificationRead(item.notificationId)
      item.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch (cause) {
      ElMessage.error(userErrorMessage(cause, '通知状态更新失败'))
      return
    }
  }
  if (item.deepLink?.startsWith('/')) {
    open.value = false
    await router.push(item.deepLink)
  }
}

watch(open, (value) => { if (value) void load() })
watch(() => session.activeRole, () => {
  items.value = []
  total.value = 0
  void loadUnread()
})
onMounted(() => {
  void loadUnread()
  pollTimer = window.setInterval(loadUnread, 60_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })
</script>

<template>
  <div class="notification-center">
    <el-badge :value="unread > 99 ? '99+' : unread" :hidden="unread === 0" :max="99">
      <button class="header-icon-action" type="button" aria-label="打开通知中心" title="通知中心" @click="open = true">
        <BellRing v-if="unread" :size="19" />
        <Bell v-else :size="19" />
      </button>
    </el-badge>

    <el-drawer v-model="open" class="notification-drawer" size="420px" append-to-body>
      <template #header>
        <div class="drawer-heading"><div><strong>通知中心</strong><span>{{ unread ? `${unread} 条未读` : '暂无未读通知' }}</span></div><el-button link :icon="CheckCheck" :disabled="!unread" @click="markAllRead">全部已读</el-button></div>
      </template>
      <div class="notification-list" aria-live="polite">
        <div v-if="loading && !items.length" class="notification-state"><LoaderCircle class="page-feedback__spinner" :size="22" />正在读取通知</div>
        <div v-else-if="!items.length" class="notification-state"><Inbox :size="24" />暂无通知</div>
        <button
          v-for="item in items"
          v-else
          :key="item.notificationId"
          type="button"
          class="notification-item"
          :class="{ 'notification-item--unread': !item.read }"
          @click="openNotification(item)"
        >
          <span class="notification-item__indicator" />
          <span class="notification-item__body"><strong>{{ item.title }}</strong><span>{{ item.body }}</span><small>{{ dateTime(item.createdAt) }}</small></span>
          <ChevronRight v-if="item.deepLink" :size="17" />
        </button>
        <el-button v-if="hasMore" class="notification-more" :icon="RefreshCw" :loading="loading" @click="load(false)">加载更多</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.notification-center { display: inline-flex; }
.drawer-heading { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.drawer-heading > div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.drawer-heading strong { color: var(--ink-strong); font-size: 16px; }
.drawer-heading span { color: var(--muted); font-size: 11px; }
.notification-list { display: flex; flex-direction: column; min-height: 180px; }
.notification-state { min-height: 180px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--muted); font-size: 13px; }
.notification-item { width: 100%; display: grid; grid-template-columns: 8px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 15px 4px; border: 0; border-bottom: 1px solid var(--border); color: var(--ink); background: transparent; cursor: pointer; text-align: left; }
.notification-item:hover { background: var(--surface-soft); }
.notification-item__indicator { width: 7px; height: 7px; border-radius: 50%; background: transparent; }
.notification-item--unread .notification-item__indicator { background: var(--accent); }
.notification-item__body { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.notification-item__body strong { color: var(--ink-strong); font-size: 13px; }
.notification-item__body span { color: var(--muted); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.notification-item__body small { color: var(--subtle); font-size: 10px; }
.notification-more { align-self: center; margin: 14px 0; }
</style>

<style>
@media (max-width: 560px) {
  .notification-drawer.el-drawer { width: 100% !important; }
}
</style>
