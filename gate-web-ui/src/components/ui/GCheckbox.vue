<script setup lang="ts">
/**
 * GCheckbox - a quiet, keyboard-friendly checkbox or switch control.
 * The native input remains in the tree so form semantics and browser focus work.
 */
withDefaults(
  defineProps<{
    modelValue?: boolean;
    variant?: 'checkbox' | 'switch';
    disabled?: boolean;
    id?: string;
    name?: string;
    ariaLabel?: string;
  }>(),
  {
    modelValue: false,
    variant: 'checkbox',
    disabled: false,
    id: '',
    name: '',
    ariaLabel: '',
  },
);

const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>();
</script>

<template>
  <label class="g-checkbox" :class="'g-checkbox--' + variant">
    <input
      class="g-checkbox__native"
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      :id="id || undefined"
      :name="name || undefined"
      :aria-label="ariaLabel || undefined"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="g-checkbox__indicator" aria-hidden="true" />
    <span v-if="$slots.default" class="g-checkbox__label"><slot /></span>
  </label>
</template>

<style scoped>
.g-checkbox {
  display: inline-flex;
  align-items: flex-start;
  gap: 9px;
  min-width: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  user-select: none;
}

.g-checkbox__native {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.g-checkbox__indicator {
  position: relative;
  display: grid;
  width: 16px;
  height: 16px;
  flex: none;
  margin-top: 1px;
  place-items: center;
  border: 1px solid var(--border-strong);
  border-radius: 5px;
  background: var(--panel);
  transition: border-color 0.16s ease, background-color 0.16s ease, box-shadow 0.16s ease;
}

.g-checkbox__indicator::after {
  width: 7px;
  height: 4px;
  border-bottom: 2px solid var(--accent-contrast);
  border-left: 2px solid var(--accent-contrast);
  content: '';
  opacity: 0;
  transform: translateY(-1px) rotate(-45deg) scale(0.7);
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.g-checkbox__native:hover:not(:disabled) + .g-checkbox__indicator,
.g-checkbox:hover .g-checkbox__indicator {
  border-color: var(--accent);
}

.g-checkbox__native:checked + .g-checkbox__indicator {
  border-color: var(--accent);
  background: var(--accent);
}

.g-checkbox__native:checked + .g-checkbox__indicator::after {
  opacity: 1;
  transform: translateY(-1px) rotate(-45deg) scale(1);
}

.g-checkbox__native:focus-visible + .g-checkbox__indicator {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-focus-ring);
}

.g-checkbox__native:disabled + .g-checkbox__indicator,
.g-checkbox__native:disabled ~ .g-checkbox__label {
  opacity: 0.55;
}

.g-checkbox--switch {
  align-items: center;
  gap: 0;
}

.g-checkbox--switch .g-checkbox__indicator {
  width: 40px;
  height: 22px;
  margin-top: 0;
  border-radius: 999px;
  background: var(--panel-2);
}

.g-checkbox--switch .g-checkbox__indicator::before {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--panel);
  box-shadow: 0 1px 2px rgba(20, 42, 54, 0.2);
  content: '';
  transition: transform 0.16s ease;
}

.g-checkbox--switch .g-checkbox__indicator::after {
  display: none;
}

.g-checkbox--switch .g-checkbox__native:checked + .g-checkbox__indicator::before {
  transform: translateX(18px);
}

.g-checkbox--switch .g-checkbox__native:checked + .g-checkbox__indicator {
  background: var(--accent);
}

@media (prefers-reduced-motion: reduce) {
  .g-checkbox__indicator,
  .g-checkbox__indicator::after,
  .g-checkbox__indicator::before {
    transition: none;
  }
}
</style>
