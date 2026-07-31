<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { History, RefreshCw } from '@lucide/vue'

import { api } from '@/api/client/edutwin'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import type { AnswerHistoryItem, AnswerHistoryPage } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const props = defineProps<{ courseId: string; refreshKey: number }>()
const emit = defineEmits<{ totalChange: [total: number] }>()

const history = ref<AnswerHistoryPage | null>(null)
const loading = ref(true)
const error = ref('')
const currentPage = ref(1)
const pageSize = ref(20)

function duration(milliseconds: number) {
  if (milliseconds < 1_000) return `${milliseconds} 毫秒`
  const seconds = milliseconds / 1_000
  return `${seconds.toFixed(seconds < 10 ? 1 : 0)} 秒`
}

function sourceLabel(source: AnswerHistoryItem['sourceType']) {
  return source === 'ONLINE' ? '在线答题' : '历史导入'
}

async function load(resetPage = false) {
  if (resetPage) currentPage.value = 1
  loading.value = true
  error.value = ''
  try {
    history.value = await api.answerHistory(
      props.courseId,
      currentPage.value - 1,
      pageSize.value,
    )
    emit('totalChange', history.value.total)
  } catch (cause) {
    error.value = userErrorMessage(cause, '答题记录读取失败。')
  } finally {
    loading.value = false
  }
}

watch(() => props.refreshKey, () => void load(true))
watch(() => props.courseId, () => void load(true))
onMounted(() => void load())
</script>

<template>
  <section class="practice-history">
    <header class="practice-history__header">
      <div>
        <h2>答题记录</h2>
        <span>{{ history ? `共 ${history.total} 条` : '当前课程' }}</span>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="load()">刷新记录</el-button>
    </header>

    <PageFeedback
      v-if="loading || error"
      :loading="loading"
      :error="error"
      @retry="load()"
    />

    <div v-else-if="history" class="lms-table-section practice-history__surface">
      <div v-if="history.items.length === 0" class="lms-empty-state">
        <History :size="26" />
        <strong>暂无答题记录</strong>
      </div>

      <template v-else>
        <el-table
          class="practice-history__table"
          :data="history.items"
          row-key="answerEventId"
        >
          <el-table-column type="expand" width="46">
            <template #default="{ row }">
              <div class="practice-history__detail">
                <div>
                  <span>所选答案</span>
                  <strong>{{ row.selectedChoice.label }}</strong>
                </div>
                <div>
                  <span>正确答案</span>
                  <strong>{{ row.correctChoice.label }}</strong>
                </div>
                <div>
                  <span>响应耗时</span>
                  <strong>{{ duration(row.responseTimeMs) }}</strong>
                </div>
                <div class="practice-history__skills">
                  <span>关联知识点</span>
                  <div>
                    <el-tag
                      v-for="skill in row.skills"
                      :key="skill.skillId"
                      size="small"
                      effect="plain"
                    >
                      {{ skill.name }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="答题时间" width="164">
            <template #default="{ row }">{{ dateTime(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="题目" min-width="300">
            <template #default="{ row }">
              <div class="practice-history__question" :title="row.prompt">
                <strong>{{ row.prompt }}</strong>
                <span>第 {{ row.attemptNumber }} 次作答</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="88">
            <template #default="{ row }">
              <el-tag :type="row.correct ? 'success' : 'danger'" effect="light">
                {{ row.correct ? '正确' : '错误' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所选答案" min-width="170">
            <template #default="{ row }">{{ row.selectedChoice.label }}</template>
          </el-table-column>
          <el-table-column label="来源" width="104">
            <template #default="{ row }">{{ sourceLabel(row.sourceType) }}</template>
          </el-table-column>
        </el-table>

        <div class="practice-history__mobile">
          <article v-for="row in history.items" :key="row.answerEventId">
            <header>
              <span>{{ dateTime(row.occurredAt) }}</span>
              <el-tag :type="row.correct ? 'success' : 'danger'" size="small">
                {{ row.correct ? '正确' : '错误' }}
              </el-tag>
            </header>
            <h3>{{ row.prompt }}</h3>
            <dl>
              <dt>所选答案</dt><dd>{{ row.selectedChoice.label }}</dd>
              <dt>正确答案</dt><dd>{{ row.correctChoice.label }}</dd>
              <dt>知识点</dt><dd>{{ row.skills.map((skill) => skill.name).join('、') }}</dd>
              <dt>作答信息</dt>
              <dd>第 {{ row.attemptNumber }} 次 · {{ duration(row.responseTimeMs) }}</dd>
              <dt>来源</dt><dd>{{ sourceLabel(row.sourceType) }}</dd>
            </dl>
          </article>
        </div>
      </template>

      <div v-if="history.total > 0" class="lms-pagination practice-history__pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="history.total"
          layout="total, sizes, prev, pager, next"
          @current-change="load()"
          @size-change="load(true)"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.practice-history {
  min-width: 0;
}

.practice-history__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.practice-history__header > div {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.practice-history__header h2 {
  margin: 0;
  color: var(--ink-strong);
  font-size: 16px;
}

.practice-history__header span {
  color: var(--muted);
  font-size: 12px;
}

.practice-history__surface {
  min-width: 0;
}

.practice-history__detail {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px 28px;
  padding: 18px 28px 20px;
  background: var(--surface-soft);
}

.practice-history__detail > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.practice-history__detail span,
.practice-history__question span {
  color: var(--muted);
  font-size: 11px;
}

.practice-history__detail strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.practice-history__skills {
  grid-column: 1 / -1;
}

.practice-history__skills > div {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.practice-history__question {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.practice-history__question strong {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-height: 1.45;
}

.practice-history__mobile {
  display: none;
}

@media (max-width: 760px) {
  .practice-history__table {
    display: none;
  }

  .practice-history__mobile {
    display: block;
  }

  .practice-history__mobile article {
    padding: 16px;
    border-bottom: 1px solid var(--border);
  }

  .practice-history__mobile article:last-child {
    border-bottom: 0;
  }

  .practice-history__mobile header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: var(--muted);
    font-size: 11px;
  }

  .practice-history__mobile h3 {
    margin: 12px 0 14px;
    color: var(--ink-strong);
    font-size: 14px;
    line-height: 1.55;
  }

  .practice-history__mobile dl {
    display: grid;
    grid-template-columns: 72px minmax(0, 1fr);
    gap: 8px 12px;
    margin: 0;
    font-size: 12px;
  }

  .practice-history__mobile dt {
    color: var(--muted);
  }

  .practice-history__mobile dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
  }

  .practice-history__pagination {
    justify-content: center;
    overflow-x: auto;
  }
}

@media (max-width: 520px) {
  .practice-history__header {
    align-items: flex-start;
  }

  .practice-history__header > div {
    flex-direction: column;
    gap: 3px;
  }
}
</style>
