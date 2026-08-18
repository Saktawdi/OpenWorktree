<script setup lang="ts">
/**
 * GInput — text, password, and multiline input with a shared field treatment.
 * Upgraded with size variants, smooth focus rings, and clear feedback.
 */
withDefaults(
  defineProps<{
    modelValue?: string;
    placeholder?: string;
    type?: 'text' | 'password' | 'textarea';
    size?: 'sm' | 'md';
    rows?: number;
    disabled?: boolean;
    ariaLabel?: string;
    ariaDescribedby?: string;
    ariaInvalid?: boolean;
    required?: boolean;
    id?: string;
    name?: string;
    autocomplete?: string;
  }>(),
  {
    modelValue: '',
    placeholder: '',
    type: 'text',
    size: 'md',
    rows: 2,
    disabled: false,
    ariaLabel: '',
    ariaDescribedby: '',
    ariaInvalid: false,
    required: false,
    id: '',
    name: '',
    autocomplete: 'off',
  },
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'keydown', event: KeyboardEvent): void;
}>();
</script>

<template>
  <textarea
    v-if="type === 'textarea'"
    class="g-input g-input--textarea"
    :class="['g-input--' + size, { 'is-invalid': ariaInvalid }]"
    :value="modelValue"
    :placeholder="placeholder"
    :rows="rows"
    :disabled="disabled"
    :id="id || undefined"
    :name="name || undefined"
    :autocomplete="autocomplete || undefined"
    :aria-label="ariaLabel || undefined"
    :aria-describedby="ariaDescribedby || undefined"
    :aria-invalid="ariaInvalid || undefined"
    :required="required || undefined"
    @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    @keydown="emit('keydown', $event)"
  />
  <input
    v-else
    class="g-input"
    :class="['g-input--' + size, { 'is-invalid': ariaInvalid }]"
    :type="type"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :id="id || undefined"
    :name="name || undefined"
    :autocomplete="autocomplete || undefined"
    :aria-label="ariaLabel || undefined"
    :aria-describedby="ariaDescribedby || undefined"
    :aria-invalid="ariaInvalid || undefined"
    :required="required || undefined"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    @keydown="emit('keydown', $event)"
  />
</template>

<style scoped>
.g-input {
  display: block;
  width: 100%;
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  outline: none;
  background: var(--panel);
  color: var(--text);
  caret-color: var(--accent);
  font-size: 13px;
  line-height: 1.45;
  box-shadow: var(--card-highlight), var(--shadow-xs);
  transition:
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-input--md {
  min-height: var(--control-height);
}

.g-input--sm {
  min-height: var(--control-height-sm);
  padding: 0 9px;
  font-size: 12px;
  border-radius: var(--radius-xs);
}

.g-input::placeholder {
  color: var(--text-faint);
}

.g-input:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong));
}

.g-input:focus {
  border-color: var(--accent);
  box-shadow: var(--focus-ring);
}

.g-input.is-invalid {
  border-color: var(--danger);
}

.g-input.is-invalid:focus {
  box-shadow: 0 0 0 2px var(--panel), 0 0 0 4px var(--danger);
}

.g-input:disabled {
  background: var(--panel-2);
  color: var(--text-muted);
  opacity: 0.6;
  cursor: not-allowed;
}

.g-input--textarea {
  min-height: 80px;
  padding: 8px 12px;
  resize: vertical;
  line-height: 1.55;
}
</style>
