<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
  padding?: 'default' | 'compact' | 'flush'
  elevated?: boolean
}>()
</script>

<template>
  <section class="panel-card" :class="[`panel-card--${padding ?? 'default'}`, { 'panel-card--elevated': elevated !== false }]">
    <header v-if="title || $slots.header || $slots.extra" class="panel-card__header">
      <div class="panel-card__heading">
        <slot name="header">
          <h2 v-if="title">{{ title }}</h2>
          <span v-if="subtitle">{{ subtitle }}</span>
        </slot>
      </div>
      <div v-if="$slots.extra" class="panel-card__extra">
        <slot name="extra" />
      </div>
    </header>
    <div class="panel-card__body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="panel-card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>
