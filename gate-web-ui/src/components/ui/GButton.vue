<script setup lang="ts">
/**
 * GButton — compact action control used throughout the gate console.
 * Upgraded with physical micro-press feedback, accessible focus rings, and smooth states.
 */
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'success';
    size?: 'sm' | 'md';
    disabled?: boolean;
    loading?: boolean;
    block?: boolean;
    type?: 'button' | 'submit';
    ariaLabel?: string;
    title?: string;
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    disabled: false,
    loading: false,
    block: false,
    type: 'button',
    ariaLabel: '',
    title: '',
  },
);

const emit = defineEmits<{ (e: 'click', event: MouseEvent): void }>();
</script>

<template>
  <button
    :type="type"
    class="g-btn"
    :class="['g-btn--' + variant, 'g-btn--' + size, { 'g-btn--block': block, 'is-loading': loading }]"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
    :aria-label="ariaLabel || undefined"
    :title="title || undefined"
    @click="(e: MouseEvent) => emit('click', e)"
  >
    <span v-if="loading" class="g-btn__spinner" aria-hidden="true" />
    <span class="g-btn__label"><slot /></span>
  </button>
</template>

<style scoped>
.g-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-weight: 600;
  letter-spacing: -0.005em;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  position: relative;
  transition:
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.08s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-btn__label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.g-btn:active:not(:disabled) {
  transform: scale(0.98) translateY(0.5px);
}

.g-btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.g-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
}

.g-btn--md {
  min-height: var(--control-height);
  padding: 0 14px;
  font-size: 13px;
}

.g-btn--sm {
  min-height: var(--control-height-sm);
  padding: 0 10px;
  font-size: 12px;
  border-radius: var(--radius-xs);
}

.g-btn--block {
  width: 100%;
}

.g-btn--primary {
  background: var(--accent);
  color: var(--accent-contrast);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22), var(--shadow-xs);
}

.g-btn--primary:hover:not(:disabled) {
  background: var(--accent-hover);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.26), var(--shadow-sm);
}

.g-btn--primary:active:not(:disabled) {
  background: var(--accent-active);
}

.g-btn--secondary {
  background: var(--panel);
  border-color: var(--border-strong);
  color: var(--text);
  box-shadow: var(--card-highlight), var(--shadow-xs);
}

.g-btn--secondary:hover:not(:disabled) {
  background: var(--hover);
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong));
  box-shadow: var(--shadow-sm);
}

.g-btn--ghost {
  background: transparent;
  color: var(--text-secondary);
}

.g-btn--ghost:hover:not(:disabled) {
  background: var(--hover);
  color: var(--text);
}

.g-btn--danger {
  background: var(--danger);
  color: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2), var(--shadow-xs);
}

.g-btn--danger:hover:not(:disabled) {
  background: var(--danger-hover);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25), var(--shadow-sm);
}

.g-btn--success {
  background: var(--success);
  color: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2), var(--shadow-xs);
}

.g-btn--success:hover:not(:disabled) {
  background: var(--success-hover);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25), var(--shadow-sm);
}

.g-btn__spinner {
  width: 13px;
  height: 13px;
  flex: none;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: g-btn-spin 0.65s linear infinite;
}

@keyframes g-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
