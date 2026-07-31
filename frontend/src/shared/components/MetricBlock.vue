<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  caption?: string
  tone?: 'default' | 'danger' | 'warning' | 'success'
}>()
</script>

<template>
  <div class="metric-block" :class="`metric-block--${tone ?? 'default'}`">
    <span class="metric-block__label">{{ label }}</span>
    <strong class="metric-block__value">{{ value }}</strong>
    <span v-if="caption" class="metric-block__caption">{{ caption }}</span>
  </div>
</template>
