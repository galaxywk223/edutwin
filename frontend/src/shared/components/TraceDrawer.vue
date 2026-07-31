<script setup lang="ts">
import { computed } from 'vue'

import type { TraceRef } from '@/shared/types/api'
import { modelPurposeLabel } from '@/shared/utils/displayText'
import { shortId } from '@/shared/utils/format'

const props = defineProps<{
  modelValue: boolean
  trace: TraceRef | null
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const rows = computed(() => {
  if (!props.trace) return []
  return [
    ['关联标识', props.trace.correlationId],
    ['答题事件', props.trace.answerEventId],
    ['分析任务', props.trace.analysisJobId],
    ['画像记录', props.trace.snapshotId],
  ]
})
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="追溯链路"
    size="min(520px, 92vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="trace" class="trace-drawer">
      <dl class="trace-drawer__ids">
        <template v-for="row in rows" :key="row[0]">
          <dt>{{ row[0] }}</dt>
          <dd :title="row[1]">{{ row[1] }}</dd>
        </template>
      </dl>

      <section>
        <h3>数据版本</h3>
        <div v-for="item in trace.dataVersions" :key="item.sourceId" class="trace-row">
          <div>
            <strong>{{ item.sourceId }}</strong>
            <span>{{ item.version }}</span>
          </div>
          <code :title="item.manifestSha256">{{ shortId(item.manifestSha256, 12) }}</code>
        </div>
      </section>

      <section>
        <h3>有效模型版本</h3>
        <div
          v-for="item in trace.effectiveModelVersions"
          :key="`${item.purpose}-${item.modelVersion}`"
          class="trace-row"
        >
          <div>
            <strong>{{ modelPurposeLabel(item.purpose) }} · {{ item.family }}</strong>
            <span>{{ item.modelVersion }}</span>
          </div>
          <code :title="item.artifactSha256">{{ shortId(item.artifactSha256, 12) }}</code>
        </div>
      </section>
    </div>
  </el-drawer>
</template>
