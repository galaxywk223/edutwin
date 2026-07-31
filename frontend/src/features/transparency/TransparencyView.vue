<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  CheckCircle2,
  Database,
  ExternalLink,
  Fingerprint,
  Package,
  RefreshCw,
  RotateCcw,
  ServerCog,
  Sparkles,
} from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import { businessApi } from '@/api/client/business'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import StatCard from '@/shared/components/StatCard.vue'
import type { ModelTransparency, TransparencyResponse } from '@/shared/types/api'
import type { GovernanceDatasetVersion, GovernanceModelDeployment } from '@/shared/types/business'
import { ElMessage, ElMessageBox } from 'element-plus'
import { metricColumns, modelPurposeLabel, userErrorMessage } from '@/shared/utils/displayText'
import { dateTime, number, shortId } from '@/shared/utils/format'

const catalog = ref<TransparencyResponse | null>(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref('data')
const datasets = ref<GovernanceDatasetVersion[]>([])
const deployments = ref<GovernanceModelDeployment[]>([])
const switching = ref(false)

const selectedModels = computed(() => catalog.value?.models.filter((item) => item.selected) ?? [])
function metric(model: ModelTransparency, name: string, split: 'VALIDATION' | 'TEST' = 'VALIDATION') {
  const item = model.metrics.find((value) => value.name === name && value.split === split)
  return item ? number(item.value, name === 'CPU_P95_MS' ? 1 : 4) : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [catalogValue, datasetValue, deploymentValue] = await Promise.all([
      api.adminTransparency(), businessApi.governanceDatasets(), businessApi.governanceDeployments(),
    ])
    catalog.value = catalogValue
    datasets.value = datasetValue.items
    deployments.value = deploymentValue.items
  } catch (cause) {
    error.value = userErrorMessage(cause, '透明度目录读取失败。')
  } finally {
    loading.value = false
  }
}

function lifecycleLabel(status: GovernanceDatasetVersion['lifecycleStatus']) {
  return ({ ENABLED: '已启用', DEPRECATED: '已弃用', RETIRED: '已退役' } as const)[status]
}

async function changeDatasetLifecycle(
  item: GovernanceDatasetVersion,
  status: GovernanceDatasetVersion['lifecycleStatus'],
) {
  try {
    const [impact, prompt] = await Promise.all([
      businessApi.datasetImpact(item.versionId),
      ElMessageBox.prompt('生命周期变更原因将写入审计记录。', `变更为${lifecycleLabel(status)}`, {
        inputPlaceholder: '变更原因', inputValidator: (value) => Boolean(value.trim()) || '变更原因不能为空',
      }),
    ])
    const impactText = `${impact.courseReferences} 个课程引用、${impact.activeModelReferences} 个活动模型引用、${impact.rollbackModelReferences} 个回滚模型引用`
    if (status === 'RETIRED' && !impact.canRetire) {
      await ElMessageBox.alert(`当前版本仍有 ${impactText}，暂不能退役。`, '影响预览', { type: 'warning' })
      return
    }
    await ElMessageBox.confirm(`当前存在 ${impactText}。确认变更为${lifecycleLabel(status)}？`, '影响预览', {
      type: status === 'RETIRED' ? 'warning' : 'info', confirmButtonText: '确认变更', cancelButtonText: '取消',
    })
    await businessApi.changeDatasetStatus(item.versionId, status, prompt.value.trim())
    ElMessage.success('数据版本生命周期已更新')
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '数据版本状态更新失败'))
  }
}

async function rollbackDeployment(item: GovernanceModelDeployment) {
  if (!item.rollbackVersionId) {
    ElMessage.warning('当前任务没有可用的回滚版本')
    return
  }
  try {
    const prompt = await ElMessageBox.prompt('仅回滚当前任务，原因将写入审计记录。', `回滚${modelPurposeLabel(item.taskName)}模型`, {
      inputPlaceholder: '回滚原因', inputValidator: (value) => Boolean(value.trim()) || '回滚原因不能为空',
    })
    await ElMessageBox.confirm('活动版本可能已被其他管理员更新，提交时将再次校验。确认执行逐任务回滚？', '确认模型回滚', {
      type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消',
    })
  switching.value = true
    const result = await businessApi.rollbackDeployment(
      item.taskName, item.activeVersionId, item.rollbackVersionId, prompt.value.trim(),
    )
    ElMessage.success(`模型已回滚，影响 ${result.affectedModules.length} 个业务模块`)
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '模型回滚失败'))
  } finally { switching.value = false }
}

