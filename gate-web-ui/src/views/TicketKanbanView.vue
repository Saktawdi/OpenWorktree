<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { GAvatar, GBadge, GButton, GField, GIcon, GInput, GModal, GSelect } from '@/components/ui';
import { createTicket, listTickets, updateTicket } from '@/api/tickets';
import { listAgentConfigs } from '@/api/agentConfig';
import { listProjects, type ProjectView } from '@/api/projects';
import { useConsolePreferences } from '@/composables/useConsolePreferences';
import { KANBAN_COLUMNS, stageToKanbanColumnStage, TICKET_STAGE_LABELS } from '@/types/stage';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';
import type { AgentConfig } from '@/types/agentConfig';

type ViewMode = 'board' | 'records';
type TicketPriority = NonNullable<Ticket['priority']>;
type FocusFilter = 'all' | 'active' | 'review' | 'attention' | 'ready';

interface NextStep {
  label: string;
  target: TicketStage;
  description: string;
}

const router = useRouter();
const route = useRoute();
const { preferences } = useConsolePreferences();
const view = ref<ViewMode>(preferences.defaultTicketView);
const tickets = ref<Ticket[]>([]);
const agentConfigs = ref<AgentConfig[]>([]);
const projects = ref<ProjectView[]>([]);
const loading = ref(true);
const dataError = ref('');
const query = ref('');
const focusFilter = ref<FocusFilter>('all');
const priorityFilter = ref<TicketPriority | 'all'>('all');
const activityMessage = ref('');
const dragTicketNo = ref<string | null>(null);
const activeDropStage = ref<TicketStage | null>(null);
const drawerTicketNo = ref<string | null>(null);
const drawerRef = ref<HTMLElement | null>(null);
const drawerTrigger = ref<HTMLElement | null>(null);
const drawerEditing = ref(false);
const savingEdit = ref(false);
const editError = ref('');
const editTitle = ref('');
const editDescription = ref('');
const editNote = ref('');
const editLabels = ref('');
const editPriority = ref<TicketPriority | null>(null);

const showCreate = ref(false);
const newNo = ref('');
const newTitle = ref('');
const newAgent = ref<string | null>(null);
const newPriority = ref<TicketPriority | null>(null);
const createError = ref('');

const projectId = computed(() => {
  const value = route.params.projectId;
  return typeof value === 'string' ? value : '';
});
const currentProject = computed(() => projects.value.find((project) => project.id === projectId.value) ?? null);

const stageOrder: TicketStage[] = [
  'PENDING',
  'IN_PROGRESS',
  'PRESUBMITTED',
  'IN_REVIEW',
  'REJECTED',
  'READY_TO_PUBLISH',
  'NEEDS_HUMAN',
  'DONE',
  'CANCELLED',
];

const stageOptions = stageOrder.map((stage) => ({
  label: TICKET_STAGE_LABELS[stage],
  value: stage,
}));
const priorityOptions = [
  { label: 'P0 · 紧急', value: 'P0' },
  { label: 'P1 · 高', value: 'P1' },
  { label: 'P2 · 普通', value: 'P2' },
  { label: 'P3 · 低', value: 'P3' },
];
const agentOptions = computed(() => agentConfigs.value.map((config) => ({
  label: `${config.name} · ${config.cli}`,
  value: config.id,
})));
const filterPriorityOptions = [
  { label: '全部优先级', value: 'all' },
  ...priorityOptions,
];

const nextStepByStage: Record<TicketStage, NextStep> = {
  PENDING: { label: '开始执行', target: 'IN_PROGRESS', description: '创建执行上下文并进入处理队列。' },
  IN_PROGRESS: { label: '提交预审', target: 'PRESUBMITTED', description: '固化当前产出，准备进入审核流程。' },
  PRESUBMITTED: { label: '进入审核', target: 'IN_REVIEW', description: '将预提审结果交给审核台处理。' },
  IN_REVIEW: { label: '通过审核', target: 'READY_TO_PUBLISH', description: '审核结论已明确，可以等待发布确认。' },
  REJECTED: { label: '重新执行', target: 'IN_PROGRESS', description: '带着审核反馈回到执行队列。' },
  READY_TO_PUBLISH: { label: '确认发布', target: 'DONE', description: '确认交付已经合并或发布完成。' },
  NEEDS_HUMAN: { label: '继续执行', target: 'IN_PROGRESS', description: '人工判断完成后，恢复正常执行。' },
  DONE: { label: '重新打开', target: 'IN_PROGRESS', description: '需要补充工作时，重新进入执行队列。' },
  CANCELLED: { label: '重新打开', target: 'IN_PROGRESS', description: '恢复已取消的任务并重新安排执行。' },
};

const normalizedQuery = computed(() => query.value.trim().toLowerCase());
const activeCount = computed(() => tickets.value.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage)).length);
const reviewCount = computed(() => tickets.value.filter((ticket) => ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage)).length);
const readyCount = computed(() => tickets.value.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length);
const attentionCount = computed(() => tickets.value.filter((ticket) => ['NEEDS_HUMAN', 'REJECTED'].includes(ticket.stage)).length);
const orderedTickets = computed(() =>
  [...tickets.value].sort((left, right) => {
    const priorityDelta = priorityRank(left.priority) - priorityRank(right.priority);
    return priorityDelta || Date.parse(right.updatedAt) - Date.parse(left.updatedAt);
  }),
);
const records = computed(() => orderedTickets.value.filter(matchesQuery));
const drawerTicket = computed(() => tickets.value.find((ticket) => ticket.no === drawerTicketNo.value) ?? null);
const drawerNextStep = computed<NextStep | null>(() =>
  drawerTicket.value ? nextStepByStage[drawerTicket.value.stage] : null,
);
const drawerCanReview = computed(() =>
  drawerTicket.value ? ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(drawerTicket.value.stage) : false,
);

