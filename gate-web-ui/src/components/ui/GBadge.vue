<script setup lang="ts">
/**
 * GBadge — compact state label for tickets, agents, and health signals.
 */
withDefaults(
  defineProps<{
    tone?: 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
    dot?: boolean;
  }>(),
  {
    tone: 'neutral',
    dot: false,
  },
);
</script>

<template>
  <span class="g-badge" :class="'g-badge--' + tone">
    <span v-if="dot" class="g-badge__dot" aria-hidden="true" />
    <slot />
  </span>
</template>

<style scoped>
.g-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 3px 9px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.g-badge__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 2px currentColor;
  opacity: 0.7;
}

.g-badge--neutral {
  border-color: var(--border);
  background: var(--panel-2);
  color: var(--text-secondary);
}

.g-badge--accent {
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.g-badge--success {
  background: var(--success-soft);
  color: var(--success);
}

.g-badge--warning {
  background: var(--warning-soft);
  color: var(--warning);
}

.g-badge--danger {
  background: var(--danger-soft);
  color: var(--danger);
}
</style>
