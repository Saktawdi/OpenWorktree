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
    ariaLabel?: string;
    ariaDescribedby?: string;
    ariaInvalid?: boolean;
    required?: boolean;
    id?: string;
    name?: string;
    clearable?: boolean;
  }>(),
  {
    modelValue: null,
    placeholder: '请选择',
    options: () => [],
    disabled: false,
    ariaLabel: '',
    ariaDescribedby: '',
    ariaInvalid: false,
    required: false,
    id: '',
    name: '',
    clearable: false,
  },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: string | null): void }>();
</script>

<template>
  <select
    class="g-select"
    :value="modelValue ?? ''"
    :disabled="disabled"
    :id="id || undefined"
    :name="name || undefined"
    :aria-label="ariaLabel || undefined"
    :aria-describedby="ariaDescribedby || undefined"
    :aria-invalid="ariaInvalid || undefined"
    :required="required || undefined"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value || null)"
  >
    <option value="" :disabled="!clearable">{{ placeholder }}</option>
    <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
  </select>
</template>

<style scoped>
.g-select {
  display: block;
  width: 100%;
  min-height: var(--control-height);
  padding: 8px 36px 8px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  outline: none;
  appearance: none;
  background-color: var(--panel);
  background-image: linear-gradient(45deg, transparent 46%, currentColor 46%, currentColor 54%, transparent 54%),
    linear-gradient(-45deg, transparent 46%, currentColor 46%, currentColor 54%, transparent 54%);
  background-size: 5px 5px;
  background-position: right 12px center, right 16px center;
  background-repeat: no-repeat;
  background-origin: content-box;
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
  border-color: color-mix(in srgb, var(--accent) 42%, var(--border-strong));
}

.g-select:focus {
  border-color: var(--accent);
  box-shadow: var(--focus-ring);
}

.g-select:disabled {
  background-color: var(--panel-2);
  color: var(--text-muted);
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