function matchesQuery(ticket: Ticket) {
  if (focusFilter.value === 'active' && ['DONE', 'CANCELLED'].includes(ticket.stage)) return false;
  if (focusFilter.value === 'review' && !['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage)) return false;
  if (focusFilter.value === 'attention' && !['NEEDS_HUMAN', 'REJECTED'].includes(ticket.stage)) return false;
  if (focusFilter.value === 'ready' && ticket.stage !== 'READY_TO_PUBLISH') return false;
  if (priorityFilter.value !== 'all' && ticket.priority !== priorityFilter.value) return false;
  if (!normalizedQuery.value) return true;
  const haystack = [ticket.no, ticket.title, ticket.project, ticket.branch, ...(ticket.labels ?? [])]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  return haystack.includes(normalizedQuery.value);
}

function priorityRank(priority?: Ticket['priority']) {
  return { P0: 0, P1: 1, P2: 2, P3: 3 }[priority ?? 'P3'];
}

function ticketsForStage(stage: TicketStage) {
  return orderedTickets.value.filter(
    (ticket) => stageToKanbanColumnStage(ticket.stage) === stage && matchesQuery(ticket),
  );
}

function setFocusFilter(filter: FocusFilter) {
  focusFilter.value = focusFilter.value === filter && filter !== 'all' ? 'all' : filter;
  if (focusFilter.value !== 'all') view.value = 'board';
}

function clearFilters() {
  query.value = '';
  focusFilter.value = 'all';
  priorityFilter.value = 'all';
}

function columnCount(stage: TicketStage) {
  return tickets.value.filter((ticket) => stageToKanbanColumnStage(ticket.stage) === stage).length;
}

function toneFor(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function agentName(ticket: Ticket) {
  if (!ticket.agentConfigId) return '人工';
  return ticket.agentConfigId.includes('claude') ? 'Claude' : 'OpenCode';
}

function formatDate(value: string) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '--';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(timestamp);
}

function formatToken(value: number | null) {
  return value == null ? '--' : new Intl.NumberFormat('zh-CN').format(value);
}

async function moveTicket(no: string, target: TicketStage) {
  const ticket = tickets.value.find((item) => item.no === no);
  if (!ticket || ticket.stage === target) return;

  const previous = ticket.stage;
  try {
    const updated = await updateTicket(no, { stage: target }, projectId.value);
    Object.assign(ticket, updated);
    activityMessage.value = `${ticket.no} 已从${TICKET_STAGE_LABELS[previous]}转入${TICKET_STAGE_LABELS[target]}。`;
  } catch {
    activityMessage.value = `${ticket.no} 不能从${TICKET_STAGE_LABELS[previous]}转入${TICKET_STAGE_LABELS[target]}，请按审核流程推进。`;
  }
}

function onDragStart(event: DragEvent, ticket: Ticket) {
  dragTicketNo.value = ticket.no;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', ticket.no);
  }
}

function onDragEnter(stage: TicketStage) {
  if (dragTicketNo.value) activeDropStage.value = stage;
}

function onDrop(event: DragEvent, target: TicketStage) {
  event.preventDefault();
  const no = event.dataTransfer?.getData('text/plain') || dragTicketNo.value;
  if (no) void moveTicket(no, target);
  dragTicketNo.value = null;
  activeDropStage.value = null;
}

function clearDragState() {
  dragTicketNo.value = null;
  activeDropStage.value = null;
}

function openDrawer(no: string, event?: MouseEvent) {
  drawerTrigger.value = event?.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  drawerTicketNo.value = no;
  drawerEditing.value = false;
  editError.value = '';
  nextTick(() => {
    drawerRef.value?.querySelector<HTMLElement>('[data-drawer-autofocus]')?.focus();
  });
}

function closeDrawer() {
  if (savingEdit.value) return;
  const trigger = drawerTrigger.value;
  drawerEditing.value = false;
  editError.value = '';
  drawerTicketNo.value = null;
  drawerTrigger.value = null;
  nextTick(() => trigger?.focus());
}

function moveDrawerToNextStep() {
  if (!drawerTicket.value || !drawerNextStep.value) return;
  void moveTicket(drawerTicket.value.no, drawerNextStep.value.target);
}

function updateDrawerStage(value: string | null) {
  if (!value || !drawerTicket.value || !stageOrder.includes(value as TicketStage)) return;
  void moveTicket(drawerTicket.value.no, value as TicketStage);
}

function parseEditLabels(value: string): string[] {
  return [...new Set(value.split(/[,，\n]/).map((label) => label.trim()).filter(Boolean))];
}

function openDrawerEdit() {
  if (!drawerTicket.value) return;
  editTitle.value = drawerTicket.value.title;
  editDescription.value = drawerTicket.value.description ?? '';
  editNote.value = drawerTicket.value.note ?? '';
  editLabels.value = (drawerTicket.value.labels ?? []).join(', ');
  editPriority.value = drawerTicket.value.priority ?? null;
  editError.value = '';
  drawerEditing.value = true;
  nextTick(() => {
    drawerRef.value?.querySelector<HTMLElement>('[data-drawer-edit-title]')?.focus();
  });
}

function cancelDrawerEdit() {
  if (savingEdit.value) return;
  drawerEditing.value = false;
  editError.value = '';
}

async function saveDrawerEdit() {
  const title = editTitle.value.trim();
  if (!title) {
    editError.value = '标题不能为空。';
    return;
  }
  if (!drawerTicket.value || savingEdit.value) return;

  savingEdit.value = true;
  editError.value = '';
  try {
    const updated = await updateTicket(drawerTicket.value.no, {
      title,
      description: editDescription.value.trim() || null,
      note: editNote.value.trim() || null,
      labels: parseEditLabels(editLabels.value),
      priority: editPriority.value,
    }, projectId.value);
    const localTicket = tickets.value.find((item) => item.no === updated.no);
    if (localTicket) Object.assign(localTicket, updated);
    activityMessage.value = `${updated.no} 工单信息已保存。`;
    drawerEditing.value = false;
  } catch {
    editError.value = '保存失败，请检查后端连接后重试。';
  } finally {
    savingEdit.value = false;
  }
}

function goTo(name: 'ticket-detail' | 'review' | 'session') {
  if (!drawerTicket.value) return;
  const no = drawerTicket.value.no;
  closeDrawer();
  router.push({ name, params: { projectId: projectId.value, no } });
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (!drawerTicket.value) return;

  if (event.key === 'Escape') {
    event.preventDefault();
    if (drawerEditing.value) cancelDrawerEdit();
    else closeDrawer();
    return;
  }

  if (event.key !== 'Tab') return;
  const root = drawerRef.value;
  if (!root) return;
  const focusable = Array.from(
    root.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => !element.hasAttribute('aria-hidden'));

  if (!focusable.length) {
    event.preventDefault();
    root.focus();
    return;
  }

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

function suggestedTicketNo() {
  const max = tickets.value.reduce((current, ticket) => {
    const match = /^T-(\d+)$/.exec(ticket.no);
    return match ? Math.max(current, Number(match[1])) : current;
  }, 100);
  return `T-${max + 1}`;
}

function openCreate() {
  newNo.value = suggestedTicketNo();
  newTitle.value = '';
  newAgent.value = null;
  newPriority.value = null;
  createError.value = '';
  showCreate.value = true;
  nextTick(() => {
    document.querySelector<HTMLElement>('[data-compose-title]')?.focus();
  });
}

function closeCreate() {
  showCreate.value = false;
  createError.value = '';
}

async function loadTickets() {
  loading.value = true;
  dataError.value = '';
  tickets.value = [];
  if (!projectId.value) {
    dataError.value = '请先从项目看板选择一个项目。';
    loading.value = false;
    return;
  }
  try {
    const [ticketResult, agentResult, projectResult] = await Promise.allSettled([
      listTickets(projectId.value),
      listAgentConfigs(),
      listProjects(),
    ]);
    if (ticketResult.status === 'fulfilled') tickets.value = ticketResult.value;
    if (agentResult.status === 'fulfilled') agentConfigs.value = agentResult.value;
    if (projectResult.status === 'fulfilled') projects.value = projectResult.value;
    if (ticketResult.status === 'rejected') throw ticketResult.reason;
    if (projectResult.status !== 'fulfilled' || !projectResult.value.some((project) => project.id === projectId.value)) {
      throw new Error('project not found');
    }
  } catch {
    dataError.value = '无法读取该项目的工单数据，请确认项目仍存在且 Gate 后端正在运行。';
  } finally {
    loading.value = false;
  }
}

async function create() {
  createError.value = '';
  if (!newNo.value.trim()) {
    createError.value = '请填写工单号';
    return;
  }
  if (!newTitle.value.trim()) {
    createError.value = '请填写工单标题';
    return;
  }

  const no = newNo.value.trim() || suggestedTicketNo();
  if (tickets.value.some((ticket) => ticket.no === no)) {
    createError.value = '工单号已存在，请换一个编号';
    return;
  }

  try {
    await createTicket({
      ticketNo: no,
      title: newTitle.value.trim(),
      ...(newAgent.value ? { agentConfigId: newAgent.value } : {}),
      ...(newPriority.value ? { priority: newPriority.value } : {}),
      projectId: projectId.value,
    });
    await loadTickets();
    view.value = 'board';
    query.value = '';
    activityMessage.value = `${no} 已创建，等待开始。`;
    closeCreate();
    nextTick(() => openDrawer(no));
  } catch {
    createError.value = '创建失败，请检查工单号、权威仓库和后端日志。';
  }
}

onMounted(() => {
  document.addEventListener('keydown', onDocumentKeydown);
  void loadTickets();
});
onBeforeUnmount(() => document.removeEventListener('keydown', onDocumentKeydown));

watch(projectId, () => {
  closeDrawer();
  void loadTickets();
});

watch(
  () => route.query.compose,
  (value) => {
    if (value !== '1') return;
    openCreate();
    void router.replace({
      name: 'kanban',
      params: { projectId: projectId.value },
      query: { ...route.query, compose: undefined },
    });
  },
  { immediate: true },
);
</script>

<template>
  <div class="kanban-page">
    <header class="board-header">
      <div class="board-header__context">
        <div>
          <p class="section-label">项目工单看板</p>
          <h2>{{ currentProject?.name ?? projectId }}</h2>
        </div>
        <GButton variant="ghost" size="sm" @click="router.push({ name: 'home' })">
          <GIcon name="chevron-left" :size="14" />
          切换项目
        </GButton>
      </div>

      <div class="summary-strip" aria-label="工单摘要">
        <button type="button" class="summary-item" :class="{ active: focusFilter === 'active' }" @click="setFocusFilter('active')">
          <strong>{{ activeCount }}</strong> 活跃工单
        </button>
        <button type="button" class="summary-item" :class="{ active: focusFilter === 'review' }" @click="setFocusFilter('review')">
          <strong>{{ reviewCount }}</strong> 审核流转
        </button>
        <button type="button" class="summary-item" :class="{ active: focusFilter === 'attention' }" @click="setFocusFilter('attention')">
          <strong>{{ attentionCount }}</strong> 需人工处理
        </button>
        <button type="button" class="summary-item" :class="{ active: focusFilter === 'ready' }" @click="setFocusFilter('ready')">
          <strong>{{ readyCount }}</strong> 待发布
        </button>
        <span class="summary-strip__total">共 {{ tickets.length }} 个</span>
      </div>

      <div class="board-actions">
        <div class="view-toggle" role="tablist" aria-label="工单视图">
          <button
            id="board-tab"
            type="button"
            role="tab"
            aria-controls="ticket-board"
            :aria-selected="view === 'board'"
            :tabindex="view === 'board' ? 0 : -1"
            :class="{ active: view === 'board' }"
            @click="view = 'board'"
          >
            <GIcon name="kanban" :size="14" />
            看板
          </button>
          <button
            id="records-tab"
            type="button"
            role="tab"
            aria-controls="ticket-records"
            :aria-selected="view === 'records'"
            :tabindex="view === 'records' ? 0 : -1"
            :class="{ active: view === 'records' }"
            @click="view = 'records'"
          >
            <GIcon name="grid" :size="14" />
            记录
          </button>
        </div>

        <GInput
          v-model="query"
          class="board-search"
          aria-label="搜索当前项目的工单、分支或标签"
          placeholder="搜索工单、分支或标签"
        />
        <details class="filter-more">
          <summary><GIcon name="settings" :size="13" />更多筛选</summary>
          <div class="filter-more__body">
            <GField class="filter-more__field" label="优先级" for-id="kanban-priority-filter">
              <GSelect id="kanban-priority-filter" v-model="priorityFilter" :options="filterPriorityOptions" />
            </GField>
            <button
              v-if="focusFilter !== 'all' || priorityFilter !== 'all' || query"
              type="button"
              class="filter-clear"
              @click="clearFilters"
            >
              清除筛选
            </button>
          </div>
        </details>
        <GButton variant="primary" @click="openCreate">
          <GIcon name="plus" :size="15" />
          新建工单
        </GButton>
      </div>
    </header>

    <p v-if="activityMessage" class="activity-message" role="status">{{ activityMessage }}</p>
    <p v-if="dataError" class="data-error" role="alert">
      <GIcon name="refresh" :size="14" />
      {{ dataError }}
      <button type="button" @click="loadTickets">重新读取</button>
    </p>

    <div v-if="loading" class="kanban-loading" aria-label="正在读取工单">
      <span v-for="index in 5" :key="index" />
    </div>

    <section
      v-else-if="view === 'board'"
      id="ticket-board"
      class="board-surface"
      role="tabpanel"
      aria-labelledby="board-tab"
    >
      <div class="kanban-scroll" role="list" aria-label="工单阶段列">
        <section
          v-for="column in KANBAN_COLUMNS"
          :key="column.stage"
          :class="[
            'kanban-column',
            'kanban-column--' + toneFor(column.stage),
            {
              'kanban-column--collapsed': column.collapsed,
              'kanban-column--drop-target': activeDropStage === column.stage,
            },
          ]"
          role="listitem"
          :aria-label="column.label + '，' + columnCount(column.stage) + ' 个工单'"
          @dragenter.prevent="onDragEnter(column.stage)"
          @dragover.prevent
          @drop="onDrop($event, column.stage)"
        >
          <header class="kanban-column__head">
            <div class="kanban-column__title-row">
              <h3>{{ column.label }}</h3>
              <span class="kanban-column__count">{{ columnCount(column.stage) }}</span>
            </div>
            <span class="kanban-column__key mono">{{ column.stage }}</span>
          </header>

          <div v-if="ticketsForStage(column.stage).length" class="kanban-column__cards">
            <article
              v-for="ticket in ticketsForStage(column.stage)"
              :key="ticket.no"
              class="kanban-card"
              :class="{
                'kanban-card--rejected': ticket.stage === 'REJECTED',
                'kanban-card--dragging': dragTicketNo === ticket.no,
              }"
              draggable="true"
              @dragstart="onDragStart($event, ticket)"
              @dragend="clearDragState"
            >
              <button
                type="button"
                class="kanban-card__button"
                :aria-label="'打开工单 ' + ticket.no + ' ' + ticket.title"
                @click="openDrawer(ticket.no, $event)"
              >
                <span class="kanban-card__topline">
                  <span class="mono kanban-card__no">{{ ticket.no }}</span>
                  <GBadge v-if="ticket.stage === 'REJECTED'" tone="danger">已驳回</GBadge>
                  <span
                    v-if="ticket.priority"
                    class="priority"
                    :class="'priority--' + ticket.priority.toLowerCase()"
                  >
                    {{ ticket.priority }}
                  </span>
                </span>
                <strong class="kanban-card__title">{{ ticket.title }}</strong>
                <span class="kanban-card__meta">
                  <span v-if="ticket.project" class="ticket-meta-item">
                    <GIcon name="folder" :size="12" />
                    {{ ticket.project }}
                  </span>
                  <span v-if="ticket.branch" class="ticket-meta-item mono">
                    <GIcon name="git-branch" :size="12" />
                    {{ ticket.branch }}
                  </span>
                </span>
                <span class="kanban-card__footer">
                  <span class="kanban-card__agent">
                    <GAvatar :name="agentName(ticket)" :tone="ticket.agentConfigId ? 'accent' : 'neutral'" />
                    {{ agentName(ticket) }}
                  </span>
                  <span class="kanban-card__signals">
                    <span v-if="ticket.commentCount" :title="ticket.commentCount + ' 条评论'">
                      <GIcon name="comment" :size="12" />
                      {{ ticket.commentCount }}
                    </span>
                    <span v-if="ticket.dependencies?.length" :title="ticket.dependencies.length + ' 项依赖'">
                      {{ ticket.dependencies.length }} 依赖
                    </span>
                    <span class="mono">{{ formatDate(ticket.updatedAt) }}</span>
                  </span>
                </span>
              </button>
              <button
                v-if="nextStepByStage[ticket.stage]"
                type="button"
                class="kanban-card__advance"
                :aria-label="'推进 ' + ticket.no + '：' + nextStepByStage[ticket.stage].label"
                @click.stop="moveTicket(ticket.no, nextStepByStage[ticket.stage].target)"
              >
                <GIcon name="spark" :size="12" />
                <span>{{ nextStepByStage[ticket.stage].label }}</span>
              </button>
            </article>
          </div>

          <div v-else class="column-empty">
            <GIcon name="kanban" :size="16" />
            <span>{{ normalizedQuery ? '没有匹配的工单' : '暂无工单' }}</span>
          </div>
        </section>
      </div>
    </section>

    <section
      v-else
      id="ticket-records"
      class="records-panel"
      role="tabpanel"
      aria-labelledby="records-tab"
    >
      <div class="records-panel__head">
        <div>
          <p class="section-label">流转记录</p>
          <h2>当前项目全部工单</h2>
          <p>记录视图仅展示「{{ currentProject?.name ?? projectId }}」下的工单。</p>
        </div>
        <span class="records-panel__count">{{ records.length }} 条</span>
      </div>

      <div class="records-panel__table">
        <table class="g-table">
          <thead>
            <tr>
              <th>工单</th>
              <th>项目与分支</th>
              <th>阶段</th>
              <th>审核</th>
              <th>执行令牌</th>
              <th>更新时间</th>
              <th><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ticket in records" :key="ticket.no">
              <td>
                <button type="button" class="record-ticket" @click="openDrawer(ticket.no, $event)">
                  <span class="mono">{{ ticket.no }}</span>
                  <strong>{{ ticket.title }}</strong>
                </button>
              </td>
              <td>
                <div class="record-project">
                  <span>{{ ticket.project || '--' }}</span>
                  <small class="mono">{{ ticket.branch || '--' }}</small>
                </div>
              </td>
              <td><GBadge :tone="toneFor(ticket.stage)">{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge></td>
              <td class="mono">R{{ ticket.reviewRound ?? 0 }}</td>
              <td class="mono">{{ formatToken(ticket.execTokenTotal) }}</td>
              <td class="record-updated">{{ formatDate(ticket.updatedAt) }}</td>
              <td>
                <GButton size="sm" variant="ghost" @click="openDrawer(ticket.no, $event)">
                  <GIcon name="external" :size="13" />
                  打开
                </GButton>
              </td>
            </tr>
            <tr v-if="records.length === 0">
              <td colspan="7" class="table-empty">没有匹配的工单记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <GModal :show="showCreate" title="新建工单" width="460px" @close="closeCreate">
      <div class="compose">
        <div class="compose__intro">
          <span class="compose__mark"><GIcon name="spark" :size="16" /></span>
          <div>
            <strong>先写下要推进的事</strong>
            <p>工单号会自动生成。需要时再补充执行上下文。</p>
          </div>
        </div>
        <GField label="标题" for-id="compose-title" required>
          <GInput id="compose-title" v-model="newTitle" data-compose-title name="ticket-title" autocomplete="off" placeholder="例如：修复审核台锚定" required />
        </GField>
        <details class="compose-advanced">
          <summary>补充执行上下文</summary>
          <div class="compose-advanced__body">
            <GField label="工单号" for-id="compose-no" hint="留空时按项目当前序列自动生成。">
              <GInput id="compose-no" v-model="newNo" name="ticket-no" autocomplete="off" placeholder="例如 T-104" />
            </GField>
            <GField label="智能体配置" for-id="compose-agent">
              <GSelect
                id="compose-agent"
                v-model="newAgent"
                :options="agentOptions"
                placeholder="不使用智能体"
              />
            </GField>
            <div class="form-grid form-grid--two">
              <GField label="所属项目">
                <div class="field-readonly"><GIcon name="folder" :size="13" />{{ currentProject?.name ?? projectId }}</div>
              </GField>
              <GField label="优先级" for-id="compose-priority">
                <GSelect id="compose-priority" v-model="newPriority" :options="priorityOptions" placeholder="未设置" />
              </GField>
            </div>
          </div>
        </details>
        <p v-if="createError" class="field-error" role="alert">{{ createError }}</p>
      </div>
      <template #footer>
        <GButton variant="ghost" @click="closeCreate">取消</GButton>
        <GButton variant="primary" @click="create">创建</GButton>
      </template>
    </GModal>

  </div>

  <Teleport to="body">
    <Transition name="ticket-drawer">
      <div v-if="drawerTicket" class="drawer-layer" @mousedown.self="closeDrawer">
        <aside
          ref="drawerRef"
          class="ticket-drawer"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="drawerEditing ? 'ticket-drawer-edit-title' : 'ticket-drawer-title'"
          tabindex="-1"
        >
          <header class="ticket-drawer__head">
            <div>
              <p class="section-label">工单详情</p>
              <span class="mono ticket-drawer__no">{{ drawerTicket.no }}</span>
              <span v-if="drawerEditing" id="ticket-drawer-edit-title" class="sr-only">编辑工单 {{ drawerTicket.no }}</span>
            </div>
            <div class="ticket-drawer__head-actions">
              <span v-if="drawerEditing" class="ticket-drawer__editing-state">编辑中</span>
              <GButton v-else variant="ghost" size="sm" @click="openDrawerEdit">
                <GIcon name="edit" :size="13" />
                编辑
              </GButton>
              <button
                data-drawer-autofocus
                type="button"
                class="icon-button"
                aria-label="关闭详情抽屉"
                :disabled="savingEdit"
                @click="closeDrawer"
              >
                <GIcon name="x" :size="17" />
              </button>
            </div>
          </header>

          <form class="ticket-drawer__body" @submit.prevent="saveDrawerEdit">
            <div class="ticket-drawer__title-row">
              <GInput
                v-if="drawerEditing"
                v-model="editTitle"
                data-drawer-edit-title
                class="drawer-edit-title"
                aria-label="工单标题"
                placeholder="输入工单标题"
              />
              <h2 v-else id="ticket-drawer-title">{{ drawerTicket.title }}</h2>
              <GBadge :tone="toneFor(drawerTicket.stage)">{{ TICKET_STAGE_LABELS[drawerTicket.stage] }}</GBadge>
            </div>

            <div v-if="!drawerEditing" class="ticket-drawer__submeta">
              <span v-if="drawerTicket.project">
                <GIcon name="folder" :size="13" />
                {{ drawerTicket.project }}
              </span>
              <span v-if="drawerTicket.branch" class="mono">
                <GIcon name="git-branch" :size="13" />
                {{ drawerTicket.branch }}
              </span>
              <span v-if="drawerTicket.priority" class="priority" :class="'priority--' + drawerTicket.priority.toLowerCase()">
                {{ drawerTicket.priority }}
              </span>
            </div>

            <div v-else class="drawer-edit-form">
              <GField label="需求描述" for-id="drawer-description" hint="描述范围、验收标准和不希望被改动的部分。">
                <GInput
                  id="drawer-description"
                  v-model="editDescription"
                  type="textarea"
                  :rows="4"
                  aria-label="需求描述"
                  placeholder="描述要完成什么、范围和验收标准"
                />
              </GField>
              <GField label="备注" for-id="drawer-note" hint="补充处理人需要知道的上下文。">
                <GInput
                  id="drawer-note"
                  v-model="editNote"
                  type="textarea"
                  :rows="3"
                  aria-label="工单备注"
                  placeholder="补充处理人需要知道的上下文"
                />
              </GField>
              <div class="drawer-edit-grid">
                <GField label="标签" for-id="drawer-labels">
                  <GInput id="drawer-labels" v-model="editLabels" name="ticket-labels" autocomplete="off" aria-label="工单标签" placeholder="逗号或换行分隔" />
                </GField>
                <GField label="优先级" for-id="drawer-priority">
                  <GSelect id="drawer-priority" v-model="editPriority" :options="priorityOptions" placeholder="未设置" aria-label="工单优先级" />
                </GField>
              </div>
              <small class="drawer-edit-hint">标签会自动去重；清空输入即可移除全部标签。</small>
              <p v-if="editError" class="drawer-edit-error" role="alert">{{ editError }}</p>
            </div>

            <section v-if="!drawerEditing" class="drawer-section drawer-content-section">
              <div class="drawer-section__head"><h3>需求与备注</h3></div>
              <div class="drawer-copy-grid">
                <article class="drawer-copy-block">
                  <span>需求描述</span>
                  <p v-if="drawerTicket.description?.trim()" class="drawer-copy-block__text">{{ drawerTicket.description }}</p>
                  <p v-else class="drawer-copy-block__text drawer-muted">暂无需求描述</p>
                </article>
                <article class="drawer-copy-block">
                  <span>备注</span>
                  <p v-if="drawerTicket.note?.trim()" class="drawer-copy-block__text">{{ drawerTicket.note }}</p>
                  <p v-else class="drawer-copy-block__text drawer-muted">暂无备注</p>
                </article>
              </div>
            </section>

            <section v-if="drawerNextStep && !drawerEditing" class="drawer-next-step">
              <div>
                <p class="section-label">建议下一步</p>
                <h3>{{ drawerNextStep.label }}</h3>
                <span>{{ drawerNextStep.description }}</span>
              </div>
              <GButton variant="primary" block @click="moveDrawerToNextStep">
                <GIcon name="spark" :size="15" />
                {{ drawerNextStep.label }}
              </GButton>
            </section>

            <section class="drawer-section">
              <div class="drawer-section__head">
                <h3>当前阶段</h3>
                <span class="mono">{{ drawerTicket.stage }}</span>
              </div>
              <GSelect
                :model-value="drawerTicket.stage"
                :options="stageOptions"
                aria-label="调整工单阶段"
                @update:model-value="updateDrawerStage"
              />
            </section>

            <section class="drawer-section drawer-section--split">
              <div>
                <div class="drawer-section__head"><h3>执行者</h3></div>
                <div class="drawer-agent">
                  <GAvatar :name="agentName(drawerTicket)" size="md" :tone="drawerTicket.agentConfigId ? 'accent' : 'neutral'" />
                  <div>
                    <strong>{{ agentName(drawerTicket) }}</strong>
                    <small class="mono">{{ drawerTicket.agentConfigId || '手动选择' }}</small>
                  </div>
                </div>
              </div>
              <div>
                <div class="drawer-section__head"><h3>更新时间</h3></div>
                <p class="drawer-muted mono">{{ formatDate(drawerTicket.updatedAt) }}</p>
              </div>
            </section>

            <section class="drawer-section">
              <div class="drawer-section__head"><h3>依赖与标签</h3></div>
              <div class="drawer-list">
                <div>
                  <span>依赖</span>
                  <p v-if="drawerTicket.dependencies?.length">{{ drawerTicket.dependencies.join('、') }}</p>
                  <p v-else class="drawer-muted">暂无依赖</p>
                </div>
                <div>
                  <span>标签</span>
                  <div v-if="!drawerEditing && drawerTicket.labels?.length" class="drawer-labels">
                    <span v-for="label in drawerTicket.labels" :key="label">{{ label }}</span>
                  </div>
                  <p v-else-if="!drawerEditing" class="drawer-muted">暂无标签</p>
                  <p v-else class="drawer-muted">标签已在上方编辑</p>
                </div>
              </div>
            </section>
          </form>

          <footer class="ticket-drawer__foot" :class="{ 'ticket-drawer__foot--editing': drawerEditing }">
            <template v-if="drawerEditing">
              <GButton variant="ghost" :disabled="savingEdit" @click="cancelDrawerEdit">取消编辑</GButton>
              <GButton variant="primary" :loading="savingEdit" @click="saveDrawerEdit">保存修改</GButton>
            </template>
            <template v-else>
              <GButton variant="secondary" @click="goTo('ticket-detail')">
                <GIcon name="external" :size="14" />
                详情页
              </GButton>
              <GButton variant="ghost" @click="goTo('session')">
                <GIcon name="chat" :size="14" />
                会话
              </GButton>
              <GButton v-if="drawerCanReview" variant="success" @click="goTo('review')">
                <GIcon name="shield" :size="14" />
                审核
              </GButton>
            </template>
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>
<style scoped>
.kanban-page {
  display: flex;
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 14px;
  padding: 18px 0 0;
  overflow: hidden;
}

.board-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex: none;
  min-height: 52px;
  margin-inline: 22px;
}

