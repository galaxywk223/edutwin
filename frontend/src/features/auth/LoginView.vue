<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, GraduationCap, HeartHandshake, LockKeyhole, Presentation, Shield, ShieldCheck, UserRound } from '@lucide/vue'

import { useSessionStore } from '@/app/stores/session'
import { showcaseUiMode } from '@/showcase/runtime'
import type { UserRole } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const demoRoles = [
  { role: 'STUDENT' as const, username: 'demo.student', label: '学生', detail: '课程、画像与计划', icon: GraduationCap },
  { role: 'TEACHER' as const, username: 'demo.teacher', label: '教师', detail: '教学分析与干预', icon: Presentation },
  { role: 'COUNSELOR' as const, username: 'demo.counselor', label: '辅导员', detail: '班级与风险跟进', icon: HeartHandshake },
  { role: 'ADMIN' as const, username: 'demo.admin', label: '管理员', detail: '治理与透明度', icon: ShieldCheck },
]

function landing(role: UserRole) {
  if (role === 'ADMIN') return '/admin/overview'
  if (role === 'COUNSELOR') return '/counselor/students'
  return role === 'TEACHER' ? '/teacher/courses' : '/student/courses'
}

async function finishLogin(account: string, secret: string) {
  const user = await session.login(account, secret)
  const activeRole = user.activeRole ?? user.role
  const fallback = user.mustChangePassword ? '/password-change' : landing(activeRole)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : fallback
  username.value = ''
  password.value = ''
  await router.replace(redirect)
}

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入账号和密码。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await finishLogin(username.value.trim(), password.value)
  } catch (cause) {
    error.value = userErrorMessage(cause, '认证服务暂时不可用。')
  } finally {
    loading.value = false
  }
}

