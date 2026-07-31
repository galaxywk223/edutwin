<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, KeyRound, RefreshCw, RotateCcw, Save, ShieldAlert } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import type { AdminAiConfiguration } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const configuration = ref<AdminAiConfiguration | null>(null)
const loading = ref(true)
const saving = ref(false)
const restoring = ref(false)
const error = ref('')
const form = reactive({ enabled: false, apiBaseUrl: '', model: '', apiKey: '' })

const sourceLabel = computed(() => configuration.value?.source === 'DATABASE' ? '管理员配置' : '环境默认')
const testLabel = computed(() => ({
  PASSED: '验证通过',
  NOT_TESTED: '尚未验证',
  SKIPPED_DISABLED: '已停用',
})[configuration.value?.lastTestStatus ?? 'NOT_TESTED'])

function syncForm(value: AdminAiConfiguration) {
  form.enabled = value.enabled
  form.apiBaseUrl = value.apiBaseUrl
  form.model = value.model
  form.apiKey = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const value = await api.adminAiConfiguration()
    configuration.value = value
    syncForm(value)
  } catch (cause) {
    error.value = userErrorMessage(cause, '大模型配置读取失败。')
  } finally {
    loading.value = false
  }
}

async function applyConfiguration() {
  if (!configuration.value) return
  if (!form.apiBaseUrl.trim() || !form.model.trim()) {
    ElMessage.warning('API 基础地址和模型名称不能为空')
    return
  }
  if (form.enabled && !configuration.value.apiKeyConfigured && !form.apiKey.trim()) {
    ElMessage.warning('启用大模型前必须填写 API Key')
    return
  }
  saving.value = true
  try {
    const result = await api.activateAdminAiConfiguration({
      expectedRevision: configuration.value.revision,
      enabled: form.enabled,
      apiBaseUrl: form.apiBaseUrl.trim(),
      model: form.model.trim(),
      ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}),
    })
    configuration.value = result.configuration
    syncForm(result.configuration)
    ElMessage.success(result.testStatus === 'PASSED' ? '连接验证通过，配置已生效' : '大模型已停用')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '配置验证或保存失败'))
  } finally {
    saving.value = false
  }
}