.board-header__context {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.board-header__context .section-label {
  margin-bottom: 4px;
}

.board-header__context h2 {
  max-width: 240px;
  margin: 0;
  overflow: hidden;
  color: var(--text);
  font-size: 18px;
  font-weight: 780;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-readonly {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--panel-2);
  font-size: 12px;
}

.field-readonly :deep(svg) {
  color: var(--accent-hover);
}

.section-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.board-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 9px;
  flex-wrap: wrap;
}

.board-search {
  width: 226px;
}

.board-search :deep(.g-input) {
  min-height: 36px;
  padding-block: 7px;
}

.filter-more {
  position: relative;
  flex: none;
}
.filter-more summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--panel);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  list-style: none;
}
.filter-more summary::-webkit-details-marker { display: none; }
.filter-more summary:hover { border-color: var(--border-strong); background: var(--hover); }
.filter-more[open] summary { border-color: var(--accent-border); color: var(--accent-hover); background: var(--accent-soft); }
.filter-more__body {
  position: absolute;
  top: calc(100% + 7px);
  right: 0;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(150px, 1fr);
  gap: 8px;
  width: 300px;
  padding: 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow-popover);
}
.filter-more__body :deep(.g-select) { min-height: 34px; font-size: 11px; }
.filter-more__field :deep(.g-field__label) { color: var(--text-muted); font-size: 10px; }
.filter-clear {
  grid-column: 1 / -1;
  justify-self: start;
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: 10px;
  font-weight: 750;
  cursor: pointer;
}

