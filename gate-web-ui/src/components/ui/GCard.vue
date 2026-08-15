<script setup lang="ts">
/**
 * GCard — quiet paper surface for data panels and forms.
 */
withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    padding?: boolean;
  }>(),
  {
    title: '',
    subtitle: '',
    padding: true,
  },
);
</script>

<template>
  <section class="g-card">
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
  box-shadow: var(--shadow-panel);
}

.g-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 54px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--border);
}

.g-card__heading {
  min-width: 0;
}

.g-card__title {
  overflow: hidden;
  color: var(--text);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.g-card__subtitle {
  margin-top: 3px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.g-card__body--pad {
  padding: 18px;
}

.g-card__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 13px 18px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}
</style>