onMounted(load)
</script>

<template>
  <div class="transparency-page">
    <PageHeader
      title="模型与数据治理"
      description="数据版本生命周期、候选评估、活动制品和回滚指针的集中管理。"
    >
      <template #actions>
        <el-button type="primary" :icon="RefreshCw" :loading="loading" @click="load">刷新目录</el-button>
      </template>
    </PageHeader>

    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <template v-if="catalog && !loading">
      <section class="stat-grid">
        <StatCard
          label="数据来源"
          :value="catalog.provenance.sources.length"
          caption="公开教育数据集"
          :icon="Database"
          tone="info"
        />
        <StatCard
          label="合成学生"
          :value="catalog.provenance.synthetic ? '2,000' : '否'"
          caption="无真实学生身份"
          :icon="Fingerprint"
          tone="success"
        />
        <StatCard
          label="候选模型"
          :value="catalog.models.length"
          caption="四个知识 + 三个风险"
          :icon="Package"
        />
        <StatCard
          label="激活制品"
          :value="selectedModels.length"
          :caption="`匹配种子 ${catalog.provenance.matchingSeed}`"
          :icon="Sparkles"
          tone="warning"
        />
      </section>

      <section class="governance-grid">
        <PanelCard padding="flush">
          <div class="governance-heading"><div><Database :size="18" /><h2>数据版本</h2></div><span>{{ datasets.length }} 个注册版本</span></div>
          <el-table :data="datasets" size="small" row-key="versionId">
            <el-table-column prop="sourceName" label="来源" min-width="150" />
            <el-table-column prop="versionId" label="版本" min-width="170" show-overflow-tooltip />
            <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.lifecycleStatus === 'ENABLED' ? 'success' : row.lifecycleStatus === 'DEPRECATED' ? 'warning' : 'info'">{{ lifecycleLabel(row.lifecycleStatus) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="150"><template #default="{ row }">
              <el-button v-if="row.lifecycleStatus === 'ENABLED'" link @click="changeDatasetLifecycle(row, 'DEPRECATED')">弃用</el-button>
              <template v-else-if="row.lifecycleStatus === 'DEPRECATED'">
                <el-button link type="primary" @click="changeDatasetLifecycle(row, 'ENABLED')">重新启用</el-button>
                <el-button link type="danger" @click="changeDatasetLifecycle(row, 'RETIRED')">退役</el-button>
              </template>
              <span v-else>不可恢复</span>
            </template></el-table-column>
          </el-table>
        </PanelCard>
        <PanelCard padding="flush">
          <div class="governance-heading"><div><ServerCog :size="18" /><h2>部署指针</h2></div><span>按任务独立回滚</span></div>
          <el-table :data="deployments" size="small" row-key="taskName">
            <el-table-column label="任务" min-width="140"><template #default="{ row }">{{ modelPurposeLabel(row.taskName) }}</template></el-table-column>
            <el-table-column prop="activeVersionId" label="活动版本" min-width="170" show-overflow-tooltip />
            <el-table-column prop="rollbackVersionId" label="回滚版本" min-width="170" show-overflow-tooltip />
            <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :icon="RotateCcw" :loading="switching" :disabled="!row.rollbackVersionId" @click="rollbackDeployment(row)">回滚</el-button></template></el-table-column>
          </el-table>
        </PanelCard>
      </section>

      <PanelCard class="transparency-tabs-card" padding="flush">
        <el-tabs v-model="activeTab" class="transparency-tabs">
          <el-tab-pane label="数据来源" name="data">
            <section class="provenance-overview">
              <div>
                <div class="section-heading">
                  <h2>演示数据谱系</h2>
                  <span>{{ dateTime(catalog.provenance.generatedAt) }} 生成</span>
                </div>
                <dl>
                  <dt>匹配版本</dt><dd>{{ catalog.provenance.matchingVersion }}</dd>
                  <dt>固定种子</dt><dd>{{ catalog.provenance.matchingSeed }}</dd>
                  <dt>数据属性</dt><dd>合成数据</dd>
                  <dt>服务器边界</dt><dd>仅保存演示库与冻结推理制品</dd>
                </dl>
              </div>
              <Fingerprint :size="72" :stroke-width="1.1" aria-hidden="true" />
            </section>

            <section class="source-list">
              <article v-for="source in catalog.provenance.sources" :key="source.sourceId" class="source-card">
                <header>
                  <div>
                    <h2>{{ source.sourceId }}</h2>
                    <span>{{ source.datasetVersion }}</span>
                  </div>
                  <a :href="source.sourceUrl" target="_blank" rel="noreferrer">
                    来源 <ExternalLink :size="13" />
                  </a>
                </header>
                <div class="source-facts">
                  <div><span>许可</span><strong>{{ source.licenseName }}</strong></div>
                  <div><span>规范行数</span><strong>{{ source.rowCount.toLocaleString('zh-CN') }}</strong></div>
                  <div><span>处理运行</span><code>{{ source.processingRunId }}</code></div>
                  <div><span>拆分种子</span><strong>{{ source.splitSeed }}</strong></div>
                </div>
                <dl class="hash-list">
                  <dt>来源 SHA-256</dt>
                  <dd><code :title="source.sourceSha256">{{ source.sourceSha256 }}</code></dd>
                  <dt>清单 SHA-256</dt>
                  <dd><code :title="source.manifestSha256">{{ source.manifestSha256 }}</code></dd>
                  <dt>配置 SHA-256</dt>
                  <dd><code :title="source.processingConfigSha256">{{ source.processingConfigSha256 }}</code></dd>
                </dl>
              </article>
            </section>
          </el-tab-pane>

          <el-tab-pane label="模型评估" name="models">
            <section class="models-section">
              <div class="section-heading">
                <div>
                  <h2>冻结候选比较</h2>
                  <span>测试集只在模型与配置冻结后评估一次</span>
                </div>
              </div>
              <div class="model-table-wrap">
                <table class="model-table">
                  <thead>
                    <tr>
                      <th>模型</th>
                      <th>用途</th>
                      <th>版本</th>
                      <th v-for="column in metricColumns" :key="column.key">{{ column.label }}</th>
                      <th>制品</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="item in catalog.models"
                      :key="`${item.model.purpose}-${item.model.modelVersion}`"
                      :class="{ 'model-row--selected': item.selected }"
                    >
                      <td>
                        <div class="model-name">
                          <CheckCircle2 v-if="item.selected" :size="15" />
                          <strong>{{ item.model.family }}</strong>
                          <span v-if="item.selected">已选择</span>
                        </div>
                      </td>
                      <td>{{ modelPurposeLabel(item.model.purpose) }}</td>
                      <td><code :title="item.model.modelVersion">{{ item.model.modelVersion }}</code></td>
                      <td v-for="column in metricColumns" :key="column.key">{{ metric(item, column.key) }}</td>
                      <td><code :title="item.model.artifactSha256">{{ shortId(item.model.artifactSha256, 12) }}</code></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>

            <section class="manifest-list">
              <div class="section-heading">
                <h2>激活模型清单</h2>
                <span>{{ dateTime(catalog.generatedAt) }} 更新</span>
              </div>
              <div v-for="item in selectedModels" :key="item.manifestSha256" class="manifest-row">
                <div>
                  <strong>{{ modelPurposeLabel(item.model.purpose) }} · {{ item.model.modelName }}</strong>
                  <span>{{ item.model.modelVersion }}</span>
                </div>
                <dl>
                  <dt>模型清单</dt><dd><code>{{ item.manifestSha256 }}</code></dd>
                  <dt>训练配置</dt><dd><code>{{ item.configSha256 }}</code></dd>
                  <dt>依赖锁</dt><dd><code>{{ item.dependencyLockSha256 }}</code></dd>
                </dl>
              </div>
            </section>
          </el-tab-pane>
        </el-tabs>
      </PanelCard>
    </template>
  </div>
