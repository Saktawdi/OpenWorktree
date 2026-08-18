<script setup lang="ts">
/**
 * GModal — focused confirmation and form dialog.
 * Enhanced with smooth backdrop, adaptive scroll bounds, and polished close button.
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { focusWhenPageActive } from '@/utils/focus';
import GIcon from './GIcon.vue';

let bodyLockCount = 0;
let bodyOverflowBeforeLock = '';

const props = withDefaults(
  defineProps<{
    show: boolean;
    title?: string;
    width?: string;
  }>(),
  {
    title: '',
    width: '480px',
  },
);

const emit = defineEmits<{ (e: 'close'): void }>();
const dialog = ref<HTMLElement | null>(null);
const closeButton = ref<HTMLButtonElement | null>(null);
const titleId = 'g-modal-title-' + Math.random().toString(36).slice(2, 9);
const bodyId = 'g-modal-body-' + Math.random().toString(36).slice(2, 9);
let restoreFocus: HTMLElement | null = null;
let bodyLocked = false;

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

function close() {
  emit('close');
}

function lockBody() {
  if (typeof document === 'undefined') return;
  if (bodyLocked) return;
  if (bodyLockCount === 0) bodyOverflowBeforeLock = document.body.style.overflow;
  bodyLockCount += 1;
  bodyLocked = true;
  document.body.style.overflow = 'hidden';
}

function unlockBody() {
  if (typeof document === 'undefined' || !bodyLocked) return;
  bodyLockCount = Math.max(0, bodyLockCount - 1);
  bodyLocked = false;
  if (bodyLockCount === 0) {
    document.body.style.overflow = bodyOverflowBeforeLock;
    bodyOverflowBeforeLock = '';
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    close();
    return;
  }

  if (event.key !== 'Tab') return;

  const focusable = Array.from(dialog.value?.querySelectorAll<HTMLElement>(focusableSelector) ?? []).filter(
    (element) => element.getAttribute('aria-hidden') !== 'true',
  );
  if (!focusable.length) {
    event.preventDefault();
    dialog.value?.focus();
    return;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (!first || !last) return;
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

watch(
  () => props.show,
  async (isOpen) => {
    if (typeof document === 'undefined') return;
    if (!isOpen) {
      unlockBody();
      focusWhenPageActive(restoreFocus);
      restoreFocus = null;
      return;
    }

    lockBody();
    restoreFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    await nextTick();
    focusWhenPageActive(closeButton.value ?? dialog.value);
  },
  { flush: 'post', immediate: true },
);

onBeforeUnmount(() => {
  unlockBody();
  focusWhenPageActive(restoreFocus);
});
</script>

<template>
  <Teleport to="body">
    <Transition name="g-modal">
      <div v-if="props.show" class="g-modal__backdrop" @click.self="close">
        <div
          ref="dialog"
          class="g-modal"
          :style="{ width: props.width }"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="props.title ? titleId : undefined"
          :aria-describedby="bodyId"
          :aria-label="props.title ? undefined : '对话框'"
          tabindex="-1"
          @keydown="onKeydown"
        >
          <div v-if="props.title" class="g-modal__head">
            <h2 :id="titleId" class="g-modal__title">{{ props.title }}</h2>
            <button ref="closeButton" class="g-modal__close" type="button" aria-label="关闭" @click="close">
              <GIcon name="x" :size="15" aria-hidden="true" />
            </button>
          </div>
          <div :id="bodyId" class="g-modal__body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="g-modal__foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.g-modal__backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow-y: auto;
  background: var(--overlay);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.g-modal {
  display: flex;
  flex-direction: column;
  max-width: 100%;
  max-height: min(calc(100vh - 64px), 820px);
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--overlay-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--card-highlight), var(--shadow-popover);
  outline: none;
}

.g-modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 52px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(180deg, var(--hover) 0%, transparent 100%);
}

.g-modal__title {
  color: var(--text);
  margin: 0;
  font-size: 14.5px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.g-modal__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition:
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-modal__close:hover {
  background: var(--hover);
  border-color: var(--border);
  color: var(--text);
}

.g-modal__close:focus-visible {
  box-shadow: var(--focus-ring);
}

.g-modal__body {
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}

.g-modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 13px 20px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.g-modal-enter-active,
.g-modal-leave-active {
  transition: opacity 0.16s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-modal-enter-active .g-modal,
.g-modal-leave-active .g-modal {
  transition:
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    opacity 0.16s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-modal-enter-from,
.g-modal-leave-to {
  opacity: 0;
}

.g-modal-enter-from .g-modal,
.g-modal-leave-to .g-modal {
  opacity: 0;
  transform: translateY(8px) scale(0.985);
}
</style>
