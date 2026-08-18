<script setup lang="ts">
/**
 * GBadge — compact state label for tickets, agents, and health signals.
 * Refined with subtle glow and clear contrast.
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
  gap: 5px;
  max-width: 100%;
  padding: 2px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 650;
  line-height: 1.45;
  letter-spacing: 0.01em;
  white-space: nowrap;
  user-select: none;
}

.g-badge__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 2px currentColor;
  opacity: 0.85;
}

.g-badge--neutral {
  border-color: var(--border);
  background: var(--panel-2);
  color: var(--text-secondary);
}

.g-badge--accent {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

html[data-theme='dark'] .g-badge--accent {
  color: var(--accent-hover);
}

.g-badge--success {
  border-color: var(--success-border);
  background: var(--success-soft);
  color: var(--success);
}

html[data-theme='dark'] .g-badge--success {
  color: var(--success-hover);
}

.g-badge--warning {
  border-color: var(--warning-border);
  background: var(--warning-soft);
  color: var(--warning);
}

html[data-theme='dark'] .g-badge--warning {
  color: var(--warning-hover);
}

.g-badge--danger {
  border-color: var(--danger-border);
  background: var(--danger-soft);
  color: var(--danger);
}

html[data-theme='dark'] .g-badge--danger {
  color: var(--danger-hover);
}
</style>