</template>

<style scoped>
.transparency-tabs-card {
  margin-top: 14px;
}

.governance-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }
.governance-heading { min-height: 54px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--border); }
.governance-heading > div { display: flex; align-items: center; gap: 8px; }
.governance-heading h2 { margin: 0; font-size: 14px; }
.governance-heading span { color: var(--muted); font-size: 12px; }
@media (max-width: 1080px) { .governance-grid { grid-template-columns: 1fr; } }

.transparency-tabs {
  padding: 4px 0 0;
}

.transparency-tabs :deep(.el-tabs__header) {
  margin: 0 16px;
}

.transparency-tabs :deep(.el-tabs__content) {
  padding: 8px 18px 20px;
}

.provenance-overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
  padding: 12px 0 20px;
}

.provenance-overview > div {
  min-width: 0;
  flex: 1;
}

.provenance-overview > svg {
  flex: 0 0 auto;
  color: var(--accent-light);
}

.provenance-overview dl {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 10px 20px;
  margin: 0;
}

.provenance-overview dt {
  color: var(--muted);
  font-size: 12px;
}

.provenance-overview dd {
  margin: 0;
  font-size: 12px;
}

.source-list {
  display: grid;
  gap: 12px;
}

.source-card {
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface-soft);
}

.source-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.source-card header > div {
  min-width: 0;
}

