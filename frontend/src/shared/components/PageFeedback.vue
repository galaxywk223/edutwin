<script setup lang="ts">
import { AlertTriangle, LoaderCircle, RotateCcw } from '@lucide/vue'

defineProps<{ loading?: boolean; error?: string | null }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="loading" class="page-feedback" role="status">
    <LoaderCircle class="page-feedback__spinner" :size="24" />
    <span>正在读取真实服务数据</span>
  </div>
  <div v-else-if="error" class="page-feedback page-feedback--error" role="alert">
    <AlertTriangle :size="24" />
    <div>
      <strong>数据读取失败</strong>
      <span>{{ error }}</span>
    </div>
    <el-button :icon="RotateCcw" @click="$emit('retry')">重试</el-button>
  </div>
</template>
