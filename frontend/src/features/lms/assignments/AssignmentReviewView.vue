<script setup lang="ts">
import { ArrowLeft, CheckCircle2, CircleX } from '@lucide/vue'

import type { AssessmentDetail, AssessmentResult } from '@/shared/types/api'

const props = defineProps<{ detail: AssessmentDetail; result: AssessmentResult }>()
defineEmits<{ back: [] }>()

function label(questionId: string, choiceId: string) {
  return props.detail.questions.find((question) => question.questionId === questionId)
    ?.options.find((option) => option.choiceId === choiceId)?.label ?? choiceId
}
</script>

<template>
  <section class="lms-assessment-sheet assignment-workspace">
    <div class="assignment-view-toolbar"><el-button link :icon="ArrowLeft" @click="$emit('back')">返回任务列表</el-button><span>提交时间 {{ new Date(result.submittedAt).toLocaleString() }}</span></div>
    <header><span>结果回顾</span><h2>{{ detail.title }}</h2><p>{{ detail.description }}</p></header>
    <div class="assignment-result-summary">
      <strong>{{ result.score }} / {{ result.maxScore }}</strong><span>{{ result.percentage }}%</span>
    </div>
    <article v-for="(question, index) in detail.questions" :key="question.questionId" class="lms-question-block assignment-review-question">
      <div class="lms-question-block__title"><span>{{ index + 1 }}</span><strong>{{ question.prompt }}</strong><small>{{ question.points }} 分</small></div>
      <template v-for="answer in result.answers.filter((item) => item.questionId === question.questionId)" :key="answer.questionId">
        <div class="assignment-answer-state" :class="answer.correct ? 'is-correct' : 'is-wrong'">
          <CheckCircle2 v-if="answer.correct" :size="18" /><CircleX v-else :size="18" />
          <div><span>所选答案</span><strong>{{ label(question.questionId, answer.selectedChoiceId) }}</strong></div>
          <div><span>正确答案</span><strong>{{ label(question.questionId, answer.correctChoiceId) }}</strong></div>
          <b>{{ answer.pointsAwarded }} 分</b>
        </div>
      </template>
    </article>
  </section>
</template>
