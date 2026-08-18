<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import GIcon from './GIcon.vue';

interface ComboboxOption {
  label: string;
  value: string;
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | null;
    options?: ComboboxOption[];
    placeholder?: string;
    searchPlaceholder?: string;
    ariaLabel?: string;
    ariaDescribedby?: string;
    ariaInvalid?: boolean;
    required?: boolean;
    id?: string;
    disabled?: boolean;
  }>(),
  {
    modelValue: null,
    options: () => [],
    placeholder: '请选择',
    searchPlaceholder: '搜索模型...',
    ariaLabel: '选择模型',
    ariaDescribedby: '',
    ariaInvalid: false,
    required: false,
    id: '',
    disabled: false,
  },
);

const emit = defineEmits<{ (event: 'update:modelValue', value: string | null): void }>();

const root = ref<HTMLElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const popover = ref<HTMLElement | null>(null);
const searchInput = ref<HTMLInputElement | null>(null);
const open = ref(false);
const query = ref('');
const activeIndex = ref(-1);
const popoverStyle = ref({ left: '0px', top: '0px', bottom: 'auto', width: '0px', maxHeight: '300px' });
const listId = `g-combobox-list-${Math.random().toString(36).slice(2, 9)}`;

const selectedOption = computed(() => props.options.find((option) => option.value === props.modelValue) ?? null);
const filteredOptions = computed(() => {
  const normalized = query.value.trim().toLowerCase();
  if (!normalized) return props.options;
  return props.options.filter((option) => `${option.label}\n${option.value}`.toLowerCase().includes(normalized));
});
const activeOption = computed(() => filteredOptions.value[activeIndex.value] ?? null);

function updatePosition() {
  if (!trigger.value || typeof window === 'undefined') return;
  const rect = trigger.value.getBoundingClientRect();
  const viewportPadding = 12;
  const width = Math.min(Math.max(rect.width, 360), window.innerWidth - viewportPadding * 2);
  const left = Math.min(Math.max(rect.left, viewportPadding), window.innerWidth - width - viewportPadding);
  const spaceBelow = window.innerHeight - rect.bottom - viewportPadding;
  const spaceAbove = rect.top - viewportPadding;
  const placeAbove = spaceBelow < 190 && spaceAbove > spaceBelow;
  const available = Math.max(150, Math.min(310, (placeAbove ? spaceAbove : spaceBelow) - 8));
  popoverStyle.value = {
    left: `${left}px`,
    top: placeAbove ? 'auto' : `${rect.bottom + 6}px`,
    bottom: placeAbove ? `${window.innerHeight - rect.top + 6}px` : 'auto',
    width: `${width}px`,
    maxHeight: `${available}px`,
  };
}

async function setOpen(next: boolean) {
  if (props.disabled) return;
  open.value = next;
  if (!next) {
    query.value = '';
    activeIndex.value = -1;
    return;
  }
  const selectedIndex = filteredOptions.value.findIndex((option) => option.value === props.modelValue);
  activeIndex.value = selectedIndex >= 0 ? selectedIndex : 0;
  await nextTick();
  updatePosition();
  searchInput.value?.focus();
}

function choose(option: ComboboxOption | null) {
  if (!option) return;
  emit('update:modelValue', option.value);
  void setOpen(false);
}

function moveActive(delta: number) {
  if (!filteredOptions.value.length) return;
  const next = activeIndex.value < 0 ? (delta > 0 ? 0 : filteredOptions.value.length - 1) : activeIndex.value + delta;
  activeIndex.value = (next + filteredOptions.value.length) % filteredOptions.value.length;
  nextTick(() => {
    document.getElementById(`${listId}-option-${activeIndex.value}`)?.scrollIntoView({ block: 'nearest' });
  });
}

function onTriggerKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    void setOpen(true);
  }
}

function onPopoverKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    void setOpen(false);
    trigger.value?.focus();
  } else if (event.key === 'ArrowDown') {
    event.preventDefault();
    moveActive(1);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    moveActive(-1);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    choose(activeOption.value ?? filteredOptions.value[0] ?? null);
  } else if (event.key === 'Home') {
    event.preventDefault();
    activeIndex.value = filteredOptions.value.length ? 0 : -1;
  } else if (event.key === 'End') {
    event.preventDefault();
    activeIndex.value = filteredOptions.value.length - 1;
  }
}

function onPointerDown(event: PointerEvent) {
  const target = event.target as Node | null;
  if (target && !root.value?.contains(target) && !popover.value?.contains(target)) void setOpen(false);
}

function onViewportChange() {
  if (open.value) updatePosition();
}

function onDocumentScroll(event: Event) {
  const target = event.target as Node | null;
  if (target && popover.value?.contains(target)) return;
  onViewportChange();
}

