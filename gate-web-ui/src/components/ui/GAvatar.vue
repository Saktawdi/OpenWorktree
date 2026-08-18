<script setup lang="ts">
/**
 * GAvatar — compact owner/agent marker with enhanced typography and micro-rings.
 */
withDefaults(
  defineProps<{
    name: string;
    size?: 'sm' | 'md' | 'lg';
    tone?: 'accent' | 'success' | 'warning' | 'neutral';
  }>(),
  {
    size: 'sm',
    tone: 'neutral',
  },
);

const initials = (name: string) => name.trim().charAt(0).toUpperCase() || '?';
</script>

<template>
  <span class="g-avatar" :class="['g-avatar--' + size, 'g-avatar--' + tone]" :title="name">
    {{ initials(name) }}
  </span>
</template>

<style scoped>
.g-avatar {
  display: inline-grid;
  place-items: center;
  flex: none;
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  font-weight: 700;
  line-height: 1;
  user-select: none;
  box-shadow: var(--shadow-xs);
}

.g-avatar--sm {
  width: 22px;
  height: 22px;
  font-size: 10px;
}

.g-avatar--md {
  width: 30px;
  height: 30px;
  font-size: 12.5px;
}

.g-avatar--lg {
  width: 38px;
  height: 38px;
  font-size: 15px;
}

.g-avatar--accent {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

html[data-theme='dark'] .g-avatar--accent {
  color: var(--accent-hover);
}

.g-avatar--success {
  border-color: var(--success-border);
  background: var(--success-soft);
  color: var(--success);
}

html[data-theme='dark'] .g-avatar--success {
  color: var(--success-hover);
}

.g-avatar--warning {
  border-color: var(--warning-border);
  background: var(--warning-soft);
  color: var(--warning);
}

html[data-theme='dark'] .g-avatar--warning {
  color: var(--warning-hover);
}

.g-avatar--neutral {
  border-color: var(--border);
  background: var(--panel-2);
  color: var(--text-secondary);
}
</style>