async function enterDemo(account: string) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await finishLogin(account, 'showcase')
  } catch (cause) {
    error.value = userErrorMessage(cause, '演示入口暂时不可用。')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-view">
    <section class="login-brand">
      <div class="login-brand__mesh" aria-hidden="true" />
      <div class="login-brand__glow" aria-hidden="true" />
      <div class="login-brand__wave" aria-hidden="true" />

      <div class="login-brand__name">
        <span><Activity :size="20" :stroke-width="2.4" /></span>
        EduTwin
      </div>

      <div class="login-brand__body">
        <div class="login-brand__copy">
          <h1>让每次学习，<br />都形成可追踪的成长记录</h1>
          <p class="login-brand__lead">面向教师、学生与辅导员的一体化学习工作台。</p>
        </div>

        <ul class="login-brand__features">
          <li>
            <span class="login-brand__feature-icon"><UserRound :size="18" :stroke-width="2" /></span>
            <div>
              <strong>学情画像</strong>
              <span>多维数据，全面洞察</span>
            </div>
          </li>
          <li>
            <span class="login-brand__feature-icon"><Shield :size="18" :stroke-width="2" /></span>
            <div>
              <strong>预警追踪</strong>
              <span>风险识别，及时干预</span>
            </div>
          </li>
        </ul>
      </div>
    </section>

    <section class="login-panel">
      <form class="login-form" autocomplete="off" @submit.prevent="submit">
        <header>
          <h2>登录工作台</h2>
          <p>统一入口，按角色进入系统</p>
        </header>

        <label>
          <span>账号</span>
          <el-input v-model="username" size="large" autocomplete="off" placeholder="请输入账号">
            <template #prefix><UserRound :size="16" /></template>
          </el-input>
        </label>

        <label>
          <span>密码</span>
          <el-input
            v-model="password"
            size="large"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请输入密码"
            @keyup.enter="submit"
          >
            <template #prefix><LockKeyhole :size="16" /></template>
          </el-input>
        </label>

        <div v-if="error" class="login-form__error" role="alert">{{ error }}</div>

        <el-button type="primary" size="large" native-type="submit" :loading="loading">
          进入工作台
        </el-button>

        <template v-if="showcaseUiMode">
          <div class="demo-divider"><span>无需账号，选择演示角色</span></div>
          <div class="demo-role-grid" aria-label="演示角色">
            <button
              v-for="item in demoRoles"
              :key="item.role"
              class="demo-role"
              type="button"
              :disabled="loading"
              @click="enterDemo(item.username)"
            >
              <component :is="item.icon" :size="18" :stroke-width="1.9" />
              <span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span>
            </button>
          </div>
          <p class="demo-boundary">仅使用确定性合成数据，管理类写操作已禁用。</p>
        </template>
      </form>
      <footer>高校智慧教学综合平台</footer>
    </section>
  </main>
</template>

<style scoped>
.login-view {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  background: #f4f7fb;
}

/* ─── Brand panel ─── */

.login-brand {
  position: relative;
  overflow: hidden;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 40px 52px 48px;
  color: #ffffff;
  background:
    radial-gradient(ellipse 80% 50% at 50% 100%, rgb(13 148 136 / 35%), transparent 55%),
    radial-gradient(circle at 15% 20%, rgb(20 184 166 / 18%), transparent 40%),
    linear-gradient(165deg, #041526 0%, #06253a 42%, #043442 100%);
}

.login-brand__mesh {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgb(94 234 212 / 4%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(94 234 212 / 4%) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: linear-gradient(180deg, transparent 0%, #000 45%, #000 100%);
  pointer-events: none;
  opacity: 0.55;
}

.login-brand__glow {
  position: absolute;
  left: -10%;
  bottom: -18%;
  width: 90%;
  height: 58%;
  border-radius: 50%;
  background: radial-gradient(ellipse at center, rgb(20 184 166 / 32%), transparent 68%);
  filter: blur(8px);
  pointer-events: none;
}

.login-brand__wave {
  position: absolute;
  left: -20%;
  right: -20%;
  bottom: -8%;
  height: 46%;
  background:
    radial-gradient(ellipse 70% 55% at 30% 70%, rgb(45 212 191 / 22%), transparent 60%),
    radial-gradient(ellipse 60% 40% at 70% 80%, rgb(13 148 136 / 28%), transparent 55%);
  pointer-events: none;
}

.login-brand__wave::before {
  content: '';
  position: absolute;
  inset: 10% 5% 0;
  border-radius: 50% 50% 0 0;
  border: 1px solid rgb(94 234 212 / 12%);
  border-bottom: 0;
  transform: perspective(400px) rotateX(52deg);
  opacity: 0.7;
}

.login-brand__name {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.login-brand__name span {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: #ffffff;
  background: linear-gradient(145deg, #2dd4bf, #0d9488);
  box-shadow: 0 10px 24px rgb(13 148 136 / 35%);
}

.login-brand__body {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 540px;
  padding: 48px 0 24px;
}

.login-brand__copy h1 {
  margin: 0;
  font-size: clamp(28px, 3.2vw, 40px);
  line-height: 1.32;
  font-weight: 700;
  letter-spacing: 0;
}

.login-brand__lead {
  max-width: 420px;
  margin: 18px 0 0;
  color: rgb(203 213 225 / 88%);
  font-size: 15px;
  line-height: 1.75;
}

.login-brand__features {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin: 40px 0 0;
  padding: 0;
  list-style: none;
}

.login-brand__features li {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 78px;
  padding: 14px 16px;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 14px;
  background: rgb(8 24 40 / 42%);
  backdrop-filter: blur(10px);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 6%);
}

.login-brand__feature-icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #5eead4;
  background: rgb(20 184 166 / 16%);
  border: 1px solid rgb(94 234 212 / 22%);
}

.login-brand__features div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.login-brand__features strong {
  font-size: 14px;
  font-weight: 650;
  color: #f8fafc;
}

.login-brand__features span {
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.4;
}

/* ─── Login panel ─── */

.login-panel {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
  background:
    radial-gradient(circle at 70% 20%, rgb(13 148 136 / 5%), transparent 28%),
    #f4f7fb;
}

.login-form {
  width: min(100%, 420px);
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 40px 36px 32px;
  border: 1px solid rgb(226 232 240 / 90%);
  border-radius: 20px;
  background: #ffffff;
  box-shadow:
    0 4px 6px rgb(15 23 42 / 2%),
    0 18px 48px rgb(15 23 42 / 8%);
}

.login-form header {
  margin-bottom: 8px;
  text-align: center;
}

.login-form h2 {
  margin: 0;
  font-size: 26px;
  line-height: 1.25;
  font-weight: 700;
  color: #0f172a;
}

.login-form header p {
  margin: 10px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.login-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.login-form label > span {
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 48px;
  padding: 0 14px;
  border-radius: 12px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #e2e8f0 inset !important;
  transition:
    box-shadow 160ms ease,
    background 160ms ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #cbd5e1 inset !important;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background: #ffffff;
  box-shadow: 0 0 0 1px #0d9488 inset, 0 0 0 3px rgb(13 148 136 / 12%) !important;
}

.login-form :deep(.el-input__prefix) {
  color: #94a3b8;
}

.login-form :deep(.el-input__inner::placeholder) {
  color: #94a3b8;
}

.login-form .el-button {
  width: 100%;
  min-height: 48px;
  margin-top: 6px;
  border: 0;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
  box-shadow: 0 10px 24px rgb(13 148 136 / 28%);
}

.login-form .el-button--primary {
  --el-button-bg-color: #0d9488;
  --el-button-border-color: #0d9488;
  --el-button-hover-bg-color: #0f766e;
  --el-button-hover-border-color: #0f766e;
  --el-button-active-bg-color: #115e59;
  --el-button-active-border-color: #115e59;
}

.login-form__error {
  margin-top: -4px;
  padding: 10px 12px;
  border-left: 3px solid #dc2626;
  border-radius: 0 10px 10px 0;
  color: #dc2626;
  background: #fef2f2;
  font-size: 12px;
  line-height: 1.5;
}

.demo-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #64748b;
  font-size: 12px;
}

.demo-divider::before,
.demo-divider::after {
  content: '';
  height: 1px;
  flex: 1;
  background: #e2e8f0;
}

.demo-role-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.demo-role {
  min-width: 0;
  min-height: 62px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 11px;
  border: 1px solid #dbe4ee;
  border-radius: 8px;
  color: #0f766e;
  background: #f8fafc;
  cursor: pointer;
  text-align: left;
  transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.demo-role:hover {
  border-color: #5eead4;
  background: #f0fdfa;
  transform: translateY(-1px);
}

.demo-role:focus-visible {
  outline: 3px solid rgb(13 148 136 / 20%);
  outline-offset: 2px;
}

.demo-role:disabled {
  cursor: wait;
  opacity: 0.6;
}

.demo-role > span {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.demo-role strong {
  color: #1e293b;
  font-size: 13px;
  font-weight: 650;
}

.demo-role small {
  color: #64748b;
  font-size: 10px;
  line-height: 1.35;
  white-space: normal;
}

.demo-boundary {
  margin: -4px 0 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
}

.login-panel footer {
  margin-top: 28px;
  color: #94a3b8;
  font-size: 12px;
}

/* ─── Responsive ─── */

@media (max-width: 960px) {
  .login-view {
    grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  }

  .login-brand {
    padding: 32px 36px 40px;
  }

  .login-brand__copy h1 {
    font-size: 30px;
  }
}

@media (max-width: 820px) {
  .login-view {
    display: block;
  }

  .login-brand {
    min-height: auto;
    padding: 28px 24px 36px;
  }

  .login-brand__body {
    padding: 36px 0 8px;
  }

  .login-brand__copy h1 {
    font-size: 28px;
  }

  .login-brand__copy h1 br {
    display: none;
  }

  .login-brand__features {
    margin-top: 28px;
  }

  .login-brand__wave::before {
    display: none;
  }

  .login-panel {
    min-height: auto;
    padding: 28px 18px 40px;
  }

  .login-form {
    padding: 28px 22px 24px;
  }

  .login-panel footer {
    text-align: center;
  }
}

@media (max-width: 480px) {
  .login-brand__features {
    grid-template-columns: 1fr;
  }

  .login-brand__features li {
    min-height: 68px;
  }

  .demo-role-grid {
    grid-template-columns: 1fr;
  }
}
</style>
