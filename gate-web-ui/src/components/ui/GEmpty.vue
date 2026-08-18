<script setup lang="ts">
/**
 * GEmpty — unified empty state: icon + title + optional hint/action.
 * Composed so every view's "no data" moment reads the same.
 */
import GIcon, { type GIconName } from './GIcon.vue';

withDefaults(
  defineProps<{
    icon?: GIconName;
    title: string;
    hint?: string;
  }>(),
  {
    icon: 'search',
    hint: '',
  },
);
</script>

<template>
  <div class="g-empty">
    <span class="g-empty__icon"><GIcon :name="icon ?? 'search'" :size="19" /></span>
    <p class="g-empty__title">{{ title }}</p>
    <p v-if="hint" class="g-empty__hint">{{ hint }}</p>
    <div v-if="$slots.default" class="g-empty__action"><slot /></div>
  </div>
</template>

<style scoped>
.g-empty {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 34px 20px;
  text-align: center;
}

.g-empty__icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: 50%;
  color: var(--text-faint);
  background: var(--panel-2);
}

.g-empty__title {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
}

.g-empty__hint {
  margin: 0;
  max-width: 42ch;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.g-empty__action {
  margin-top: 8px;
}
</style>
