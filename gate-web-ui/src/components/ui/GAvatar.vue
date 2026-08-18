<script setup lang="ts">
/**
 * GAvatar — compact owner/agent marker.
 */
withDefaults(
  defineProps<{
    name: string;
    size?: 'sm' | 'md';
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
  border-radius: 50%;
  font-weight: 750;
  line-height: 1;
  user-select: none;
}

.g-avatar--sm {
  width: 22px;
  height: 22px;
  font-size: 10px;
}

.g-avatar--md {
  width: 32px;
  height: 32px;
  font-size: 13px;
}

.g-avatar--accent {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.g-avatar--success {
  border-color: color-mix(in srgb, var(--success) 26%, transparent);
  background: var(--success-soft);
  color: var(--success);
}

.g-avatar--warning {
  border-color: color-mix(in srgb, var(--warning) 28%, transparent);
  background: var(--warning-soft);
  color: var(--warning);
}

.g-avatar--neutral {
  border-color: var(--border);
  background: var(--panel-2);
  color: var(--text-secondary);
}
</style>