watch(query, () => {
  activeIndex.value = filteredOptions.value.length ? 0 : -1;
});

watch(filteredOptions, (options) => {
  if (activeIndex.value >= options.length) activeIndex.value = options.length - 1;
});

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown);
  document.addEventListener('scroll', onDocumentScroll, true);
  window.addEventListener('resize', onViewportChange);
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown);
  document.removeEventListener('scroll', onDocumentScroll, true);
  window.removeEventListener('resize', onViewportChange);
});
</script>

<template>
  <div ref="root" class="g-combobox" :class="{ 'g-combobox--open': open }">
    <button
      ref="trigger"
      class="g-combobox__trigger"
      type="button"
      role="combobox"
      :id="id || undefined"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :aria-describedby="ariaDescribedby || undefined"
      :aria-invalid="ariaInvalid || undefined"
      :aria-required="required || undefined"
      :aria-expanded="open"
      :aria-controls="open ? listId : undefined"
      :aria-activedescendant="open && activeIndex >= 0 ? `${listId}-option-${activeIndex}` : undefined"
      @click="setOpen(!open)"
      @keydown="onTriggerKeydown"
    >
      <span class="g-combobox__value" :class="{ 'g-combobox__value--placeholder': !selectedOption }">
        {{ selectedOption?.label ?? placeholder }}
      </span>
      <GIcon class="g-combobox__chevron" name="chevron-right" :size="14" />
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        ref="popover"
        class="g-combobox__popover"
        :style="popoverStyle"
        @keydown="onPopoverKeydown"
      >
        <div class="g-combobox__search-wrap">
          <GIcon name="search" :size="14" />
          <input
            ref="searchInput"
            v-model="query"
            class="g-combobox__search"
            type="search"
            :placeholder="searchPlaceholder"
            :aria-label="searchPlaceholder"
          />
        </div>
        <div :id="listId" class="g-combobox__list" role="listbox">
          <button
            v-for="(option, index) in filteredOptions"
            :id="`${listId}-option-${index}`"
            :key="option.value"
            class="g-combobox__option"
            :class="{ 'g-combobox__option--active': index === activeIndex, 'g-combobox__option--selected': option.value === modelValue }"
            type="button"
            role="option"
            :aria-selected="option.value === modelValue"
            @mouseenter="activeIndex = index"
            @click="choose(option)"
          >
            <span>{{ option.label }}</span>
            <GIcon v-if="option.value === modelValue" name="check" :size="14" />
          </button>
          <p v-if="!filteredOptions.length" class="g-combobox__empty">没有匹配的模型</p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.g-combobox {
  position: relative;
  min-width: 0;
}

.g-combobox__trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-height: var(--control-height);
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  outline: none;
  color: var(--text);
  background: var(--panel);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  text-align: left;
  box-shadow: var(--card-highlight), var(--shadow-xs);
  transition:
    border-color 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-combobox__trigger:hover:not(:disabled),
.g-combobox--open .g-combobox__trigger {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong));
}

.g-combobox--open .g-combobox__trigger {
  box-shadow: var(--focus-ring);
}

.g-combobox__trigger:focus-visible {
  box-shadow: var(--focus-ring);
}

.g-combobox__trigger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.g-combobox__value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.g-combobox__value--placeholder {
  color: var(--text-muted);
}

.g-combobox__chevron {
  flex: none;
  color: var(--text-muted);
  transform: rotate(90deg);
  transition: transform 0.16s cubic-bezier(0.16, 1, 0.3, 1);
}

.g-combobox--open .g-combobox__chevron {
  transform: rotate(-90deg);
}

.g-combobox__popover {
  position: fixed;
  z-index: 120;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  box-shadow: var(--card-highlight), var(--shadow-popover);
  color: var(--text);
}

.g-combobox__search-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text-muted);
}

.g-combobox__search {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  color: var(--text);
  background: transparent;
  font: inherit;
  font-size: 12.5px;
}

.g-combobox__search::placeholder {
  color: var(--text-faint);
}

.g-combobox__list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}

.g-combobox__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--radius-xs);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  text-align: left;
  transition: background-color 0.1s ease, color 0.1s ease;
}

.g-combobox__option:hover,
.g-combobox__option--active {
  color: var(--text);
  background: var(--hover);
}

.g-combobox__option--selected {
  color: var(--accent);
  font-weight: 650;
}

.g-combobox__option--selected svg {
  flex: none;
  color: var(--accent);
}

.g-combobox__empty {
  margin: 0;
  padding: 16px 10px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

@media (prefers-reduced-motion: reduce) {
  .g-combobox__trigger,
  .g-combobox__chevron {
    transition: none;
  }
}
</style>
