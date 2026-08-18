<script setup lang="ts">
/**
 * GSelect — native select with the same field language as GInput.
 * Uses a clean vector chevron icon and unified focus ring.
 */
withDefaults(
  defineProps<{
    modelValue?: string | null;
    placeholder?: string;
    options?: Array<{ label: string; value: string }>;
    size?: 'sm' | 'md';
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
    size: 'md',
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
  <div class="g-select-wrap" :class="['g-select-wrap--' + size, { 'is-disabled': disabled, 'is-invalid': ariaInvalid }]">
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
    <svg class="g-select__arrow" width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
      <path d="M2.5 4.5L6 8L9.5 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
  </div>
</template>

<style scoped>
.g-select-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
}

.g-select {
  display: block;
  width: 100%;
  padding: 0 32px 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  outline: none;
  appearance: none;
  background-color: var(--panel);
  color: var(--text);
  color-scheme: inherit;
  font-size: 13px;
  line-height: 1.45;
  box-shadow: var(--card-highlight), var(--shadow-xs);
  cursor: pointer;
  transition:
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-select-wrap--md .g-select {
  min-height: var(--control-height);
}

.g-select-wrap--sm .g-select {
  min-height: var(--control-height-sm);
  padding: 0 28px 0 9px;
  font-size: 12px;
  border-radius: var(--radius-xs);
}

.g-select:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong));
}

.g-select:focus {
  border-color: var(--accent);
  box-shadow: var(--focus-ring);
}

.g-select-wrap.is-invalid .g-select {
  border-color: var(--danger);
}

.g-select-wrap.is-invalid .g-select:focus {
  box-shadow: 0 0 0 2px var(--panel), 0 0 0 4px var(--danger);
}

.g-select:disabled {
  background-color: var(--panel-2);
  color: var(--text-muted);
  opacity: 0.6;
  cursor: not-allowed;
}

.g-select__arrow {
  position: absolute;
  right: 12px;
  color: var(--text-muted);
  pointer-events: none;
  transition: color 0.14s ease;
}

.g-select-wrap--sm .g-select__arrow {
  right: 9px;
}

.g-select:focus + .g-select__arrow {
  color: var(--accent);
}
</style>
