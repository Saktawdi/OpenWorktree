<script setup lang="ts">
/**
 * GSelect — native select with the same field language as GInput.
 */
withDefaults(
  defineProps<{
    modelValue?: string | null;
    placeholder?: string;
    options?: Array<{ label: string; value: string }>;
    disabled?: boolean;
  }>(),
  {
    modelValue: null,
    placeholder: '请选择',
    options: () => [],
    disabled: false,
  },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: string | null): void }>();
</script>

<template>
  <select
    class="g-select"
    :value="modelValue ?? ''"
    :disabled="disabled"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value || null)"
  >
    <option value="" disabled>{{ placeholder }}</option>
    <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
  </select>
</template>

<style scoped>
.g-select {
  display: block;
  width: 100%;
  min-height: 38px;
  padding: 8px 36px 8px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  outline: none;
  appearance: none;
  background-color: var(--panel);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%236b7785' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-position: right 11px center;
  background-repeat: no-repeat;
  color: var(--text);
  color-scheme: inherit;
  font-size: 13px;
  line-height: 1.45;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    background-color 0.16s ease;
}

.g-select:hover:not(:disabled) {
  border-color: var(--border-strong);
}

.g-select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.g-select:disabled {
  background-color: var(--panel-2);
  color: var(--text-muted);
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