.source-card h2 {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
}

.source-card header span {
  display: block;
  margin-top: 5px;
  color: var(--muted);
  font-size: 11px;
}

.source-card header a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  font-size: 12px;
  text-decoration: none;
}

.source-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 18px 0;
}

.source-facts div {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.source-facts span {
  color: var(--muted);
  font-size: 10px;
}

.source-facts strong,
.source-facts code {
  overflow-wrap: anywhere;
  font-size: 12px;
}

.hash-list {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr);
  gap: 8px 16px;
  margin: 0;
  padding: 14px;
  border-radius: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
}

.hash-list dt {
  color: var(--muted);
  font-size: 10px;
}

.hash-list dd {
  min-width: 0;
  margin: 0;
}

.hash-list code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 10px;
  white-space: nowrap;
}

.models-section {
  padding-top: 8px;
}

.model-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 12px;
}

.model-table {
  width: 100%;
  min-width: 1150px;
  border-collapse: collapse;
  font-size: 11px;
}

.model-table th {
  height: 42px;
  padding: 0 11px;
  color: var(--muted);
  background: var(--surface-soft);
  font-size: 10px;
  font-weight: 650;
  text-align: right;
}

.model-table th:nth-child(-n + 3),
.model-table th:last-child {
  text-align: left;
}

.model-table td {
  height: 55px;
  padding: 7px 11px;
  border-top: 1px solid var(--border);
  text-align: right;
}

.model-table td:nth-child(-n + 3),
.model-table td:last-child {
  text-align: left;
}

.model-table code {
  max-width: 185px;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 9px;
  white-space: nowrap;
}

.model-row--selected {
  background: var(--surface-tint);
}

.model-name {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--accent-dark);
}

.model-name strong {
  color: var(--ink);
  font-size: 11px;
}

.model-name span {
  font-size: 9px;
}

.manifest-list {
  margin-top: 22px;
}

.manifest-row {
  display: grid;
  grid-template-columns: minmax(170px, 0.5fr) minmax(0, 1.5fr);
  gap: 24px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}

.manifest-row > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.manifest-row strong {
  font-size: 12px;
}

.manifest-row span {
  color: var(--muted);
  font-size: 11px;
  overflow-wrap: anywhere;
}

.manifest-row dl {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 6px 12px;
  margin: 0;
}

.manifest-row dt {
  color: var(--muted);
  font-size: 10px;
}

.manifest-row dd {
  min-width: 0;
  margin: 0;
}

.manifest-row code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 9px;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .source-facts {
    grid-template-columns: repeat(2, 1fr);
  }

  .manifest-row {
    grid-template-columns: 1fr;
  }

  .provenance-overview > svg {
    display: none;
  }
}

@media (max-width: 480px) {
  .source-facts {
    grid-template-columns: 1fr;
  }

  .hash-list,
  .provenance-overview dl {
    grid-template-columns: 1fr;
  }
}
</style>
