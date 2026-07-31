<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { KeyRound, Mail, Palette, Phone, Save, ShieldCheck, UserRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { api } from '@/api/client/edutwin'
import { useSessionStore } from '@/app/stores/session'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import type { ProfileAvatar, ProfileTheme, UserProfile, UserRole } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const session = useSessionStore()
const router = useRouter()
const profile = ref<UserProfile | null>(session.profile)
const loading = ref(!profile.value)
const saving = ref(false)
const error = ref('')
const form = reactive<{ email: string; phone: string; avatarKey: ProfileAvatar; themeColor: ProfileTheme }>({
  email: '', phone: '', avatarKey: 'avatar-1', themeColor: 'teal',
})

const roleLabels: Record<UserRole, string> = {
  ADMIN: '系统管理员', TEACHER: '教师', STUDENT: '学生', COUNSELOR: '辅导员',
}
const avatarOptions = [
  { value: 'avatar-1', label: '默认' },
  { value: 'avatar-2', label: '学术' },
  { value: 'avatar-3', label: '守护' },
] satisfies { value: ProfileAvatar; label: string }[]
const themeOptions = [
  { value: 'teal', label: '青绿', color: '#0f766e' },
  { value: 'blue', label: '海蓝', color: '#2563eb' },
  { value: 'violet', label: '紫罗兰', color: '#7c3aed' },
  { value: 'rose', label: '玫红', color: '#be123c' },
] satisfies { value: ProfileTheme; label: string; color: string }[]
const organizations = computed(() => profile.value?.organizations ?? [])

function syncForm(value: UserProfile) {
  Object.assign(form, {
    email: value.email ?? '',
    phone: value.phone ?? '',
    avatarKey: value.avatarKey || 'avatar-1',
    themeColor: value.themeColor || 'teal',
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    profile.value = await api.profile()
    session.setProfile(profile.value)
    syncForm(profile.value)
  } catch (cause) {
    error.value = userErrorMessage(cause, '个人资料读取失败。')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    profile.value = await api.updateProfile({
      email: form.email.trim() || null,
      phone: form.phone.trim() || null,
      avatarKey: form.avatarKey,
      themeColor: form.themeColor,
    })
    session.setProfile(profile.value)
    syncForm(profile.value)
    ElMessage.success('个人资料已更新')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '个人资料更新失败'))
  } finally {
    saving.value = false
  }
}

onMounted(() => profile.value ? syncForm(profile.value) : void load())
</script>

<template>
  <div>
    <PageHeader title="个人中心" description="账号身份信息由学校统一维护，联系方式与界面偏好可自行设置。">
      <template #actions>
        <el-button :icon="KeyRound" @click="router.push('/password-change')">修改密码</el-button>
        <el-button type="primary" :icon="Save" :loading="saving" :disabled="!profile" @click="save">保存设置</el-button>
      </template>
    </PageHeader>
    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <div v-if="profile" class="profile-layout">
      <PanelCard title="官方身份" subtitle="姓名、编号、角色与组织归属为只读信息">
        <dl class="profile-facts">
          <div><dt>姓名</dt><dd>{{ profile.displayName }}</dd></div>
          <div><dt>登录账号</dt><dd>{{ profile.username }}</dd></div>
          <div><dt>学号 / 工号</dt><dd>{{ profile.officialId || '未设置' }}</dd></div>
          <div>
            <dt>角色</dt>
            <dd class="profile-tags"><el-tag v-for="role in profile.roles" :key="role" effect="plain">{{ roleLabels[role] }}</el-tag></dd>
          </div>
          <div class="profile-facts__wide">
            <dt>组织归属</dt>
            <dd v-if="organizations.length" class="profile-organizations">
              <span v-for="organization in organizations" :key="organization.organizationId">
                <ShieldCheck :size="15" />{{ organization.displayName }}<small>{{ organization.code }}</small>
              </span>
            </dd>
            <dd v-else>未设置</dd>
          </div>
        </dl>
      </PanelCard>

      <PanelCard title="联系方式与偏好" subtitle="头像使用系统预设图标，主题在当前账号中保存">
        <el-form label-position="top" class="profile-form">
          <div class="profile-form__two">
            <el-form-item label="邮箱"><el-input v-model="form.email" :prefix-icon="Mail" placeholder="name@example.edu.cn" /></el-form-item>
            <el-form-item label="手机号"><el-input v-model="form.phone" :prefix-icon="Phone" placeholder="用于校内联系" /></el-form-item>
          </div>
          <el-form-item label="预设头像">
            <el-radio-group v-model="form.avatarKey" class="profile-option-group">
              <el-radio-button v-for="option in avatarOptions" :key="option.value" :value="option.value">
                <UserRound :size="16" />{{ option.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="界面主题">
            <el-radio-group v-model="form.themeColor" class="profile-option-group profile-theme-options">
              <el-radio-button v-for="option in themeOptions" :key="option.value" :value="option.value">
                <span class="theme-swatch" :style="{ backgroundColor: option.color }" />{{ option.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <div class="profile-preference-note"><Palette :size="16" />主题色应用于工作台强调色，不改变登录页品牌样式。</div>
        </el-form>
      </PanelCard>
    </div>
  </div>
</template>

<style scoped>
.profile-layout { display: grid; grid-template-columns: minmax(320px, .8fr) minmax(420px, 1.2fr); gap: 16px; align-items: start; }
.profile-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 24px; margin: 0; }
.profile-facts div { min-width: 0; }
.profile-facts dt { margin-bottom: 5px; color: var(--muted); font-size: 12px; }
.profile-facts dd { margin: 0; color: var(--ink-strong); font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.profile-facts__wide { grid-column: 1 / -1; }
.profile-tags, .profile-organizations { display: flex; flex-wrap: wrap; gap: 7px; }
.profile-organizations > span { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 6px 9px; border: 1px solid var(--border); border-radius: var(--radius-xs); background: var(--surface-soft); }
.profile-organizations small { color: var(--muted); font-weight: 500; }
.profile-form__two { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.profile-option-group { display: flex; flex-wrap: wrap; }
.profile-option-group :deep(.el-radio-button__inner) { display: inline-flex; align-items: center; gap: 7px; }
.theme-swatch { width: 14px; height: 14px; border-radius: 4px; box-shadow: inset 0 0 0 1px rgb(15 23 42 / 12%); }
.profile-preference-note { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 12px; }
@media (max-width: 900px) { .profile-layout { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .profile-facts, .profile-form__two { grid-template-columns: 1fr; } .profile-facts__wide { grid-column: auto; } }
</style>
