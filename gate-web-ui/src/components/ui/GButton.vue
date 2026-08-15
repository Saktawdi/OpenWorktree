<script setup lang="ts">
/**
 * GButton — compact action control used throughout the gate console.
 * The public props/events are intentionally stable for existing views.
 */
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'success';
    size?: 'sm' | 'md';
    disabled?: boolean;
    loading?: boolean;
    block?: boolean;
    type?: 'button' | 'submit';
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    disabled: false,
    loading: false,
    block: false,
    type: 'button',
  },
);

const emit = defineEmits<{ (e: 'click', event: MouseEvent): void }>();
</script>

<template>
  <button
    :type="type"
    class="g-btn"
    :class="['g-btn--' + variant, 'g-btn--' + size, { 'g-btn--block': block }]"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
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
  gap: 8px;
  min-width: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-weight: 650;
  letter-spacing: 0.005em;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.12s ease;
}

.g-btn__label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.g-btn:active:not(:disabled) {
  transform: translateY(1px);
}

.g-btn:focus-visible {
  outline: 2px solid var(--accent-contrast);
  outline-offset: 2px;
  box-shadow: 0 0 0 4px var(--accent);
}

.g-btn:disabled {
  opacity: 0.52;
  cursor: not-allowed;
  box-shadow: none;
}

.g-btn--md {
  min-height: 38px;
  padding: 0 15px;
  font-size: 13px;
}

.g-btn--sm {
  min-height: 30px;
  padding: 0 10px;
  font-size: 12px;
}

.g-btn--block {
  width: 100%;
}

.g-btn--primary {
  background: var(--accent);
  color: var(--accent-contrast);
  box-shadow: 0 5px 12px rgba(143, 57, 16, 0.18);
}

.g-btn--primary:hover:not(:disabled) {
  background: var(--accent-hover);
  box-shadow: 0 7px 16px rgba(143, 57, 16, 0.23);
}

.g-btn--secondary {
  background: var(--panel);
  border-color: var(--border-strong);
  color: var(--text);
  box-shadow: 0 1px 1px rgba(38, 41, 46, 0.03);
}

.g-btn--secondary:hover:not(:disabled) {
  background: var(--hover);
  border-color: #b8a998;
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
  color: #fffaf7;
  box-shadow: 0 5px 12px rgba(117, 39, 34, 0.16);
}

.g-btn--danger:hover:not(:disabled) {
  background: #8d2f29;
}

.g-btn--success {
  background: var(--success);
  color: #f8fffb;
  box-shadow: 0 5px 12px rgba(38, 119, 83, 0.14);
}

.g-btn--success:hover:not(:disabled) {
  background: #195d3f;
}

.g-btn__spinner {
  width: 13px;
  height: 13px;
  flex: none;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: g-btn-spin 0.7s linear infinite;
}

@keyframes g-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
