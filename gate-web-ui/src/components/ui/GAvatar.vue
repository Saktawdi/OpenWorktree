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
  border-color: rgba(230, 109, 47, 0.2);
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.g-avatar--success {
  border-color: rgba(44, 137, 98, 0.2);
  background: var(--success-soft);
  color: #277755;
}

.g-avatar--warning {
  border-color: rgba(184, 121, 33, 0.22);
  background: var(--warning-soft);
  color: #98651a;
}

.g-avatar--neutral {
  border-color: var(--border);
  background: var(--panel-2);
  color: var(--text-secondary);
}
</style>
