<script setup lang="ts">
import type { RiskBand } from '@/shared/types/api'
import { riskBandLabel } from '@/shared/utils/displayText'

defineProps<{ band: RiskBand; compact?: boolean }>()
</script>

<template>
  <span class="risk-tag" :class="[`risk-tag--${band.toLowerCase()}`, { 'risk-tag--compact': compact }]">
    <span class="risk-tag__dot" aria-hidden="true" />
    {{ riskBandLabel(band) }}
  </span>
</template>
