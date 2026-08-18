<script setup lang="ts">
import { computed } from 'vue';
/**
 * GSkeleton — layout-matching placeholder shown while data loads.
 * Shape mirrors the content it replaces (rule: skeletons over spinners).
 */
const props = withDefaults(
  defineProps<{
    variant?: 'text' | 'block' | 'circle';
    width?: string;
    height?: string;
    lines?: number;
  }>(),
  {
    variant: 'text',
    width: '',
    height: '',
    lines: 1,
  },
);
const lineCount = computed(() => props.lines ?? 1);
</script>

<template>
  <span
    v-if="variant !== 'text' || lineCount <= 1"
    class="g-skeleton"
    :class="'g-skeleton--' + variant"
    :style="{ width: width || undefined, height: height || undefined }"
    aria-hidden="true"
  />
  <span v-else class="g-skeleton-group" aria-hidden="true">
    <span
      v-for="line in lineCount"
      :key="line"
      class="g-skeleton g-skeleton--text"
      :class="{ 'g-skeleton--text-last': line === lineCount }"
      :style="{ width: width || undefined }"
    />
  </span>
</template>

<style scoped>
.g-skeleton {
  display: inline-block;
  width: 100%;
  border-radius: var(--radius-sm);
  background: var(--skeleton-base);
  overflow: hidden;
  position: relative;
}

.g-skeleton::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, var(--skeleton-shine), transparent);
  animation: g-skeleton-shimmer 1.4s ease-in-out infinite;
}

.g-skeleton--text {
  height: 12px;
}

.g-skeleton--text-last {
  width: 62%;
}

.g-skeleton--block {
  height: 120px;
  border-radius: var(--radius-md);
}

.g-skeleton--circle {
  width: 32px;
  height: 32px;
  border-radius: 50%;
}

.g-skeleton-group {
  display: grid;
  gap: 8px;
  width: 100%;
}

@keyframes g-skeleton-shimmer {
  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .g-skeleton::after {
    animation: none;
  }
}
</style>