.view-toggle {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel-2);
}

.view-toggle button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, transform 0.12s ease;
}

.view-toggle button:hover {
  color: var(--text);
  background: var(--hover);
}

.view-toggle button:active {
  transform: translateY(1px);
}

.view-toggle button.active {
  border: 1px solid var(--accent-border);
  color: var(--accent-hover);
  background: var(--panel-2);
  box-shadow: var(--shadow-panel);
}

.summary-strip {
  display: flex;
  align-items: center;
  gap: 0;
  flex-wrap: wrap;
  flex: none;
  min-height: 38px;
  border-left: 2px solid var(--accent);
  color: var(--text-muted);
  font-size: 12px;
}

.summary-item {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  min-height: 30px;
  padding: 0 14px;
  border-right: 1px solid var(--border);
}
.summary-item {
  border-top: 0;
  border-bottom: 0;
  color: var(--text-muted);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: color 0.15s ease, background-color 0.15s ease;
}
.summary-item:hover,
.summary-item.active {
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.summary-item strong {
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.summary-strip__total {
  padding: 0 14px;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 11px;
}

.activity-message {
  margin: -3px 22px 0;
  padding: 9px 12px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
  flex: none;
}

.data-error {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: -3px 22px 0;
  padding: 9px 12px;
  border: 1px solid var(--danger-soft);
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 12px;
  font-weight: 650;
  flex: none;
}

.data-error button {
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: inherit;
  font-weight: 750;
  cursor: pointer;
}

.kanban-loading {
  display: grid;
  grid-template-columns: repeat(5, minmax(190px, 1fr));
  gap: 12px;
  min-height: 280px;
  margin: 0 22px 22px;
  overflow: hidden;
}

.kanban-loading span {
  position: relative;
  min-height: 220px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--skeleton-base);
}

.kanban-loading span::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, var(--skeleton-shine), transparent);
  animation: kanban-loading 1.4s ease-in-out infinite;
}

