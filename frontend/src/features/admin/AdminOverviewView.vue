<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ContactRound, Database, GraduationCap, RefreshCw, ServerCog, Users } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { AdminOverview } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const overview = ref<AdminOverview | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await api.adminOverview()
  } catch (cause) {
    error.value = userErrorMessage(cause, '系统概览读取失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      title="系统概览"
      description="平台身份、数据和模型部署的当前运行状态。"
    >
      <template #actions>
        <el-button type="primary" :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <template v-if="overview && !loading">
      <section class="stat-grid">
        <StatCard label="有效账号" :value="overview.enabledUsers" caption="当前可登录" :icon="Users" />
        <StatCard label="教师" :value="overview.teachers" caption="教学身份" :icon="GraduationCap" tone="info" />
        <StatCard label="辅导员" :value="overview.counselors" caption="只读学业观察" :icon="ContactRound" tone="success" />
        <StatCard label="有效数据版本" :value="overview.enabledDatasets" caption="允许引用" :icon="Database" tone="warning" />
      </section>

      <PanelCard class="overview-band">
        <div class="overview-band__icon"><ServerCog :size="28" /></div>
        <div>
          <h2>模型部署注册表</h2>
          <p>
            {{ overview.modelDeployments }} 个任务指针处于管理范围，概览生成于
            {{ dateTime(overview.generatedAt) }}。
          </p>
        </div>
      </PanelCard>
    </template>
  </div>
</template>

<style scoped>
.stat-grid {
  margin-bottom: 14px;
}

.overview-band :deep(.panel-card__body) {
  display: flex;
  align-items: center;
  gap: 18px;
}

.overview-band__icon {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  color: var(--accent-dark);
  background: var(--accent-soft);
}

.overview-band h2 {
  margin: 0 0 5px;
  font-size: 16px;
}

.overview-band p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}
</style>
