<script setup lang="ts">
/**
 * GTooltip — lightweight hover/focus hint for dense console controls.
 */
withDefaults(
  defineProps<{
    content: string;
    side?: 'top' | 'bottom' | 'left' | 'right';
    block?: boolean;
  }>(),
  {
    side: 'top',
    block: false,
  },
);
</script>

<template>
  <span class="g-tooltip" :class="{ 'g-tooltip--block': block }">
    <slot />
    <span class="g-tooltip__bubble" :class="'g-tooltip__bubble--' + side" role="tooltip">{{ content }}</span>
  </span>
</template>

<style scoped>
.g-tooltip {
  position: relative;
  display: inline-flex;
  min-width: 0;
}

.g-tooltip--block {
  display: flex;
  width: 100%;
}

.g-tooltip__bubble {
  position: absolute;
  z-index: 30;
  width: max-content;
  max-width: 240px;
  padding: 6px 9px;
  border: 1px solid var(--tooltip-border);
  border-radius: 7px;
  background: var(--tooltip-bg);
  color: var(--tooltip-text);
  font-size: 12px;
  font-weight: 550;
  line-height: 1.4;
  box-shadow: var(--shadow-popover);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.g-tooltip:hover .g-tooltip__bubble,
.g-tooltip:focus-within .g-tooltip__bubble {
  opacity: 1;
  pointer-events: auto;
}

.g-tooltip__bubble--top {
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translate(-50%, 3px);
}

.g-tooltip:hover .g-tooltip__bubble--top,
.g-tooltip:focus-within .g-tooltip__bubble--top {
  transform: translate(-50%, 0);
}

.g-tooltip__bubble--bottom {
  top: calc(100% + 8px);
  left: 50%;
  transform: translate(-50%, -3px);
}

.g-tooltip:hover .g-tooltip__bubble--bottom,
.g-tooltip:focus-within .g-tooltip__bubble--bottom {
  transform: translate(-50%, 0);
}

.g-tooltip__bubble--left {
  top: 50%;
  right: calc(100% + 8px);
  transform: translate(3px, -50%);
}

.g-tooltip:hover .g-tooltip__bubble--left,
.g-tooltip:focus-within .g-tooltip__bubble--left {
  transform: translate(0, -50%);
}

.g-tooltip__bubble--right {
  top: 50%;
  left: calc(100% + 8px);
  transform: translate(-3px, -50%);
}

.g-tooltip:hover .g-tooltip__bubble--right,
.g-tooltip:focus-within .g-tooltip__bubble--right {
  transform: translate(0, -50%);
}
</style>