@keyframes kanban-loading {
  100% { transform: translateX(100%); }
}

.board-surface {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.records-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
  margin: 0 22px 22px;
}

.records-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 19px 20px 16px;
  border-bottom: 1px solid var(--border);
}

.records-panel__head h2 {
  margin: 0;
  color: var(--text);
  font-size: 19px;
  font-weight: 780;
  letter-spacing: -0.025em;
}

.records-panel__head p:last-child {
  max-width: 580px;
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.kanban-scroll {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(236px, 1fr);
  grid-auto-rows: minmax(0, 1fr);
  gap: 12px;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: 0 18px 16px;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-color: var(--border-strong) transparent;
  scrollbar-width: thin;
}

.kanban-column {
  display: flex;
  min-width: 0;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel-2);
  box-shadow: var(--shadow-panel);
  transition: border-color 0.16s ease, background-color 0.16s ease, box-shadow 0.16s ease;
}

.kanban-column--collapsed {
  min-width: 208px;
}

.kanban-column--drop-target {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: 0 0 0 3px var(--accent-focus-ring), var(--shadow-panel);
}

.kanban-column--neutral .kanban-column__head {
  box-shadow: inset 0 2px 0 var(--border-strong);
}

.kanban-column--accent .kanban-column__head {
  box-shadow: inset 0 2px 0 var(--accent);
}

