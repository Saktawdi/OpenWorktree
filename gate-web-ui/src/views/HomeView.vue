<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { GBadge, GButton, GCard, GIcon } from '@/components/ui';
import { mockTickets, stageLabels } from '@/mocks/prototypeData';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type QueueFilter = 'priority' | 'review' | 'blocked' | 'all';
type Destination = 'detail' | 'review' | 'session';
type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger';

interface NextStep {
  label: string;
  hint: string;
  destination: Destination;
}

const router = useRouter();
const activeFilter = ref<QueueFilter>('priority');
const selectedNo = ref<string | null>(null);

const filters: Array<{ id: QueueFilter; label: string }> = [
  { id: 'priority', label: '优先处理' },
  { id: 'review', label: '等待决策' },
  { id: 'blocked', label: '需要拉起' },
  { id: 'all', label: '全部进行中' },
];

const priorityRank: Record<'P0' | 'P1' | 'P2' | 'P3', number> = {
  P0: 0,
  P1: 1,
  P2: 2,
  P3: 3,
};

const stageRank: Record<TicketStage, number> = {
  READY_TO_PUBLISH: 0,
  IN_REVIEW: 1,
  PRESUBMITTED: 2,
  NEEDS_HUMAN: 3,
  REJECTED: 4,
  IN_PROGRESS: 5,
  PENDING: 6,
  DONE: 7,
  CANCELLED: 8,
};

const activeTickets = computed(() =>
  mockTickets.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage)),
);

const reviewTickets = computed(() =>
  activeTickets.value.filter((ticket) =>
    ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage),
  ),
);

const stalledTickets = computed(() =>
  orderTickets(
    activeTickets.value.filter((ticket) =>
      ['PENDING', 'PRESUBMITTED', 'REJECTED', 'NEEDS_HUMAN'].includes(ticket.stage),
    ),
  ).slice(0, 3),
);

const readyCount = computed(
  () => activeTickets.value.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length,
);

const queueTickets = computed(() =>
  orderTickets(activeTickets.value.filter((ticket) => matchesFilter(ticket, activeFilter.value))),
);

const focusTicket = computed<Ticket | null>(
  () =>
    queueTickets.value.find((ticket) => ticket.no === selectedNo.value) ??
    queueTickets.value[0] ??
    null,
);

const focusStep = computed<NextStep | null>(() =>
  focusTicket.value ? nextStepFor(focusTicket.value) : null,
);

const decisionTickets = computed(() =>
  orderTickets(
    activeTickets.value.filter((ticket) =>
      ['IN_REVIEW', 'READY_TO_PUBLISH', 'REJECTED'].includes(ticket.stage),
    ),
  ).slice(0, 3),
);

function orderTickets(tickets: Ticket[]): Ticket[] {
  return [...tickets].sort(
    (left, right) =>
      priorityRank[left.priority ?? 'P3'] - priorityRank[right.priority ?? 'P3'] ||
      stageRank[left.stage] - stageRank[right.stage] ||
      new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
  );
}

function matchesFilter(ticket: Ticket, filter: QueueFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'priority') {
    return (
      ticket.priority === 'P0' ||
      ticket.priority === 'P1' ||
      ticket.stage === 'READY_TO_PUBLISH'
    );
  }
  if (filter === 'review') {
    return ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage);
  }
  return ['PENDING', 'PRESUBMITTED', 'REJECTED', 'NEEDS_HUMAN'].includes(ticket.stage);
}

