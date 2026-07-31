<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, Clock3, Send } from '@lucide/vue'

import type { AssessmentDetail } from '@/shared/types/api'

const props = defineProps<{ detail: AssessmentDetail; answers: Record<string, string>; submitting: boolean }>()
const emit = defineEmits<{
  back: []
  submit: []
  answer: [questionId: string, choiceId: string]
}>()
const answered = computed(() => props.detail.questions.filter((question) => props.answers[question.questionId]).length)
</script>

<template>
  <section class="lms-assessment-sheet assignment-workspace">
    <div class="assignment-view-toolbar">
      <el-button link :icon="ArrowLeft" @click="emit('back')">返回任务列表</el-button>
      <span>{{ answered }}/{{ detail.questionCount }} 题已作答</span>
      <span><Clock3 :size="14" />{{ detail.dueAt ? new Date(detail.dueAt).toLocaleString() : '不限时' }}</span>
    </div>
    <header><span>{{ detail.assessmentType === 'QUIZ' ? '课程测验' : '课程作业' }}</span><h2>{{ detail.title }}</h2><p>{{ detail.description }}</p></header>
    <article v-for="(question, index) in detail.questions" :key="question.questionId" class="lms-question-block">
      <div class="lms-question-block__title"><span>{{ index + 1 }}</span><strong>{{ question.prompt }}</strong><small>{{ question.points }} 分</small></div>
      <el-radio-group :model-value="answers[question.questionId]" class="lms-answer-options" @update:model-value="emit('answer', question.questionId, String($event))">
        <el-radio v-for="option in question.options" :key="option.choiceId" :value="option.choiceId" border>{{ option.label }}</el-radio>
      </el-radio-group>
    </article>
    <div class="lms-assessment-actions"><span>提交后不可修改</span><el-button type="primary" :icon="Send" :disabled="answered !== detail.questionCount" :loading="submitting" @click="emit('submit')">提交并评分</el-button></div>
  </section>
</template>
