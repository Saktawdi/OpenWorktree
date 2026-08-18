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
import { focusWhenPageActive } from '@/utils/focus';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { applyConsolePreferences, preferences, toggleConsoleTheme } = useConsolePreferences();
const commandOpen = ref(false);
const commandQuery = ref('');
const commandIndex = ref(0);
const commandInput = ref<HTMLInputElement | null>(null);
const commandPanel = ref<HTMLElement | null>(null);
const mobileNavOpen = ref(false);
const mobileNavPanel = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;
let previousMobileFocus: HTMLElement | null = null;
let previousBodyOverflow = '';
let overlayScrollLocked = false;

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
  { key: 'agents', label: '智能体配置', routeName: 'agents', icon: 'bot' },
  { key: 'settings', label: '系统设置', routeName: 'settings', icon: 'settings' },
  { key: 'styleguide', label: '样式速览', routeName: 'styleguide', icon: 'palette' },
];

const activeKey = computed(() => {
  const name = String(route.name ?? '');
  if (name === 'ticket-detail') return 'kanban';
  return navItems.find((n) => n.routeName === name)?.key ?? '';
});

const isSettingsRoute = computed(() => route.name === 'settings');
const hasProjectContext = computed(() => typeof route.params.projectId === 'string' && Boolean(route.params.projectId));
const canQuickCreate = computed(() =>
  hasProjectContext.value && !['login', 'kanban', 'styleguide'].includes(String(route.name ?? '')),
);
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
    session: '智能体会话',
    cost: '成本面板',
    agents: '智能体配置',
    settings: '系统设置',
    styleguide: '样式速览',
  };
  return map[String(route.name ?? '')] ?? 'GATE';
});