async function restoreEnvironment() {
  if (!configuration.value) return
  try {
    await ElMessageBox.confirm(
      '活动配置将切换回服务器环境变量。环境配置处于启用状态时会先完成一次真实工具调用验证。',
      '恢复环境默认',
      { type: 'warning', confirmButtonText: '验证并恢复', cancelButtonText: '取消' },
    )
    restoring.value = true
    const result = await api.restoreAdminAiEnvironment(configuration.value.revision)
    configuration.value = result.configuration
    syncForm(result.configuration)
    ElMessage.success('已恢复环境默认配置')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '环境配置恢复失败'))
  } finally {
    restoring.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ai-configuration-page">
    <PageHeader title="大模型配置" description="系统助手与学情诊断共用的 OpenAI 兼容接口。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="configuration?.source === 'DATABASE'"
          :icon="RotateCcw"
          :loading="restoring"
          :disabled="saving"
          @click="restoreEnvironment"
        >恢复环境默认</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <PanelCard v-if="configuration && !loading" padding="flush" class="ai-settings-panel">
      <div class="configuration-status">
        <div class="configuration-status__item">
          <span>配置来源</span>
          <strong>{{ sourceLabel }}</strong>
        </div>
        <div class="configuration-status__item">
          <span>连接状态</span>
          <strong class="status-value" :class="{ 'status-value--ok': configuration.lastTestStatus === 'PASSED' }">
            <CheckCircle2 v-if="configuration.lastTestStatus === 'PASSED'" :size="16" />
            <ShieldAlert v-else :size="16" />
            {{ testLabel }}
          </strong>
        </div>
        <div class="configuration-status__item">
          <span>最近验证</span>
          <strong>{{ configuration.lastTestedAt ? dateTime(configuration.lastTestedAt) : '—' }}</strong>
        </div>
        <div class="configuration-status__item">
          <span>响应耗时</span>
          <strong>{{ configuration.lastTestLatencyMs === null ? '—' : `${configuration.lastTestLatencyMs} ms` }}</strong>
        </div>
      </div>

      <div v-if="!configuration.writeAvailable" class="configuration-warning">
        <ShieldAlert :size="18" />
        <span>服务器未配置运行时加密密钥，当前仅可使用环境默认配置。</span>
      </div>

      <el-form class="configuration-form" label-position="top" @submit.prevent="applyConfiguration">
        <div class="configuration-toggle">
          <div>
            <strong>启用大模型</strong>
            <span>停用后，助手不可用，学情诊断自动使用模板降级。</span>
          </div>
          <el-switch v-model="form.enabled" :disabled="!configuration.writeAvailable || saving" />
        </div>

        <div class="configuration-form__grid">
          <el-form-item label="API 基础地址">
            <el-input
              v-model="form.apiBaseUrl"
              maxlength="500"
              placeholder="https://api.example.com/v1"
              :disabled="!configuration.writeAvailable || saving"
            />
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input
              v-model="form.model"
              maxlength="200"
              placeholder="model-name"
              :disabled="!configuration.writeAvailable || saving"
            />
          </el-form-item>
        </div>

        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            maxlength="512"
            autocomplete="new-password"
            :placeholder="configuration.apiKeyConfigured ? '已配置，留空保持不变' : '输入 API Key'"
            :disabled="!configuration.writeAvailable || saving"
          >
            <template #prefix><KeyRound :size="16" /></template>
          </el-input>
        </el-form-item>

        <div class="configuration-actions">
          <span v-if="configuration.updatedBy">
            最近由 {{ configuration.updatedBy }} 更新于 {{ dateTime(configuration.updatedAt) }}
          </span>
          <span v-else>当前版本来自服务器环境变量</span>
          <el-button
            native-type="submit"
            type="primary"
            :icon="Save"
            :loading="saving"
            :disabled="!configuration.writeAvailable || restoring"
          >{{ form.enabled ? '测试并应用' : '保存停用状态' }}</el-button>
        </div>
      </el-form>
    </PanelCard>
  </div>
</template>

<style scoped>
.ai-settings-panel { overflow: hidden; }
.configuration-status {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
}
.configuration-status__item { min-width: 0; padding: 16px 18px; border-right: 1px solid var(--border); }
.configuration-status__item:last-child { border-right: 0; }
.configuration-status__item span { display: block; margin-bottom: 5px; color: var(--muted); font-size: 12px; }
.configuration-status__item strong { display: flex; align-items: center; gap: 6px; min-height: 22px; font-size: 14px; }
.status-value { color: var(--warning); }
.status-value--ok { color: var(--success); }
.configuration-warning {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 20px 24px 0;
  padding: 12px 14px;
  border: 1px solid var(--warning);
  color: var(--warning);
  background: var(--warning-soft);
}
.configuration-form { padding: 24px; }
.configuration-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--border);
}
.configuration-toggle strong { display: block; margin-bottom: 4px; font-size: 15px; }
.configuration-toggle span { display: block; color: var(--muted); font-size: 13px; }
.configuration-form__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.configuration-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 10px; }
.configuration-actions > span { color: var(--muted); font-size: 13px; }
@media (max-width: 860px) {
  .configuration-status { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .configuration-status__item:nth-child(2) { border-right: 0; }
  .configuration-status__item:nth-child(-n + 2) { border-bottom: 1px solid var(--border); }
}
@media (max-width: 600px) {
  .configuration-status { grid-template-columns: 1fr; }
  .configuration-status__item { border-right: 0; border-bottom: 1px solid var(--border); }
  .configuration-status__item:last-child { border-bottom: 0; }
  .configuration-form { padding: 18px; }
  .configuration-form__grid { grid-template-columns: 1fr; gap: 0; }
  .configuration-toggle, .configuration-actions { align-items: flex-start; }
  .configuration-actions { flex-direction: column; }
  .configuration-actions :deep(.el-button) { width: 100%; }
}
</style>
