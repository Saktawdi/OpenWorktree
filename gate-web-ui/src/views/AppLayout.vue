<script setup lang="ts">
/**
 * AppLayout - Multica-inspired shell.
 * 左侧固定侧栏 + 右侧 Canvas 内容区.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter, RouterView } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { GButton, GIcon, GTooltip } from '@/components/ui';
import { useConsolePreferences } from '@/composables/useConsolePreferences';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { applyConsolePreferences, preferences, toggleConsoleTheme } = useConsolePreferences();
const commandOpen = ref(false);
const commandQuery = ref('');
const commandIndex = ref(0);
const commandInput = ref<HTMLInputElement | null>(null);
const commandPanel = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;

interface NavItem {
  key: string;
  label: string;
  routeName: string;
  icon: 'grid' | 'kanban' | 'shield' | 'chat' | 'chart' | 'bot' | 'palette' | 'settings' | 'folder';
}
const navItems: NavItem[] = [
  { key: 'home', label: '项目看板', routeName: 'home', icon: 'grid' },
  { key: 'kanban', label: '工单看板', routeName: 'kanban', icon: 'kanban' },
  { key: 'review', label: '审核台', routeName: 'review', icon: 'shield' },
  { key: 'session', label: '会话', routeName: 'session', icon: 'chat' },
  { key: 'cost', label: '成本', routeName: 'cost', icon: 'chart' },
  { key: 'agents', label: 'Agent 配置', routeName: 'agents', icon: 'bot' },
  { key: 'settings', label: '系统设置', routeName: 'settings', icon: 'settings' },
  { key: 'styleguide', label: '样式速览', routeName: 'styleguide', icon: 'palette' },
];

const activeKey = computed(() => {
  const name = String(route.name ?? '');
  if (name === 'ticket-detail') return 'kanban';
  return navItems.find((n) => n.routeName === name)?.key ?? '';
});

const isSettingsRoute = computed(() => route.name === 'settings');
const activeSettingsSection = computed(() => String(route.query.section ?? 'models'));
const settingsNavItems: Array<NavItem & { section: string }> = [
  { key: 'settings-models', label: '模型与供应商', routeName: 'settings', section: 'models', icon: 'bot' },
  { key: 'settings-console', label: '控制台', routeName: 'settings', section: 'console', icon: 'grid' },
  { key: 'settings-runtime', label: '运行环境', routeName: 'settings', section: 'runtime', icon: 'folder' },
  { key: 'settings-security', label: '访问安全', routeName: 'settings', section: 'security', icon: 'shield' },
];

const pageTitle = computed(() => {
  const map: Record<string, string> = {
    home: '项目看板',
    kanban: '工单看板',
    'ticket-detail': '工单详情',
    review: '审核台',
    session: 'Agent 会话',
    cost: '成本面板',
    agents: 'Agent 配置',
    settings: '系统设置',
    styleguide: '样式速览',
  };
  return map[String(route.name ?? '')] ?? 'GATE';
});

const filteredNavItems = computed(() => {
  const query = commandQuery.value.trim().toLowerCase();
  if (!query) return navItems;
  return navItems.filter((item) => `${item.label} ${item.routeName}`.toLowerCase().includes(query));
});

watch(commandQuery, () => {
  commandIndex.value = 0;
});

watch(filteredNavItems, (items) => {
  if (commandIndex.value >= items.length) commandIndex.value = Math.max(0, items.length - 1);
});

function go(item: NavItem) {
  if (item.routeName === 'review' || item.routeName === 'session') {
    const ticketNo = String(route.params.no ?? 'T-104');
    router.push({ name: item.routeName, params: { no: ticketNo } });
    return;
  }
  router.push({ name: item.routeName });
}

function goSettingsSection(section: string) {
  router.push({ name: 'settings', query: { section } });
}

function openCommand() {
  if (commandOpen.value) return;
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  commandQuery.value = '';
  commandIndex.value = 0;
  commandOpen.value = true;
  nextTick(() => commandInput.value?.focus());
}

function closeCommand(restoreFocus = true) {
  if (!commandOpen.value) return;
  commandOpen.value = false;
  if (restoreFocus) nextTick(() => previousFocus?.focus());
}

function chooseCommand(item: NavItem) {
  closeCommand(false);
  go(item);
}

function moveCommandIndex(amount: number) {
  const count = filteredNavItems.value.length;
  if (!count) return;
  commandIndex.value = (commandIndex.value + amount + count) % count;
}

function trapCommandFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !commandPanel.value) return;
  const focusable = Array.from(
    commandPanel.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
    ),
  );
  if (!focusable.length) return;
  const first = focusable[0]!;
  const last = focusable[focusable.length - 1]!;
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function onCommandKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    closeCommand();
    return;
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    moveCommandIndex(1);
    return;
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault();
    moveCommandIndex(-1);
    return;
  }
  if (event.key === 'Enter') {
    const item = filteredNavItems.value[commandIndex.value];
    if (item) {
      event.preventDefault();
      chooseCommand(item);
    }
    return;
  }
  trapCommandFocus(event);
}

function onDocumentKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    if (commandOpen.value) closeCommand();
    else openCommand();
    return;
  }
  if (commandOpen.value && event.key === 'Escape') {
    event.preventDefault();
    closeCommand();
  }
}

function logout() {
  auth.logout();
  router.push({ name: 'login' });
}

onMounted(() => {
  applyConsolePreferences();
  document.addEventListener('keydown', onDocumentKeydown);
});
onBeforeUnmount(() => document.removeEventListener('keydown', onDocumentKeydown));
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <button type="button" class="brand" aria-label="前往项目看板" @click="router.push({ name: 'home' })">
        <span class="brand__mark">G</span>
        <span class="brand__text">
          <span class="brand__name">GATE</span>
          <span class="brand__sub">LOCAL GIT GATE</span>
        </span>
      </button>

      <nav v-if="!isSettingsRoute" class="nav">
        <p class="nav__group">工作区</p>
        <GTooltip v-for="item in navItems" :key="item.key" :content="item.label" side="right" block>
          <button
            type="button"
            class="nav__item"
            :class="{ 'nav__item--active': activeKey === item.key }"
            :aria-label="item.label"
            @click="go(item)"
          >
            <GIcon :name="item.icon" :size="16" />
            <span>{{ item.label }}</span>
          </button>
        </GTooltip>
      </nav>

      <nav v-else class="nav nav--settings" aria-label="系统设置">
        <button type="button" class="nav__back" @click="router.push({ name: 'home' })">
          <GIcon name="chevron-left" :size="15" />
          <span>返回项目看板</span>
        </button>
        <p class="nav__group">系统管理</p>
        <GTooltip v-for="item in settingsNavItems" :key="item.key" :content="item.label" side="right" block>
          <button
            type="button"
            class="nav__item"
            :class="{ 'nav__item--active': activeSettingsSection === item.section }"
            :aria-label="item.label"
            @click="goSettingsSection(item.section)"
          >
            <GIcon :name="item.icon" :size="16" />
            <span>{{ item.label }}</span>
          </button>
        </GTooltip>
      </nav>

      <div class="sidebar__foot">
        <div class="token-chip">
          <span class="token-chip__dot" />
          {{ auth.tokenDigest || '未登录' }}
        </div>
        <GButton variant="ghost" size="sm" block @click="logout">退出登录</GButton>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar__title">
          <div class="topbar__heading">
            <span class="topbar__eyebrow">GATE / WORKSPACE</span>
            <h1>{{ pageTitle }}</h1>
          </div>
          <span v-if="route.meta.closedLoop" class="topbar__loop">
            {{ route.meta.closedLoop }}
          </span>
        </div>
        <div class="topbar__right">
          <span class="topbar__status"><i aria-hidden="true" />本地实例运行中</span>
          <GTooltip :content="preferences.theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'" side="bottom">
            <button
              type="button"
              class="theme-toggle"
              :aria-label="preferences.theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'"
              :aria-pressed="preferences.theme === 'dark'"
              @click="toggleConsoleTheme"
            >
              <GIcon :name="preferences.theme === 'dark' ? 'sun' : 'moon'" :size="15" />
            </button>
          </GTooltip>
          <button
            type="button"
            class="topbar__hint"
            aria-label="打开命令面板"
            aria-haspopup="dialog"
            :aria-expanded="commandOpen"
            @click="openCommand"
          >
            <GIcon name="search" :size="13" />
            <span>搜索</span>
            <kbd>⌘K</kbd>
          </button>
        </div>
      </header>

      <main class="canvas">
        <RouterView />
      </main>
    </div>

    <Teleport to="body">
      <div v-if="commandOpen" class="command-backdrop" @mousedown.self="() => closeCommand()">
        <section
          ref="commandPanel"
          class="command-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="command-title"
          @keydown="onCommandKeydown"
        >
          <div class="command-dialog__search">
            <GIcon name="search" :size="17" />
            <input
              ref="commandInput"
              v-model="commandQuery"
              type="search"
              autocomplete="off"
              aria-label="搜索工作区"
              placeholder="搜索工作区"
            />
            <kbd>Esc</kbd>
          </div>
          <div class="command-dialog__body">
            <p id="command-title" class="command-dialog__label">前往工作区</p>
            <div v-if="filteredNavItems.length" class="command-options" role="listbox" aria-label="工作区选项">
              <button
                v-for="(item, index) in filteredNavItems"
                :key="item.key"
                type="button"
                class="command-option"
                :class="{ 'command-option--active': commandIndex === index }"
                role="option"
                :aria-selected="commandIndex === index"
                @mouseenter="commandIndex = index"
                @click="chooseCommand(item)"
              >
                <GIcon :name="item.icon" :size="16" />
                <span>{{ item.label }}</span>
                <small class="mono">{{ item.routeName }}</small>
              </button>
            </div>
            <div v-else class="command-empty">
              <GIcon name="search" :size="17" />
              <span>没有匹配的工作区</span>
            </div>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.shell {
  --nav-accent: var(--accent);
  display: flex;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  color: var(--text, #1f2328);
  background: var(--background, #eef1f4);
}

.sidebar {
  flex: 0 0 232px;
  width: 232px;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 18px 12px 14px;
  color: var(--ink-text);
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  z-index: 2;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 3px 8px 22px;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  user-select: none;
}
.brand__mark {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex: none;
  border-radius: 7px;
  color: #ffffff;
  background: var(--nav-accent);
  box-shadow: none;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.04em;
}
.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.brand__name {
  color: #ffffff;
  font-size: 15px;
  font-weight: 760;
  letter-spacing: 0.06em;
}
.brand__sub {
  margin-top: 5px;
  color: var(--sidebar-muted);
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.12em;
}

.nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: visible;
}
.nav__group {
  margin: 0 10px 9px;
  color: #8b949e;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}
.nav__back {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  margin: 0 4px 22px;
  padding: 0 6px;
  border: 0;
  color: #b6bec8;
  background: transparent;
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
}
.nav__back:hover {
  color: #ffffff;
}
.nav--settings .nav__group {
  margin-bottom: 8px;
}
.nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: #b6bec8;
  background: transparent;
  font-size: 13px;
  font-weight: 560;
  text-align: left;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease, border-color 0.16s ease;
}
.nav__item :deep(svg) {
  color: #8b949e;
  transition: color 0.16s ease;
}
.nav__item:hover {
  color: #ffffff;
  background: rgba(240, 246, 252, 0.1);
  border-color: rgba(240, 246, 252, 0.1);
}
.nav__item:hover :deep(svg) {
  color: #79c0ff;
}
.nav__item--active {
  color: #ffffff;
  background: rgba(9, 105, 218, 0.22);
  border-color: rgba(121, 192, 255, 0.24);
  box-shadow: inset 3px 0 0 var(--nav-accent);
}
.nav__item--active :deep(svg) {
  color: #79c0ff;
}

.sidebar__foot {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 17px 2px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}
.token-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 0 8px;
  color: #8b949e;
  font-family: var(--font-mono);
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.token-chip__dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft);
}
.sidebar__foot :deep(.g-btn--ghost) {
  justify-content: flex-start;
  color: #b6bec8;
  border-radius: 6px;
}
.sidebar__foot :deep(.g-btn--ghost:hover:not(:disabled)) {
  color: #ffffff;
  background: rgba(240, 246, 252, 0.1);
}

.main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  padding: 0;
  background: var(--background, #eef1f4);
}
.topbar {
  display: flex;
  flex: 0 0 64px;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 0 clamp(20px, 3vw, 40px);
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}
.topbar__title {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.topbar__heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.topbar__eyebrow {
  color: var(--text-muted, #636c76);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.16em;
}
.topbar__title h1 {
  margin: 0;
  color: var(--text, #1f2328);
  font-size: 20px;
  font-weight: 760;
  letter-spacing: -0.025em;
}
.topbar__loop {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-muted, #636c76);
  background: var(--panel-2);
  font-size: 11px;
  white-space: nowrap;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 17px;
  color: var(--text-muted, #636c76);
  font-size: 11px;
}
.theme-toggle {
  display: grid;
  width: 32px;
  height: 32px;
  flex: none;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: var(--panel);
  cursor: pointer;
  transition: color 0.14s ease, border-color 0.14s ease, background-color 0.14s ease,
    transform 0.12s ease;
}
.theme-toggle:hover {
  border-color: var(--border-strong);
  color: var(--accent-hover);
  background: var(--hover);
}
.theme-toggle:active {
  transform: translateY(1px);
}
.topbar__status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--success);
  font-weight: 600;
}
.topbar__status i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft);
}
.topbar__hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-muted, #636c76);
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 10px;
  cursor: pointer;
  transition: color 0.14s ease, border-color 0.14s ease, background-color 0.14s ease;
}
.topbar__hint:hover {
  color: var(--text, #1f2328);
  border-color: var(--border-strong);
  background: var(--panel);
}
.topbar__hint kbd {
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
}
.command-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: start center;
  padding: min(14vh, 140px) 18px 18px;
  overflow: auto;
  background: var(--overlay);
}
.command-dialog {
  width: min(560px, 100%);
  overflow: hidden;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-popover);
}
.command-dialog__search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
}
.command-dialog__search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--text);
  background: transparent;
  font-size: 14px;
}
.command-dialog__search input::placeholder {
  color: var(--text-faint);
}
.command-dialog__search kbd {
  padding: 2px 5px;
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-faint);
  background: var(--panel-2);
  font-family: var(--font-mono);
  font-size: 10px;
}
.command-dialog__body {
  padding: 12px;
}
.command-dialog__label {
  margin: 0 4px 7px;
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.command-options {
  display: grid;
  gap: 3px;
}
.command-option {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 42px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease, border-color 0.14s ease, color 0.14s ease;
}
.command-option:hover,
.command-option--active {
  border-color: rgba(9, 105, 218, 0.28);
  color: var(--text);
  background: var(--accent-soft);
}
.command-option > span {
  overflow: hidden;
  font-size: 13px;
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.command-option small {
  color: var(--text-faint);
  font-size: 10px;
}
.command-empty {
  display: grid;
  justify-items: center;
  gap: 7px;
  min-height: 130px;
  place-content: center;
  color: var(--text-faint);
  font-size: 12px;
}
.canvas {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  margin: 0;
  overflow: hidden;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

@media (max-width: 860px) {
  .sidebar {
    flex-basis: 78px;
    width: 78px;
    padding-inline: 10px;
  }
  .brand {
    justify-content: center;
    padding-inline: 0;
  }
  .brand__text,
  .nav__group,
  .nav__back span,
  .nav__item span,
  .token-chip,
  .sidebar__foot :deep(.g-btn) {
    display: none;
  }
  .nav__item {
    justify-content: center;
    padding-inline: 0;
  }
  .nav__back {
    justify-content: center;
    margin: 0;
    padding-inline: 0;
  }
  .sidebar__foot {
    align-items: center;
  }
  .topbar__status {
    display: none;
  }
}
@media (max-width: 560px) {
  .shell {
    flex-direction: column;
  }
  .sidebar {
    width: 100%;
    height: auto;
    flex-basis: auto;
    flex-direction: row;
    align-items: center;
    padding: 10px 14px;
  }
  .brand {
    padding: 0;
  }
  .nav {
    flex-direction: row;
    justify-content: flex-start;
    gap: 3px;
    min-width: 0;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
    scrollbar-width: none;
  }
  .nav::-webkit-scrollbar {
    display: none;
  }
  .nav :deep(.g-tooltip--block) {
    width: 38px;
    flex: 0 0 38px;
  }
  .nav :deep(.g-tooltip__bubble) {
    display: none;
  }
  .nav__item {
    width: 38px;
    min-height: 38px;
    scroll-snap-align: start;
  }
  .nav__back {
    width: 38px;
    min-height: 38px;
    flex: 0 0 38px;
    margin: 0;
  }
  .sidebar__foot {
    display: none;
  }
  .main {
    height: calc(100% - 58px);
  }
  .topbar {
    flex-basis: 68px;
    min-height: 68px;
    padding-inline: 18px;
  }
  .topbar__title h1 {
    font-size: 18px;
  }
  .topbar__hint {
    display: inline-flex;
    width: 32px;
    height: 32px;
    justify-content: center;
    padding: 0;
  }
  .topbar__hint span,
  .topbar__hint kbd {
    display: none;
  }
}
</style>