const loopLabel = computed(() => {
  const map: Record<string, string> = {
    status: '状态',
    layout: '组件',
    'presubmit+review': '预提审 / 审核',
    review: '审核',
    session: '会话',
    metrics: '成本',
    config: '配置',
  };
  return map[String(route.meta.closedLoop ?? '')] ?? '';
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

function syncOverlayScrollLock() {
  if (typeof document === 'undefined') return;
  const shouldLock = commandOpen.value || mobileNavOpen.value;
  if (shouldLock && !overlayScrollLocked) {
    previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    overlayScrollLocked = true;
  } else if (!shouldLock && overlayScrollLocked) {
    document.body.style.overflow = previousBodyOverflow;
    previousBodyOverflow = '';
    overlayScrollLocked = false;
  }
}

watch([commandOpen, mobileNavOpen], syncOverlayScrollLock, { immediate: true });

function go(item: NavItem) {
  closeMobileNav(false);
  if (item.routeName === 'kanban') {
    if (hasProjectContext.value) {
      router.push({ name: 'kanban', params: { projectId: route.params.projectId } });
    } else {
      router.push({ name: 'home' });
    }
    return;
  }
  if (item.routeName === 'review' || item.routeName === 'session') {
    if (!hasProjectContext.value || !route.params.no) {
      router.push({ name: 'home' });
      return;
    }
    const ticketNo = String(route.params.no ?? 'T-104');
    router.push({ name: item.routeName, params: { projectId: route.params.projectId, no: ticketNo } });
    return;
  }
  router.push({ name: item.routeName });
}

function goSettingsSection(section: string) {
  closeMobileNav(false);
  router.push({ name: 'settings', query: { section } });
}

function openTicketComposer() {
  if (!hasProjectContext.value) {
    router.push({ name: 'home' });
    return;
  }
  router.push({ name: 'kanban', params: { projectId: route.params.projectId }, query: { compose: '1' } });
}

function openCommand() {
  if (commandOpen.value) return;
  closeMobileNav(false);
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  commandQuery.value = '';
  commandIndex.value = 0;
  commandOpen.value = true;
  nextTick(() => focusWhenPageActive(commandInput.value));
}

function closeCommand(restoreFocus = true) {
  if (!commandOpen.value) return;
  commandOpen.value = false;
  if (restoreFocus) nextTick(() => focusWhenPageActive(previousFocus));
}

function chooseCommand(item: NavItem) {
  closeCommand(false);
  go(item);
}

function openMobileNav() {
  if (mobileNavOpen.value) return;
  previousMobileFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  mobileNavOpen.value = true;
  nextTick(() => focusWhenPageActive(mobileNavPanel.value?.querySelector<HTMLElement>('button:not([disabled])')));
}

function closeMobileNav(restoreFocus = true) {
  if (!mobileNavOpen.value) return;
  mobileNavOpen.value = false;
  if (restoreFocus) nextTick(() => focusWhenPageActive(previousMobileFocus));
}

function trapMobileNavFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !mobileNavPanel.value) return;
  const focusable = Array.from(
    mobileNavPanel.value.querySelectorAll<HTMLElement>(
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

function onMobileNavKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    closeMobileNav();
    return;
  }
  trapMobileNavFocus(event);
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
    return;
  }
  if (mobileNavOpen.value && event.key === 'Escape') {
    event.preventDefault();
    closeMobileNav();
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
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown);
  closeMobileNav(false);
  if (overlayScrollLocked) {
    document.body.style.overflow = previousBodyOverflow;
    previousBodyOverflow = '';
    overlayScrollLocked = false;
  }
});
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <button type="button" class="brand" aria-label="前往项目看板" @click="router.push({ name: 'home' })">
        <span class="brand__mark">G</span>
        <span class="brand__text">
          <span class="brand__name">GATE</span>
          <span class="brand__sub">本地 Git 闸门</span>
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
          <button
            type="button"
            class="mobile-nav-toggle"
            aria-label="打开工作区导航"
            :aria-expanded="mobileNavOpen"
            aria-controls="mobile-workspace-nav"
            @click="openMobileNav"
          >
            <GIcon name="menu" :size="17" />
          </button>
          <div class="topbar__heading">
            <h1>{{ pageTitle }}</h1>
          </div>
          <span v-if="loopLabel" class="topbar__loop">
            {{ loopLabel }}
          </span>
        </div>
        <div class="topbar__right">
          <GButton v-if="canQuickCreate" variant="secondary" size="sm" class="topbar__create" @click="openTicketComposer">
            <GIcon name="plus" :size="14" />
            新建工单
          </GButton>
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
              </button>
            </div>
            <div v-else class="command-empty">
              <GIcon name="search" :size="17" />
              <span>没有匹配的工作区</span>
            </div>
          </div>
        </section>
      </div>

      <div v-if="mobileNavOpen" class="mobile-nav-backdrop" @mousedown.self="() => closeMobileNav()">
        <aside
          id="mobile-workspace-nav"
          ref="mobileNavPanel"
          class="mobile-nav-panel"
          role="dialog"
          aria-modal="true"
          aria-label="工作区导航"
          @keydown="onMobileNavKeydown"
        >
          <header class="mobile-nav-panel__head">
            <div class="mobile-nav-panel__brand">
              <span class="brand__mark">G</span>
              <span>
                <strong>GATE</strong>
                <small>本地 Git 闸门</small>
              </span>
            </div>
            <button type="button" class="icon-button" aria-label="关闭工作区导航" @click="closeMobileNav()">
              <GIcon name="x" :size="16" />
            </button>
          </header>

          <nav v-if="!isSettingsRoute" class="mobile-nav-panel__nav" aria-label="工作区">
            <p class="nav__group">工作区</p>
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="nav__item"
            :class="{ 'nav__item--active': activeKey === item.key }"
            :aria-current="activeKey === item.key ? 'page' : undefined"
            :aria-label="item.label"
            @click="go(item)"
          >
              <GIcon :name="item.icon" :size="16" />
              <span>{{ item.label }}</span>
            </button>
          </nav>
          <nav v-else class="mobile-nav-panel__nav nav--settings" aria-label="系统设置">
            <button type="button" class="nav__back" @click="router.push({ name: 'home' }); closeMobileNav(false)">
              <GIcon name="chevron-left" :size="15" />
              <span>返回项目看板</span>
            </button>
            <p class="nav__group">系统管理</p>
            <button
              v-for="item in settingsNavItems"
              :key="item.key"
              type="button"
              class="nav__item"
              :class="{ 'nav__item--active': activeSettingsSection === item.section }"
              :aria-current="activeSettingsSection === item.section ? 'page' : undefined"
              :aria-label="item.label"
              @click="goSettingsSection(item.section)"
            >
              <GIcon :name="item.icon" :size="16" />
              <span>{{ item.label }}</span>
            </button>
          </nav>

          <div class="mobile-nav-panel__foot">
            <div class="token-chip">
              <span class="token-chip__dot" />
              {{ auth.tokenDigest || '未登录' }}
            </div>
            <GButton variant="ghost" size="sm" block @click="logout">退出登录</GButton>
          </div>
        </aside>
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
  color: var(--text);
  background: var(--background);
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
  border: 1px solid color-mix(in srgb, var(--sidebar-accent) 46%, transparent);
  border-radius: var(--radius-md);
  color: var(--accent-contrast);
  background: linear-gradient(145deg, var(--sidebar-accent), var(--nav-accent));
  box-shadow: var(--shadow-brand);
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
  color: var(--sidebar-text);
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
  color: var(--sidebar-muted);
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
  color: var(--sidebar-muted);
  background: transparent;
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
}
.nav__back:hover {
  color: var(--sidebar-text);
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
  border-radius: var(--radius-sm);
  color: var(--sidebar-muted);
  background: transparent;
  font-size: 13px;
  font-weight: 560;
  text-align: left;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease, border-color 0.16s ease;
}
.nav__item :deep(svg) {
  color: var(--sidebar-icon);
  transition: color 0.16s ease;
}
.nav__item:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-hover);
  border-color: var(--sidebar-hover);
}
.nav__item:hover :deep(svg) {
  color: var(--sidebar-accent);
}
.nav__item--active {
  color: var(--sidebar-text);
  background: var(--sidebar-active);
  border-color: var(--sidebar-active-border);
  box-shadow: inset 3px 0 0 var(--nav-accent);
}
.nav__item--active :deep(svg) {
  color: var(--sidebar-accent);
}