.kanban-column--success .kanban-column__head {
  box-shadow: inset 0 2px 0 var(--success);
}

.kanban-column--warning .kanban-column__head {
  box-shadow: inset 0 2px 0 var(--warning);
}

.kanban-column--danger .kanban-column__head {
  box-shadow: inset 0 2px 0 var(--danger);
}

.kanban-column__head {
  display: grid;
  gap: 5px;
  min-height: 65px;
  padding: 13px 13px 11px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.kanban-column__title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.kanban-column h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 780;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kanban-column__count {
  display: inline-grid;
  min-width: 23px;
  height: 21px;
  flex: none;
  place-items: center;
  padding: 0 6px;
  border-radius: 7px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
}

.kanban-column__key {
  color: var(--text-faint);
  font-size: 9px;
  letter-spacing: 0.055em;
}

.kanban-column__cards {
  display: grid;
  align-content: start;
  gap: 8px;
  min-height: 0;
  flex: 1;
  padding: 10px;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scrollbar-color: var(--border-strong) transparent;
  scrollbar-width: thin;
}

.kanban-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, opacity 0.16s ease, transform 0.16s ease;
}

.kanban-card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-popover);
}

.kanban-card--rejected {
  border-color: color-mix(in srgb, var(--danger) 40%, transparent);
  box-shadow: inset 3px 0 0 var(--danger);
}

