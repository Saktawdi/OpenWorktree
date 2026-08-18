<script setup lang="ts">
/**
 * GCheckbox - a quiet, keyboard-friendly checkbox or switch control.
 * Features exact pixel symmetry for switches and smooth transitions.
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
  <label class="g-checkbox" :class="['g-checkbox--' + variant, { 'is-disabled': disabled }]">
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
  gap: 8px;
  min-width: 0;
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.5;
  cursor: pointer;
  user-select: none;
}

.g-checkbox.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
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
  margin-top: 1.5px;
  place-items: center;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  background: var(--panel);
  box-shadow: var(--card-highlight), var(--shadow-xs);
  transition:
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-checkbox__indicator::after {
  width: 7px;
  height: 4px;
  border-bottom: 2px solid #ffffff;
  border-left: 2px solid #ffffff;
  content: '';
  opacity: 0;
  transform: translateY(-1px) rotate(-45deg) scale(0.7);
  transition:
    opacity 0.12s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.12s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-checkbox__native:hover:not(:disabled) + .g-checkbox__indicator,
.g-checkbox:hover .g-checkbox__indicator {
  border-color: color-mix(in srgb, var(--accent) 55%, var(--border-strong));
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
  box-shadow: var(--focus-ring);
}

/* Precise Switch Geometry */
.g-checkbox--switch {
  align-items: center;
  gap: 0;
}

.g-checkbox--switch .g-checkbox__indicator {
  width: 36px;
  height: 20px;
  margin-top: 0;
  border-radius: var(--radius-full);
  background: var(--panel-2);
  border-color: var(--border-strong);
}

.g-checkbox--switch .g-checkbox__indicator::before {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  content: '';
  transition: transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-checkbox--switch .g-checkbox__indicator::after {
  display: none;
}

.g-checkbox--switch .g-checkbox__native:checked + .g-checkbox__indicator::before {
  transform: translateX(16px);
}

.g-checkbox--switch .g-checkbox__native:checked + .g-checkbox__indicator {
  background: var(--accent);
  border-color: var(--accent);
}

@media (prefers-reduced-motion: reduce) {
  .g-checkbox__indicator,
  .g-checkbox__indicator::after,
  .g-checkbox__indicator::before {
    transition: none;
  }
}
</style>