function stageTone(stage: TicketStage): BadgeTone {
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function nextStepFor(ticket: Ticket): NextStep {
  switch (ticket.stage) {
    case 'READY_TO_PUBLISH':
      return {
        label: '处理发布',
        hint: '审核已通过，进入审核台完成最后确认。',
        destination: 'review',
      };
    case 'PRESUBMITTED':
    case 'IN_REVIEW':
      return {
        label: '继续审核',
        hint: '证据已准备，下一步是明确通过或驳回。',
        destination: 'review',
      };
    case 'IN_PROGRESS':
      return {
        label: '继续会话',
        hint: '执行仍在推进，先查看 Agent 的最近上下文。',
        destination: 'session',
      };
    case 'PENDING':
      return {
        label: '启动执行',
        hint: '尚未开始，进入会话后可立即安排执行。',
        destination: 'session',
      };
    case 'REJECTED':
      return {
        label: '查看驳回项',
        hint: '先确认上一轮结论，再决定是否重新提交。',
        destination: 'detail',
      };
    case 'NEEDS_HUMAN':
      return {
        label: '人工决策',
        hint: '该事项需要人工确认后才能继续流转。',
        destination: 'detail',
      };
    default:
      return {
        label: '查看工单',
        hint: '打开工单详情，确认下一步。',
        destination: 'detail',
      };
  }
}

function selectTicket(ticket: Ticket) {
  selectedNo.value = ticket.no;
}

function openDestination(ticket: Ticket, destination: Destination) {
  if (destination === 'review') {
    router.push({ name: 'review', params: { no: ticket.no } });
    return;
  }
  if (destination === 'session') {
    router.push({ name: 'session', params: { no: ticket.no } });
    return;
  }
  router.push({ name: 'ticket-detail', params: { no: ticket.no } });
}

function openTicket(ticket: Ticket) {
  openDestination(ticket, 'detail');
}

function openQueue() {
  router.push({ name: 'kanban' });
}

function openCreate() {
  router.push({ name: 'kanban' });
}

function decisionSummary(ticket: Ticket): string {
  if (ticket.stage === 'READY_TO_PUBLISH') return '审核已通过，等待发布确认。';
  if (ticket.stage === 'REJECTED') return `第 ${ticket.reviewRound ?? 0} 轮审核已驳回。`;
  return `第 ${ticket.reviewRound ?? 0} 轮审核正在进行。`;
}

function stalledSummary(ticket: Ticket): string {
  if (ticket.stage === 'PENDING') return '尚未进入执行，会话还未启动。';
  if (ticket.stage === 'PRESUBMITTED') return '已预提审，等待进入审核判断。';
  if (ticket.stage === 'REJECTED') return '上一轮未通过，需要处理结论。';
  return '需要人工确认后才能继续。';
}
</script>

<template>
  <div class="home">
    <header class="home__header">
      <div class="home__heading">
        <p class="home__context">运营指挥台</p>
        <h2>先完成真正卡住交付的下一步。</h2>
        <p>
          从队列中选定一项，直接进入会话、审核或详情，不必先浏览整张看板。
        </p>
      </div>
      <div class="home__header-actions">
        <GButton variant="primary" @click="openCreate">
          <GIcon name="plus" :size="15" />
          新建工单
        </GButton>
        <GButton variant="secondary" @click="openQueue">
          <GIcon name="kanban" :size="15" />
          工单看板
        </GButton>
      </div>
    </header>

    <section class="home__snapshot" aria-label="当前工作概况">
      <div class="snapshot-item">
        <span>进行中</span>
        <strong>{{ activeTickets.length }}</strong>
        <small>正在影响交付节奏</small>
      </div>
      <div class="snapshot-item">
        <span>等待决策</span>
        <strong>{{ reviewTickets.length }}</strong>
        <small>已进入审核链路</small>
      </div>
      <div class="snapshot-item snapshot-item--accent">
        <span>可发布</span>
        <strong>{{ readyCount }}</strong>
        <small>可以完成最后确认</small>
      </div>
    </section>

    <section class="home__command-grid" aria-label="优先事项与处理队列">
      <GCard class="focus-panel" :padding="false">
        <template #head>
          <div class="panel-heading">
            <span>当前优先事项</span>
            <strong>{{ focusTicket ? focusTicket.no : '暂无事项' }}</strong>
          </div>
          <GBadge v-if="focusTicket" :tone="stageTone(focusTicket.stage)">
            {{ stageLabels[focusTicket.stage] }}
          </GBadge>
        </template>

        <div class="focus-panel__body">
          <div class="queue-filter" role="group" aria-label="处理队列筛选">
            <button
              v-for="filter in filters"
              :key="filter.id"
              type="button"
              :aria-pressed="activeFilter === filter.id"
              @click="activeFilter = filter.id"
            >
              {{ filter.label }}
            </button>
          </div>

          <template v-if="focusTicket && focusStep">
            <div class="focus-ticket">
              <div class="focus-ticket__meta">
                <span class="focus-ticket__priority">{{ focusTicket.priority ?? 'P3' }}</span>
                <span>{{ focusTicket.project ?? '本地工作区' }}</span>
                <span class="mono">{{ focusTicket.branch ?? focusTicket.targetRef }}</span>
              </div>
              <button
                type="button"
                class="focus-ticket__title"
                @click="openTicket(focusTicket)"
              >
                {{ focusTicket.title }}
                <GIcon name="external" :size="16" />
              </button>
              <p class="focus-ticket__description">
                {{ focusTicket.dependencies?.length ? `涉及 ${focusTicket.dependencies.length} 个依赖项目。` : '当前没有标记的跨项目依赖。' }}
              </p>
            </div>

            <div class="next-step">
              <div class="next-step__icon" aria-hidden="true">
                <GIcon name="spark" :size="18" />
              </div>
              <div>
                <span>建议下一步</span>
                <strong>{{ focusStep.label }}</strong>
                <p>{{ focusStep.hint }}</p>
              </div>
              <GButton variant="primary" @click="openDestination(focusTicket, focusStep.destination)">
                {{ focusStep.label }}
              </GButton>
            </div>
          </template>

          <div v-else class="focus-empty">
            <GIcon name="kanban" :size="18" />
            <span>当前筛选下没有需要处理的工单。</span>
            <GButton variant="secondary" size="sm" @click="activeFilter = 'all'">查看全部进行中</GButton>
          </div>
        </div>
      </GCard>

      <GCard class="queue-panel" :padding="false">
        <template #head>
          <div class="panel-heading">
            <span>待处理队列</span>
            <strong>{{ queueTickets.length }} 项</strong>
          </div>
          <GIcon name="kanban" :size="16" />
        </template>

        <div class="queue-list">
          <button
            v-for="ticket in queueTickets"
            :key="ticket.no"
            type="button"
            class="queue-item"
            :class="{ 'queue-item--selected': ticket.no === focusTicket?.no }"
            :aria-pressed="ticket.no === focusTicket?.no"
            @click="selectTicket(ticket)"
          >
            <span class="queue-item__meta">
              <span class="mono">{{ ticket.no }}</span>
              <span>{{ ticket.priority ?? 'P3' }}</span>
            </span>
            <strong>{{ ticket.title }}</strong>
            <span class="queue-item__foot">
              <GBadge :tone="stageTone(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge>
              <span>{{ nextStepFor(ticket).label }}</span>
            </span>
          </button>
          <div v-if="queueTickets.length === 0" class="queue-empty">
            <span>没有匹配的工单。</span>
            <button type="button" @click="activeFilter = 'all'">清除筛选</button>
          </div>
        </div>
      </GCard>
    </section>

    <section class="home__support-grid" aria-label="决策与停滞工作">
      <GCard class="support-panel" :padding="false">
        <template #head>
          <div class="panel-heading">
            <span>近期决策</span>
            <strong>审核节点</strong>
          </div>
          <GIcon name="shield" :size="16" />
        </template>

        <div class="support-list">
          <button
            v-for="ticket in decisionTickets"
            :key="ticket.no"
            type="button"
            class="support-row"
            @click="openDestination(ticket, nextStepFor(ticket).destination)"
          >
            <span class="support-row__no mono">{{ ticket.no }}</span>
            <span class="support-row__copy">
              <strong>{{ ticket.title }}</strong>
              <span>{{ decisionSummary(ticket) }}</span>
            </span>
            <span class="support-row__action">
              {{ nextStepFor(ticket).label }}
              <GIcon name="chevron-right" :size="15" />
            </span>
          </button>
          <p v-if="decisionTickets.length === 0" class="support-empty">暂时没有需要判断的审核事项。</p>
        </div>
      </GCard>

      <GCard class="support-panel support-panel--stalled" :padding="false">
        <template #head>
          <div class="panel-heading">
            <span>需要拉起</span>
            <strong>停滞工作</strong>
          </div>
          <GIcon name="git-branch" :size="16" />
        </template>

        <div class="support-list">
          <button
            v-for="ticket in stalledTickets"
            :key="ticket.no"
            type="button"
            class="support-row"
            @click="openTicket(ticket)"
          >
            <span class="support-row__no mono">{{ ticket.no }}</span>
            <span class="support-row__copy">
              <strong>{{ ticket.title }}</strong>
              <span>{{ stalledSummary(ticket) }}</span>
            </span>
            <span class="support-row__action">
              查看详情
              <GIcon name="chevron-right" :size="15" />
            </span>
          </button>
          <p v-if="stalledTickets.length === 0" class="support-empty">当前没有标记为停滞的工作。</p>
        </div>
      </GCard>
    </section>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: clamp(24px, 3.2vw, 42px) clamp(20px, 4vw, 58px) 30px;
  overflow: auto;
}