.kanban-card--dragging {
  opacity: 0.43;
  transform: rotate(1deg) scale(0.99);
  box-shadow: var(--shadow-popover);
}

.kanban-card__button {
  display: grid;
  width: 100%;
  gap: 9px;
  padding: 12px;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.kanban-card__button:hover {
  background: var(--hover);
}

.kanban-card__advance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
  width: calc(100% - 24px);
  min-height: 27px;
  margin: 0 12px 10px;
  padding: 0 8px;
  border: 1px solid var(--accent-border);
  border-radius: 6px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 750;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, transform 0.12s ease;
}
.kanban-card__advance:hover { border-color: var(--accent); background: var(--hover); }
.kanban-card__advance:active { transform: translateY(1px); }

.kanban-card__button:focus-visible,
.record-ticket:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  box-shadow: inset 0 0 0 1px var(--accent-border);
}

.kanban-card__topline,
.kanban-card__meta,
.kanban-card__footer,
.kanban-card__signals,
.kanban-card__agent {
  display: flex;
  align-items: center;
  min-width: 0;
}

.kanban-card__topline {
  gap: 6px;
}

.kanban-card__no {
  margin-right: auto;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.kanban-card__topline :deep(.g-badge) {
  flex: none;
}

.priority {
  flex: none;
  padding: 2px 5px;
  border-radius: 5px;
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 9px;
  font-weight: 800;
}

.priority--p0 {
  color: var(--danger);
  background: var(--danger-soft);
}

.priority--p1 {
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.kanban-card__title {
  display: -webkit-box;
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 720;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.kanban-card__meta {
  gap: 7px;
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
}

.ticket-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-meta-item + .ticket-meta-item::before {
  width: 3px;
  height: 3px;
  margin-right: 1px;
  border-radius: 50%;
  background: var(--border-strong);
  content: "";
}

.kanban-card__footer {
  justify-content: space-between;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
  color: var(--text-faint);
  font-size: 10px;
}

.kanban-card__agent {
  gap: 5px;
  min-width: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 10px;
  font-weight: 650;
  white-space: nowrap;
}

.kanban-card__agent :deep(.g-avatar) {
  flex: none;
}

.kanban-card__signals {
  justify-content: flex-end;
  gap: 7px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.kanban-card__signals > span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.kanban-card__signals > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
}

.column-empty {
  display: grid;
  justify-items: center;
  gap: 6px;
  min-height: 145px;
  flex: 1;
  place-content: center;
  padding: 20px 12px;
  color: var(--text-faint);
  font-size: 11px;
  text-align: center;
}

.column-empty svg {
  color: var(--border-strong);
}

.records-panel {
  overflow: hidden;
}

.records-panel__count {
  flex: none;
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 750;
}

.records-panel__table {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.g-table {
  width: 100%;
  min-width: 900px;
  border-collapse: collapse;
  font-size: 12px;
}

.g-table th {
  padding: 11px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.07em;
  text-align: left;
  text-transform: uppercase;
  white-space: nowrap;
}

.g-table td {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.g-table tbody tr:last-child td {
  border-bottom: 0;
}

.g-table tbody tr:hover {
  background: var(--hover);
}

.record-ticket {
  display: grid;
  width: 100%;
  gap: 3px;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.record-ticket > span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 750;
}

.record-ticket strong {
  max-width: 290px;
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-project {
  display: grid;
  gap: 3px;
}

.record-project > span {
  color: var(--text-secondary);
}

.record-project > small {
  color: var(--text-faint);
  font-size: 10px;
}

.record-updated {
  color: var(--text-muted) !important;
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.table-empty {
  padding: 34px 14px !important;
  color: var(--text-muted) !important;
  text-align: center;
}

.form {
  display: grid;
  gap: 15px;
}

.compose {
  display: grid;
  gap: 16px;
}
.compose__intro {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: var(--accent-soft);
}
.compose__mark {
  display: grid;
  width: 31px;
  height: 31px;
  flex: none;
  place-items: center;
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--panel);
}
.compose__intro > div { min-width: 0; }
.compose__intro strong { display: block; color: var(--text); font-size: 12px; }
.compose__intro p { margin: 3px 0 0; color: var(--text-muted); font-size: 11px; }
.field--primary :deep(.g-input) { min-height: 48px; font-size: 14px; }
.compose-advanced { border-top: 1px solid var(--border); }
.compose-advanced summary {
  padding: 12px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 750;
  cursor: pointer;
  list-style: none;
}
.compose-advanced summary::-webkit-details-marker { display: none; }
.compose-advanced summary::after { float: right; color: var(--text-faint); content: '+'; font-family: var(--font-mono); font-size: 14px; }
.compose-advanced[open] summary::after { content: '-'; }
.compose-advanced__body { display: grid; gap: 15px; padding-top: 13px; }
.form-grid { display: grid; gap: 14px; }
.form-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }

.field {
  display: grid;
  gap: 7px;
}

.field > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.field-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
  font-weight: 650;
}

.sr-only {
  position: absolute;
  top: 0;
  left: 0;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

.drawer-layer {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  justify-content: flex-end;
  background: var(--overlay);
  backdrop-filter: blur(5px);
}

.ticket-drawer {
  display: flex;
  width: min(500px, 100vw);
  height: 100%;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--overlay-border);
  background: var(--panel);
  box-shadow: var(--shadow-popover);
}

.ticket-drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-height: 72px;
  padding: 19px 22px 15px;
  border-bottom: 1px solid var(--border);
}

.ticket-drawer__no {
  color: var(--accent-hover);
  font-size: 12px;
  font-weight: 800;
}

.ticket-drawer__head-actions {
  display: flex;
  align-items: center;
  gap: 5px;
}

.ticket-drawer__head-actions :deep(.g-btn) {
  min-height: 30px;
  padding-inline: 9px;
}

.ticket-drawer__editing-state {
  padding: 5px 8px;
  border: 1px solid var(--accent-border);
  border-radius: 6px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 800;
}

.icon-button {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--text-muted);
  background: transparent;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}

.icon-button:hover {
  border-color: var(--border);
  color: var(--text);
  background: var(--hover);
}

.icon-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.ticket-drawer__body {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 21px 22px 26px;
  scrollbar-color: var(--border-strong) transparent;
  scrollbar-width: thin;
}

.ticket-drawer__title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ticket-drawer__title-row h2 {
  min-width: 0;
  margin: 0;
  color: var(--text);
  font-size: 21px;
  font-weight: 780;
  letter-spacing: -0.025em;
  line-height: 1.28;
}

.ticket-drawer__title-row :deep(.g-badge) {
  flex: none;
  margin-top: 3px;
}

.drawer-edit-title {
  min-width: 0;
  min-height: 42px;
  padding: 8px 10px;
  color: var(--text);
  font-size: 18px;
  font-weight: 760;
}

.ticket-drawer__submeta {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 11px;
}

.ticket-drawer__submeta > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.drawer-copy-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.drawer-copy-block {
  display: grid;
  min-width: 0;
  gap: 7px;
  padding: 11px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel-2);
}

.drawer-copy-block > span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.drawer-copy-block__text {
  min-width: 0;
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.drawer-edit-form {
  display: grid;
  gap: 13px;
  margin-top: 17px;
  padding: 14px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  background: var(--accent-soft);
}

.drawer-edit-field {
  display: grid;
  gap: 7px;
}

.drawer-edit-field > span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.drawer-edit-field :deep(.g-input),
.drawer-edit-field :deep(.g-select) {
  background: var(--panel);
}

.drawer-edit-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(132px, 0.85fr);
  gap: 10px;
}

.drawer-edit-hint {
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.45;
}

.drawer-edit-error {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--danger-soft);
  border-radius: 7px;
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 11px;
  line-height: 1.45;
}

