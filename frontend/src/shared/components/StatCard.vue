<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  label: string
  value: string | number
  caption?: string
  tone?: 'default' | 'danger' | 'warning' | 'success' | 'info'
  icon?: Component
}>()
</script>

<template>
  <article class="stat-card" :class="`stat-card--${tone ?? 'default'}`">
    <div class="stat-card__top">
      <span class="stat-card__label">{{ label }}</span>
      <span v-if="icon" class="stat-card__icon" aria-hidden="true">
        <component :is="icon" :size="18" :stroke-width="1.9" />
      </span>
    </div>
    <strong class="stat-card__value">{{ value }}</strong>
    <span v-if="caption" class="stat-card__caption">{{ caption }}</span>
  </article>
</template>