.home__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  max-width: 1380px;
}

.home__heading {
  max-width: 720px;
}

.home__context {
  margin: 0 0 8px;
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.05em;
}

.home__heading h2 {
  max-width: 650px;
  margin: 0;
  color: var(--ink);
  font-size: clamp(27px, 3.2vw, 40px);
  font-weight: 780;
  letter-spacing: -0.045em;
  line-height: 1.12;
}

.home__heading > p:last-child {
  max-width: 620px;
  margin: 11px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.home__header-actions {
  display: flex;
  flex: none;
  gap: 9px;
}

.home__header-actions :deep(.g-btn) {
  min-height: 40px;
  border-radius: 9px;
}

.home__snapshot {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-width: 1380px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(255, 253, 250, 0.68);
  box-shadow: 0 8px 24px rgba(43, 50, 56, 0.04);
}

.snapshot-item {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: baseline;
  gap: 10px;
  min-height: 76px;
  padding: 17px 20px;
  border-right: 1px solid var(--border);
}

.snapshot-item:last-child {
  border-right: 0;
}

.snapshot-item > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.snapshot-item strong {
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: 25px;
  font-weight: 780;
  letter-spacing: -0.05em;
  line-height: 1;
}

.snapshot-item small {
  min-width: 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.snapshot-item--accent {
  background: rgba(176, 75, 28, 0.065);
}

.snapshot-item--accent strong {
  color: var(--accent-hover);
}

.home__command-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(320px, 0.9fr);
  gap: 16px;
  max-width: 1380px;
}

.focus-panel,
.queue-panel,
.support-panel {
  overflow: hidden;
  border-color: var(--border);
  border-radius: 15px;
  background: var(--panel);
  box-shadow: 0 10px 26px rgba(43, 50, 56, 0.055);
}

.focus-panel {
  border-left: 3px solid var(--accent);
  background:
    linear-gradient(135deg, rgba(176, 75, 28, 0.085), transparent 48%),
    var(--panel);
}

.queue-panel,
.support-panel {
  display: flex;
  flex-direction: column;
}

.focus-panel :deep(.g-card__head),
.queue-panel :deep(.g-card__head),
.support-panel :deep(.g-card__head) {
  min-height: 57px;
  padding: 14px 18px;
  border-bottom-color: var(--border);
}

.panel-heading {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.panel-heading > span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.panel-heading strong {
  overflow: hidden;
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.focus-panel__body {
  display: grid;
  gap: 20px;
  padding: 16px 18px 18px;
}

.queue-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.queue-filter button {
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid var(--border);
  border-radius: 7px;
  color: var(--text-secondary);
  background: var(--panel);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.14s ease, background-color 0.14s ease, color 0.14s ease;
}

.queue-filter button:hover {
  border-color: var(--border-strong);
  background: var(--hover);
  color: var(--text);
}

.queue-filter button[aria-pressed='true'] {
  border-color: rgba(176, 75, 28, 0.28);
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.focus-ticket {
  display: grid;
  gap: 9px;
}

.focus-ticket__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
  font-size: 11px;
}

.focus-ticket__meta > span:not(:last-child)::after {
  margin-left: 7px;
  color: var(--border-strong);
  content: '/';
}

.focus-ticket__priority {
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-weight: 800;
}

.focus-ticket__title {
  display: inline-flex;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 9px;
  width: fit-content;
  max-width: 100%;
  padding: 0;
  border: 0;
  color: var(--ink);
  background: transparent;
  font-size: clamp(20px, 2.3vw, 28px);
  font-weight: 760;
  letter-spacing: -0.035em;
  line-height: 1.25;
  text-align: left;
  cursor: pointer;
}

.focus-ticket__title:hover {
  color: var(--accent-hover);
}

.focus-ticket__title :deep(svg) {
  flex: none;
  margin-top: 5px;
}

.focus-ticket__description {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.next-step {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 13px;
  border: 1px solid rgba(176, 75, 28, 0.18);
  border-radius: 11px;
  background: rgba(176, 75, 28, 0.075);
}

.next-step__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 9px;
  color: var(--accent-hover);
  background: rgba(255, 253, 250, 0.72);
}

.next-step span,
.next-step p {
  color: var(--text-muted);
  font-size: 11px;
}

.next-step strong {
  display: block;
  margin-top: 1px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.next-step p {
  margin: 3px 0 0;
  line-height: 1.45;
}

.next-step :deep(.g-btn) {
  min-height: 36px;
  border-radius: 8px;
}

.focus-empty {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 132px;
  padding: 18px;
  border: 1px dashed var(--border-strong);
  border-radius: 11px;
  color: var(--text-muted);
  font-size: 13px;
}

.focus-empty :deep(.g-btn) {
  margin-left: auto;
}

.queue-panel :deep(.g-card__body) {
  min-height: 0;
  padding: 0;
}

.queue-list {
  display: grid;
  min-height: 0;
  max-height: 405px;
  overflow-y: auto;
}

.queue-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 7px 12px;
  width: 100%;
  padding: 14px 16px;
  border: 0;
  border-bottom: 1px solid var(--border);
  color: var(--text);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease, box-shadow 0.14s ease;
}

.queue-item:last-child {
  border-bottom: 0;
}

.queue-item:hover {
  background: var(--hover);
}

.queue-item--selected {
  background: rgba(176, 75, 28, 0.065);
  box-shadow: inset 3px 0 0 var(--accent);
}

.queue-item__meta {
  display: flex;
  gap: 7px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
}

.queue-item__meta > span:last-child {
  color: var(--accent-hover);
}

.queue-item > strong {
  grid-column: 1 / -1;
  display: -webkit-box;
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 710;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.queue-item__foot {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-muted);
  font-size: 11px;
}

.queue-item__foot :deep(.g-badge) {
  font-size: 10px;
}

.queue-empty {
  display: grid;
  gap: 8px;
  place-items: center;
  min-height: 160px;
  padding: 20px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

.queue-empty button {
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.home__support-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  max-width: 1380px;
}

.support-panel--stalled {
  border-top: 3px solid var(--warning);
}

.support-panel--stalled :deep(.g-card__head) {
  border-top: 0;
}

.support-panel :deep(.g-card__body) {
  padding: 0;
}

.support-list {
  display: grid;
}

.support-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 74px;
  padding: 12px 16px;
  border: 0;
  border-bottom: 1px solid var(--border);
  color: var(--text);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.support-row:last-child {
  border-bottom: 0;
}

.support-row:hover {
  background: var(--hover);
}

.support-row__no {
  align-self: flex-start;
  padding-top: 2px;
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 750;
}

.support-row__copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.support-row__copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-row__copy span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-row__action {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.support-row:hover .support-row__action {
  color: var(--accent-hover);
}

.support-empty {
  margin: 0;
  padding: 26px 16px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

@media (max-width: 1000px) {
  .home__command-grid {
    grid-template-columns: minmax(0, 1.25fr) minmax(280px, 0.85fr);
  }

  .snapshot-item {
    grid-template-columns: auto 1fr;
  }

  .snapshot-item small {
    grid-column: 1 / -1;
  }

  .next-step {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .next-step :deep(.g-btn) {
    grid-column: 2;
    justify-self: start;
  }
}

@media (max-width: 760px) {
  .home {
    gap: 14px;
    padding: 18px 14px 24px;
  }

  .home__header {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }

  .home__header-actions {
    width: 100%;
  }

  .home__header-actions :deep(.g-btn) {
    flex: 1;
  }

  .home__snapshot,
  .home__command-grid,
  .home__support-grid {
    grid-template-columns: 1fr;
  }

  .snapshot-item {
    grid-template-columns: auto auto 1fr;
    min-height: 66px;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .snapshot-item:last-child {
    border-bottom: 0;
  }

  .snapshot-item small {
    grid-column: auto;
  }

  .queue-list {
    max-height: 340px;
  }
}

@media (max-width: 470px) {
  .home__heading h2 {
    font-size: 27px;
  }

  .home__header-actions {
    flex-direction: column;
  }

  .focus-ticket__meta > span:last-child {
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .focus-ticket__meta > span:last-child::after {
    content: none;
  }

  .next-step {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .next-step :deep(.g-btn) {
    grid-column: 1 / -1;
    justify-self: stretch;
  }

  .support-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .support-row__action {
    grid-column: 2;
  }
}
</style>