.drawer-next-step {
  display: grid;
  gap: 13px;
  margin-top: 20px;
  padding: 15px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  background: var(--accent-soft);
}

.drawer-next-step .section-label {
  margin-bottom: 5px;
}

.drawer-next-step h3 {
  margin: 0;
  color: var(--text);
  font-size: 16px;
  font-weight: 770;
}

.drawer-next-step span {
  display: block;
  margin-top: 5px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.drawer-section {
  display: grid;
  gap: 9px;
  margin-top: 21px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.drawer-section__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.drawer-section__head h3 {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  font-weight: 760;
}

.drawer-section__head > span {
  color: var(--text-faint);
  font-size: 10px;
}

.drawer-section--split {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
}

.drawer-agent {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-agent > div {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.drawer-agent strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-agent small {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-muted {
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
}

.drawer-list {
  display: grid;
  gap: 13px;
}

.drawer-list > div {
  display: grid;
  gap: 5px;
}

.drawer-list > div > span {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 750;
}

.drawer-list p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.drawer-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.drawer-labels span {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 5px 9px;
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 750;
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.ticket-drawer__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 22px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.ticket-drawer__foot--editing {
  justify-content: stretch;
}

.ticket-drawer__foot--editing :deep(.g-btn) {
  flex: 1;
}

.ticket-drawer-enter-active,
.ticket-drawer-leave-active {
  transition: opacity 0.2s ease;
}

.ticket-drawer-enter-active .ticket-drawer,
.ticket-drawer-leave-active .ticket-drawer {
  transition: transform 0.22s ease;
}

.ticket-drawer-enter-from,
.ticket-drawer-leave-to {
  opacity: 0;
}

.ticket-drawer-enter-from .ticket-drawer,
.ticket-drawer-leave-to .ticket-drawer {
  transform: translateX(100%);
}

:global(html[data-density='compact'] .kanban-page) {
  gap: 8px;
  padding-top: 10px;
}

:global(html[data-density='compact'] .kanban-scroll) {
  gap: 8px;
}

:global(html[data-density='compact'] .kanban-column__head) {
  min-height: 55px;
  padding: 10px;
}

:global(html[data-density='compact'] .kanban-column__cards) {
  gap: 6px;
  padding: 7px;
}

:global(html[data-density='compact'] .kanban-card__button) {
  gap: 6px;
  padding: 9px;
}

@media (prefers-reduced-motion: reduce) {
  .kanban-column,
  .kanban-card,
  .view-toggle button,
  .ticket-drawer-enter-active,
  .ticket-drawer-leave-active,
  .ticket-drawer-enter-active .ticket-drawer,
  .ticket-drawer-leave-active .ticket-drawer {
    transition: none;
  }
  .kanban-loading span::after {
    animation: none;
  }
}

@media (max-width: 1080px) {
  .board-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .board-actions {
    justify-content: flex-start;
    width: 100%;
  }
  .filter-more__body { left: 0; right: auto; }
}

@media (max-width: 700px) {
  .kanban-page {
    gap: 9px;
    padding-top: 10px;
  }

  .board-header {
    margin-inline: 14px;
  }

  .board-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    align-items: center;
  }

  .board-search {
    grid-column: 1 / -1;
    grid-row: 2;
    width: 100%;
  }

  .board-actions :deep(.g-btn) {
    grid-column: 3;
    grid-row: 1;
    width: auto;
  }

  .filter-more { grid-column: 2; grid-row: 1; }
  .filter-more summary { min-width: 36px; padding-inline: 8px; }
  .filter-more summary :deep(svg) { flex: none; }
  .filter-more summary { font-size: 0; }
  .filter-more summary::after { content: '筛选'; font-size: 11px; }
  .filter-more__body { top: calc(100% + 6px); width: min(300px, calc(100vw - 28px)); }

  .view-toggle {
    grid-column: 1;
    grid-row: 1;
  }

  .summary-strip {
    width: 100%;
    gap: 0;
    padding-top: 9px;
    border-top: 1px solid var(--border);
    border-left: 0;
  }

  .summary-item {
    padding-inline: 10px;
  }

  .summary-strip__total {
    width: auto;
    padding-inline: 10px;
  }

  .activity-message {
    margin-inline: 14px;
  }

  .records-panel {
    margin: 0 14px 14px;
  }

  .records-panel__head {
    gap: 10px;
    padding: 17px 16px 14px;
  }

  .kanban-scroll {
    grid-auto-columns: minmax(224px, 1fr);
    padding: 0 8px 11px;
  }

  .ticket-drawer {
    width: 100%;
    height: min(88dvh, 760px);
    align-self: flex-end;
    border-top: 1px solid var(--overlay-border);
    border-left: 0;
    border-radius: 16px 16px 0 0;
    box-shadow: var(--shadow-popover);
  }

  .drawer-layer {
    align-items: flex-end;
  }

  .ticket-drawer-enter-from .ticket-drawer,
  .ticket-drawer-leave-to .ticket-drawer {
    transform: translateY(100%);
  }
}

@media (max-width: 560px) {
  .view-toggle {
    width: fit-content;
  }

  .records-panel__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .g-table {
    min-width: 820px;
  }
  .summary-strip { overflow-x: auto; flex-wrap: nowrap; }
  .summary-item { flex: none; }
  .filter-more__body { grid-template-columns: 1fr; }
  .filter-more__body .filter-clear { grid-column: auto; }
  .form-grid--two { grid-template-columns: 1fr; }

  .ticket-drawer__head {
    padding-inline: 17px;
  }

  .ticket-drawer__body {
    padding-inline: 17px;
  }

  .ticket-drawer__foot {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding-inline: 17px;
  }

  .ticket-drawer__foot :deep(.g-btn:last-child) {
    grid-column: 1 / -1;
  }

  .ticket-drawer__foot--editing :deep(.g-btn:last-child) {
    grid-column: auto;
  }

  .drawer-edit-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .drawer-copy-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .drawer-section--split {
    grid-template-columns: minmax(0, 1fr);
    gap: 13px;
  }
}
</style>
