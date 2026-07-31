<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, KeyRound } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { useSessionStore } from '@/app/stores/session'
import { userErrorMessage } from '@/shared/utils/displayText'

const session = useSessionStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')
const forced = session.user?.mustChangePassword ?? false

async function submit() {
  if (newPassword.value.length < 12 || newPassword.value !== confirmPassword.value) {
    error.value = '新密码至少 12 位，且两次输入必须一致。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await api.changePassword(currentPassword.value, newPassword.value)
    session.clear()
    await router.replace('/login')
  } catch (cause) {
    error.value = userErrorMessage(cause, '密码修改失败。')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="password-change-page">
    <form class="password-change-form" @submit.prevent="submit">
      <div class="password-change-brand">
        <span class="password-change-brand__mark"><Activity :size="18" /></span>
        <span>EduTwin</span>
      </div>
      <div class="password-change-icon"><KeyRound :size="22" /></div>
      <h1>{{ forced ? '修改初始密码' : '修改密码' }}</h1>
      <p>{{ forced ? '账号使用临时密码登录，完成修改后需重新登录进入工作区。' : '密码更新后当前会话将退出，需使用新密码重新登录。' }}</p>
      <div v-if="error" class="password-change-error" role="alert">{{ error }}</div>
      <label>
        <span>当前密码</span>
        <el-input v-model="currentPassword" type="password" show-password placeholder="当前密码" size="large" />
      </label>
      <label>
        <span>新密码</span>
        <el-input v-model="newPassword" type="password" show-password placeholder="至少 12 位" size="large" />
      </label>
      <label>
        <span>确认新密码</span>
        <el-input v-model="confirmPassword" type="password" show-password placeholder="再次输入新密码" size="large" />
      </label>
      <el-button native-type="submit" type="primary" size="large" :loading="loading">确认修改</el-button>
    </form>
  </main>
</template>

<style scoped>
.password-change-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgb(45 212 191 / 12%), transparent 32%),
    var(--page-bg);
}

.password-change-form {
  width: min(420px, 100%);
  display: grid;
  gap: 14px;
  padding: 28px;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: var(--shadow);
}

.password-change-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ink-strong);
  font-size: 14px;
  font-weight: 700;
}

.password-change-brand__mark {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: #0f172a;
  background: linear-gradient(135deg, #5eead4, #2dd4bf);
}

.password-change-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  margin-top: 4px;
  color: var(--accent-dark);
  background: var(--accent-soft);
  border-radius: 12px;
}

.password-change-form h1 {
  margin: 0;
  font-size: 24px;
  color: var(--ink-strong);
}

.password-change-form p {
  margin: -4px 0 4px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.password-change-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.password-change-form label > span {
  color: #374151;
  font-size: 12px;
  font-weight: 600;
}

.password-change-form :deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 10px;
}

.password-change-form .el-button {
  width: 100%;
  min-height: 44px;
  margin-top: 4px;
  border-radius: 10px;
}

.password-change-error {
  padding: 10px 12px;
  border-left: 3px solid var(--danger);
  border-radius: 0 8px 8px 0;
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 12px;
  line-height: 1.5;
}
</style>