.sidebar__foot {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 17px 2px 0;
  border-top: 1px solid var(--sidebar-border);
}
.token-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 0 8px;
  color: var(--sidebar-muted);
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
  color: var(--sidebar-muted);
  border-radius: var(--radius-sm);
}
.sidebar__foot :deep(.g-btn--ghost:hover:not(:disabled)) {
  color: var(--sidebar-text);
  background: var(--sidebar-hover);
}

.main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  padding: 0;
  background: var(--background);
}
.topbar {
  display: flex;
  flex: 0 0 64px;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 0 clamp(20px, 3vw, 40px);
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--panel) 94%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}
.topbar__title {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.mobile-nav-toggle {
  display: none;
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
.mobile-nav-toggle:hover {
  border-color: var(--border-strong);
  color: var(--accent-hover);
  background: var(--hover);
}
.mobile-nav-toggle:active {
  transform: translateY(1px);
}
.topbar__heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.topbar__title h1 {
  margin: 0;
  color: var(--text);
  font-size: 20px;
  font-weight: 760;
  letter-spacing: -0.025em;
}
.topbar__loop {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 11px;
  white-space: nowrap;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 17px;
  color: var(--text-muted);
  font-size: 11px;
}
.topbar__create {
  min-height: 32px;
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
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 10px;
  cursor: pointer;
  transition: color 0.14s ease, border-color 0.14s ease, background-color 0.14s ease;
}
.topbar__hint:hover {
  color: var(--text);
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
  border-color: var(--accent-border);
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
.mobile-nav-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  background: var(--overlay);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}
.mobile-nav-panel {
  display: flex;
  width: min(300px, calc(100vw - 32px));
  height: 100%;
  flex-direction: column;
  padding: 16px 12px 14px;
  color: var(--ink-text);
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  box-shadow: var(--shadow-popover);
  animation: mobile-nav-in 0.18s ease both;
}
.mobile-nav-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 3px 8px 22px;
}
.mobile-nav-panel__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.mobile-nav-panel__brand .brand__mark {
  width: 34px;
  height: 34px;
}
.mobile-nav-panel__brand > span:last-child {
  display: grid;
  gap: 4px;
}
.mobile-nav-panel__brand strong {
  color: var(--sidebar-text);
  font-size: 14px;
  letter-spacing: 0.06em;
}
.mobile-nav-panel__brand small {
  color: var(--sidebar-muted);
  font-size: 9px;
  letter-spacing: 0.12em;
}
.mobile-nav-panel__head .icon-button {
  color: var(--sidebar-muted);
}
.mobile-nav-panel__head .icon-button:hover {
  border-color: var(--sidebar-border);
  color: var(--sidebar-text);
  background: var(--sidebar-hover);
}
.mobile-nav-panel__nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: auto;
}
.mobile-nav-panel__nav .nav__group {
  margin-top: 0;
}
.mobile-nav-panel__nav .nav__item {
  min-height: 42px;
}
.mobile-nav-panel__foot {
  display: grid;
  gap: 10px;
  padding: 17px 2px 0;
  border-top: 1px solid var(--sidebar-border);
}
.mobile-nav-panel__foot :deep(.g-btn--ghost) {
  justify-content: flex-start;
  color: var(--sidebar-muted);
}
.mobile-nav-panel__foot :deep(.g-btn--ghost:hover:not(:disabled)) {
  color: var(--sidebar-text);
  background: var(--sidebar-hover);
}
@keyframes mobile-nav-in {
  from { opacity: 0; transform: translateX(-10px); }
  to { opacity: 1; transform: translateX(0); }
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
  background: var(--canvas);
  box-shadow: none;
}

@media (prefers-reduced-transparency: reduce) {
  .topbar {
    background: var(--panel);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
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
  .sidebar > .nav {
    display: none;
  }
  .mobile-nav-panel .nav__group,
  .mobile-nav-panel .nav__back span,
  .mobile-nav-panel .nav__item span {
    display: inline;
  }
  .mobile-nav-panel .nav__item {
    justify-content: flex-start;
    width: 100%;
    padding-inline: 12px;
  }
  .mobile-nav-panel .nav__back {
    justify-content: flex-start;
    margin: 0 4px 22px;
    padding-inline: 6px;
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
  .mobile-nav-toggle {
    display: grid;
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
  .topbar__create :deep(.g-btn__label) {
    display: none;
  }
  .topbar__create {
    width: 32px;
    min-height: 32px;
    padding: 0;
  }
}
</style>
