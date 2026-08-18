<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string;
    hint?: string;
    error?: string;
    forId?: string;
    required?: boolean;
  }>(),
  {
    hint: '',
    error: '',
    forId: '',
    required: false,
  },
);
</script>

<template>
  <div class="g-field" :class="{ 'g-field--error': error }">
    <div class="g-field__head">
      <label v-if="forId" class="g-field__label" :for="forId">
        {{ label }}<span v-if="required" class="g-field__required" aria-hidden="true">*</span>
      </label>
      <span v-else class="g-field__label">
        {{ label }}<span v-if="required" class="g-field__required" aria-hidden="true">*</span>
      </span>
      <span v-if="$slots.meta" class="g-field__meta"><slot name="meta" /></span>
    </div>
    <div class="g-field__control">
      <slot />
    </div>
    <p v-if="error" class="g-field__message" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="g-field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.g-field {
  display: grid;
  min-width: 0;
  gap: 7px;
}

.g-field__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.g-field__label {
  min-width: 0;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 750;
  line-height: 1.35;
}

.g-field__required {
  margin-left: 3px;
  color: var(--danger);
}

.g-field__meta,
.g-field__hint,
.g-field__message {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.g-field__hint,
.g-field__message {
  margin: 0;
}

.g-field__message {
  color: var(--danger);
  font-weight: 700;
}

.g-field--error :deep(.g-input),
.g-field--error :deep(.g-select),
.g-field--error :deep(.g-combobox__trigger) {
  border-color: var(--danger);
}
</style>
