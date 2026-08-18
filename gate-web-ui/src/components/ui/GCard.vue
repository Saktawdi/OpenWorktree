<script setup lang="ts">
/**
 * GCard — quiet surface for data panels and forms.
 * Features subtle inset lighting and layered hairline borders.
 */
withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    padding?: boolean;
    hoverable?: boolean;
  }>(),
  {
    title: '',
    subtitle: '',
    padding: true,
    hoverable: false,
  },
);
</script>

<template>
  <section class="g-card" :class="{ 'g-card--hoverable': hoverable }">
    <header v-if="title || subtitle || $slots.head" class="g-card__head">
      <div v-if="title || subtitle" class="g-card__heading">
        <div v-if="title" class="g-card__title">{{ title }}</div>
        <div v-if="subtitle" class="g-card__subtitle">{{ subtitle }}</div>
      </div>
      <slot name="head" />
    </header>
    <div class="g-card__body" :class="{ 'g-card__body--pad': padding }">
      <slot />
    </div>
    <footer v-if="$slots.foot" class="g-card__foot">
      <slot name="foot" />
    </footer>
  </section>
</template>

<style scoped>
.g-card {
  min-width: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--card-highlight), var(--shadow-panel);
  transition:
    border-color 0.16s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.16s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-card--hoverable:hover {
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  box-shadow: var(--card-highlight), var(--shadow-popover);
}

.g-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 48px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(180deg, var(--hover) 0%, transparent 100%);
}

.g-card__heading {
  min-width: 0;
}

.g-card__title {
  overflow: hidden;
  color: var(--text);
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: -0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.g-card__subtitle {
  margin-top: 2px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.g-card__body--pad {
  padding: 16px 18px;
}

.g-card__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}
</style>
