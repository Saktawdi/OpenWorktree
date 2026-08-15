<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { GBadge, GButton, GCard, GIcon, GInput, GSelect } from '@/components/ui';
import { mockTickets, stageLabels } from '@/mocks/prototypeData';
import type { ExecTokenSource, Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type TimeFilter = 'all' | '12h' | '24h';
type StageFilter = 'all' | 'delivery' | 'review' | 'ready' | 'closed';
type SourceFilter = 'all' | ExecTokenSource | 'missing';
type SortKey = 'tokens' | 'updated' | 'title' | 'stage';
type SortDirection = 'asc' | 'desc';
type MeasurementState = 'tracked' | 'manual' | 'missing' | 'anomaly';

const router = useRouter();
const timeFilter = ref<TimeFilter>('all');
const stageFilter = ref<StageFilter>('all');
const sourceFilter = ref<SourceFilter>('all');
const query = ref('');
const sortKey = ref<SortKey>('tokens');
const sortDirection = ref<SortDirection>('desc');
const selectedNo = ref<string | null>(mockTickets[0]?.no ?? null);
const drillNotice = ref('');

const latestUpdatedAt = computed(() => Math.max(...mockTickets.map((ticket) => new Date(ticket.updatedAt).getTime())));
const filteredTickets = computed(() => {
  const normalizedQuery = query.value.trim().toLowerCase();
  return mockTickets.filter((ticket) => {
    const matchesTime = matchesTimeFilter(ticket, timeFilter.value);
    const matchesStage = matchesStageFilter(ticket.stage, stageFilter.value);
    const matchesSource = matchesSourceFilter(ticket.execTokenSource, sourceFilter.value);
    const haystack = [ticket.no, ticket.title, ticket.project, ticket.branch, ...(ticket.labels ?? [])]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return matchesTime && matchesStage && matchesSource && (!normalizedQuery || haystack.includes(normalizedQuery));
  });
});
const measuredTickets = computed(() => filteredTickets.value.filter((ticket) => ticket.execTokenTotal != null));
const totalTokens = computed(() => measuredTickets.value.reduce((sum, ticket) => sum + (ticket.execTokenTotal ?? 0), 0));
const averageTokens = computed(() => (measuredTickets.value.length ? totalTokens.value / measuredTickets.value.length : 0));
const medianTokens = computed(() => median(measuredTickets.value.map((ticket) => ticket.execTokenTotal ?? 0)));
const anomalyThreshold = computed(() => Math.ceil(Math.max(averageTokens.value * 1.5, medianTokens.value * 1.35)));
const missingTickets = computed(() => filteredTickets.value.filter((ticket) => ticket.execTokenTotal == null));
const anomalyTickets = computed(() =>
  measuredTickets.value.filter((ticket) => measuredTickets.value.length >= 3 && (ticket.execTokenTotal ?? 0) >= anomalyThreshold.value),
);
const coverage = computed(() =>
  filteredTickets.value.length ? Math.round((measuredTickets.value.length / filteredTickets.value.length) * 100) : 0,
);
const largestTicket = computed(() =>
  [...measuredTickets.value].sort((a, b) => (b.execTokenTotal ?? 0) - (a.execTokenTotal ?? 0))[0] ?? null,
);
const sortedTickets = computed(() => {
  const items = [...filteredTickets.value];
  return items.sort((a, b) => compareTickets(a, b, sortKey.value, sortDirection.value));
});
const selectedTicket = computed(() =>
  sortedTickets.value.find((ticket) => ticket.no === selectedNo.value) ?? sortedTickets.value[0] ?? null,
);
const activeFilterSummary = computed(
  () => `${timeFilterLabel(timeFilter.value)} / ${stageFilterLabel(stageFilter.value)} / ${sourceFilterLabel(sourceFilter.value)}`,
);

watch(sortedTickets, (tickets) => {
  if (!tickets.some((ticket) => ticket.no === selectedNo.value)) selectedNo.value = tickets[0]?.no ?? null;
}, { immediate: true });

function matchesTimeFilter(ticket: Ticket, filter: TimeFilter) {
  if (filter === 'all') return true;
  const hours = filter === '12h' ? 12 : 24;
  return new Date(ticket.updatedAt).getTime() >= latestUpdatedAt.value - hours * 60 * 60 * 1000;
}

function matchesStageFilter(stage: TicketStage, filter: StageFilter) {
  if (filter === 'all') return true;
  if (filter === 'delivery') return ['PENDING', 'IN_PROGRESS', 'PRESUBMITTED'].includes(stage);
  if (filter === 'review') return ['IN_REVIEW', 'NEEDS_HUMAN', 'REJECTED'].includes(stage);
  if (filter === 'ready') return stage === 'READY_TO_PUBLISH';
  return ['DONE', 'CANCELLED'].includes(stage);
}

function matchesSourceFilter(source: ExecTokenSource | null, filter: SourceFilter) {
  if (filter === 'all') return true;
  if (filter === 'missing') return source == null;
  return source === filter;
}

function median(values: number[]) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle]! : (sorted[middle - 1]! + sorted[middle]!) / 2;
}

function compareTickets(a: Ticket, b: Ticket, key: SortKey, direction: SortDirection) {
  if (key === 'tokens') {
    const aMissing = a.execTokenTotal == null;
    const bMissing = b.execTokenTotal == null;
    if (aMissing !== bMissing) return aMissing ? 1 : -1;
    return ((a.execTokenTotal ?? 0) - (b.execTokenTotal ?? 0)) * (direction === 'asc' ? 1 : -1);
  }
  if (key === 'updated') {
    return (new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime()) * (direction === 'asc' ? 1 : -1);
  }
  if (key === 'stage') {
    return stageLabels[a.stage].localeCompare(stageLabels[b.stage], 'zh-CN') * (direction === 'asc' ? 1 : -1);
  }
  return a.title.localeCompare(b.title, 'zh-CN') * (direction === 'asc' ? 1 : -1);
}

function stageTone(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function measurementState(ticket: Ticket): MeasurementState {
  if (ticket.execTokenTotal == null) return 'missing';
  if (measuredTickets.value.length >= 3 && ticket.execTokenTotal >= anomalyThreshold.value) return 'anomaly';
  if (ticket.execTokenSource === 'manual') return 'manual';
  return 'tracked';
}

function measurementMeta(ticket: Ticket) {
  const state = measurementState(ticket);
  const map: Record<MeasurementState, { label: string; tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger' }> = {
    tracked: { label: '已计量', tone: 'success' },
    manual: { label: '人工回填', tone: 'neutral' },
    missing: { label: '缺失', tone: 'warning' },
    anomaly: { label: '异常偏高', tone: 'danger' },
  };
  return map[state];
}

function sourceLabel(source: ExecTokenSource | null) {
  if (source === 'agent_cli') return 'Agent CLI';
  if (source === 'manual') return '人工回填';
  if (source === 'unavailable') return '不可用';
  return '未回写';
}

function sourceFilterLabel(filter: SourceFilter) {
  if (filter === 'all') return '全部来源';
  if (filter === 'agent_cli') return 'Agent CLI';
  if (filter === 'manual') return '人工回填';
  if (filter === 'unavailable') return '不可用';
  return '缺失计量';
}

function stageFilterLabel(filter: StageFilter) {
  if (filter === 'all') return '全部状态';
  if (filter === 'delivery') return '交付进行中';
  if (filter === 'review') return '审核相关';
  if (filter === 'ready') return '可发布';
  return '已结束';
}

function timeFilterLabel(filter: TimeFilter) {
  if (filter === 'all') return '全部时间';
  if (filter === '12h') return '最近 12 小时';
  return '最近 24 小时';
}

function formatTokens(value: number | null | undefined) {
  if (value == null) return '未回写';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatDate(iso: string) {
  const date = new Date(iso);
  return `${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')} ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}`;
}

function toggleSort(key: SortKey) {
  if (sortKey.value === key) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDirection.value = key === 'title' || key === 'stage' ? 'asc' : 'desc';
  }
}

function sortMark(key: SortKey) {
  if (sortKey.value !== key) return '';
  return sortDirection.value === 'asc' ? '↑' : '↓';
}

function selectTicket(ticket: Ticket) {
  selectedNo.value = ticket.no;
  drillNotice.value = `已选中 ${ticket.no}，右侧显示该工单的成本上下文。`;
}

function showMissing() {
  sourceFilter.value = 'missing';
  drillNotice.value = '已筛选出尚未回写执行计量的工单。';
}

function showAnomalies() {
  sortKey.value = 'tokens';
  sortDirection.value = 'desc';
  const top = anomalyTickets.value[0];
  if (top) selectedNo.value = top.no;
  drillNotice.value = top ? `已定位异常花费工单 ${top.no}。` : '当前筛选中没有异常花费记录。';
}

function resetFilters() {
  timeFilter.value = 'all';
  stageFilter.value = 'all';
  sourceFilter.value = 'all';
  query.value = '';
  drillNotice.value = '已恢复全部成本记录。';
}

function csvCell(value: string | number | null | undefined) {
  const text = value == null ? '' : String(value);
  return `"${text.replace(/"/g, '""')}"`;
}

function exportCsv() {
  const lines = [
    '\uFEFF# GATE cost decision export',
    `# filters: ${activeFilterSummary.value}; keyword=${query.value.trim() || 'none'}`,
    'ticket_no,title,stage,updated_at,exec_token_total,exec_token_source,measurement_state',
    ...sortedTickets.value.map((ticket) => [
      csvCell(ticket.no),
      csvCell(ticket.title),
      csvCell(ticket.stage),
      csvCell(ticket.updatedAt),
      csvCell(ticket.execTokenTotal),
      csvCell(ticket.execTokenSource),
      csvCell(measurementMeta(ticket).label),
    ].join(',')),
  ];
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const anchor = document.createElement('a');
  const url = URL.createObjectURL(blob);
  anchor.href = url;
  anchor.download = 'gate-cost-filtered.csv';
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
  drillNotice.value = `已导出 ${sortedTickets.value.length} 条当前筛选记录。`;
}
</script>

<template>
  <div class="cost-decision">
    <header class="cost-head">
      <div>
        <p class="section-kicker">COST DECISION DESK</p>
        <h2>执行成本决策台</h2>
        <p>把执行用量、缺失计量和异常花费放到同一个可钻取的工作面里。</p>
      </div>
      <GButton variant="secondary" @click="exportCsv">
        <GIcon name="external" :size="15" />
        导出当前筛选
      </GButton>
    </header>

    <p class="sr-notice" role="status" aria-live="polite">{{ drillNotice }}</p>

    <section class="cost-overview" aria-label="成本汇总">
      <div class="cost-overview__primary">
        <span>当前筛选执行用量</span>
        <strong class="mono">{{ formatTokens(totalTokens) }}</strong>
        <p>
          {{ measuredTickets.length }} / {{ filteredTickets.length }} 张工单已计量
          <span aria-hidden="true">/</span>
          中位数 {{ formatTokens(medianTokens) }} token
        </p>
        <div class="cost-overview__meta">
          <span>筛选：{{ activeFilterSummary }}</span>
          <span>窗口以最近活动 {{ formatDate(new Date(latestUpdatedAt).toISOString()) }} 为基准</span>
        </div>
      </div>
      <div class="cost-overview__focus">
        <span>当前最高花费</span>
        <template v-if="largestTicket">
          <button type="button" @click="selectTicket(largestTicket)">
            <strong class="mono">{{ largestTicket.no }}</strong>
            <span>{{ largestTicket.title }}</span>
            <b class="mono">{{ formatTokens(largestTicket.execTokenTotal) }}</b>
          </button>
        </template>
        <p v-else>当前筛选尚无可用的执行用量。</p>
      </div>
    </section>

    <section class="signal-grid" aria-label="成本信号">
      <button type="button" class="signal-card signal-card--missing" @click="showMissing">
        <span class="signal-card__label">缺失计量</span>
        <strong>{{ missingTickets.length }}</strong>
        <p>尚未回写执行用量，点击查看。</p>
      </button>
      <button type="button" class="signal-card signal-card--anomaly" @click="showAnomalies">
        <span class="signal-card__label">异常花费</span>
        <strong>{{ anomalyTickets.length }}</strong>
        <p>阈值 {{ formatTokens(anomalyThreshold) }} token，点击定位。</p>
      </button>
      <div class="signal-card signal-card--coverage">
        <span class="signal-card__label">计量覆盖率</span>
        <strong>{{ coverage }}%</strong>
        <p>平均 {{ formatTokens(Math.round(averageTokens)) }} token / 已计量工单。</p>
      </div>
    </section>

    <GCard class="filter-card">
      <template #head>
        <div class="card-heading">
          <span class="card-heading__eyebrow">FILTERS</span>
          <strong>筛选与钻取</strong>
        </div>
        <GButton variant="ghost" size="sm" @click="resetFilters">重置筛选</GButton>
      </template>
      <div class="filter-controls">
        <label>
          <span>时间窗口</span>
          <GSelect v-model="timeFilter" :options="[
            { label: '全部时间', value: 'all' },
            { label: '最近 12 小时', value: '12h' },
            { label: '最近 24 小时', value: '24h' },
          ]" />
        </label>
        <label>
          <span>工单状态</span>
          <GSelect v-model="stageFilter" :options="[
            { label: '全部状态', value: 'all' },
            { label: '交付进行中', value: 'delivery' },
            { label: '审核相关', value: 'review' },
            { label: '可发布', value: 'ready' },
            { label: '已结束', value: 'closed' },
          ]" />
        </label>
        <label>
          <span>数据来源</span>
          <GSelect v-model="sourceFilter" :options="[
            { label: '全部来源', value: 'all' },
            { label: 'Agent CLI', value: 'agent_cli' },
            { label: '人工回填', value: 'manual' },
            { label: '缺失计量', value: 'missing' },
          ]" />
        </label>
        <label class="filter-controls__search">
          <span>搜索工单</span>
          <GInput v-model="query" placeholder="工单号、标题、项目或标签" />
        </label>
      </div>
    </GCard>

    <div class="cost-body">
      <GCard class="cost-table-card">
        <template #head>
          <div class="card-heading">
            <span class="card-heading__eyebrow">RECORDS</span>
            <strong>工单成本记录</strong>
          </div>
          <span class="record-count">{{ sortedTickets.length }} 条结果</span>
        </template>

        <div v-if="sortedTickets.length" class="table-wrap">
          <table class="cost-table">
            <thead>
              <tr>
                <th>工单</th>
                <th><button type="button" @click="toggleSort('title')">标题 <span aria-hidden="true">{{ sortMark('title') }}</span></button></th>
                <th><button type="button" @click="toggleSort('stage')">状态 <span aria-hidden="true">{{ sortMark('stage') }}</span></button></th>
                <th>来源</th>
                <th><button type="button" @click="toggleSort('tokens')">执行 Token <span aria-hidden="true">{{ sortMark('tokens') }}</span></button></th>
                <th><button type="button" @click="toggleSort('updated')">最近活动 <span aria-hidden="true">{{ sortMark('updated') }}</span></button></th>
                <th aria-label="操作" />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="ticket in sortedTickets"
                :key="ticket.no"
                :class="{ selected: ticket.no === selectedTicket?.no }"
                @click="selectTicket(ticket)"
              >
                <td class="cost-table__no mono">{{ ticket.no }}</td>
                <td>
                  <strong class="cost-table__title">{{ ticket.title }}</strong>
                  <span class="cost-table__project">{{ ticket.project ?? '未指定项目' }}</span>
                </td>
                <td><GBadge :tone="stageTone(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge></td>
                <td><span class="source-cell">{{ sourceLabel(ticket.execTokenSource) }}</span></td>
                <td>
                  <strong class="cost-table__tokens mono">{{ formatTokens(ticket.execTokenTotal) }}</strong>
                  <GBadge :tone="measurementMeta(ticket).tone">{{ measurementMeta(ticket).label }}</GBadge>
                </td>
                <td class="cost-table__date mono">{{ formatDate(ticket.updatedAt) }}</td>
                <td>
                  <GButton variant="ghost" size="sm" :aria-label="`查看 ${ticket.no} 成本上下文`" @click.stop="selectTicket(ticket)">查看</GButton>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="records-empty">
          <GIcon name="search" :size="21" aria-hidden="true" />
          <strong>没有符合条件的成本记录</strong>
          <p>尝试扩大时间窗口、切换状态或重置筛选。</p>
          <GButton variant="secondary" size="sm" @click="resetFilters">恢复全部记录</GButton>
        </div>
      </GCard>

      <aside class="cost-inspector" aria-label="选中工单成本上下文">
        <GCard v-if="selectedTicket" class="inspector-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">SELECTED TICKET</span>
              <strong>成本上下文</strong>
            </div>
            <GBadge :tone="measurementMeta(selectedTicket).tone">{{ measurementMeta(selectedTicket).label }}</GBadge>
          </template>
          <div class="inspector-card__ticket">
            <span class="mono">{{ selectedTicket.no }}</span>
            <h3>{{ selectedTicket.title }}</h3>
          </div>
          <div class="inspector-metric">
            <span>执行 Token</span>
            <strong class="mono">{{ formatTokens(selectedTicket.execTokenTotal) }}</strong>
            <small>{{ sourceLabel(selectedTicket.execTokenSource) }}</small>
          </div>
          <dl class="inspector-list">
            <div><dt>当前阶段</dt><dd><GBadge :tone="stageTone(selectedTicket.stage)">{{ stageLabels[selectedTicket.stage] }}</GBadge></dd></div>
            <div><dt>目标分支</dt><dd class="mono">{{ selectedTicket.targetRef }}</dd></div>
            <div><dt>tree 锚点</dt><dd class="mono">{{ selectedTicket.treeHash ?? '未建立' }}</dd></div>
            <div><dt>基线提交</dt><dd class="mono">{{ selectedTicket.baseCommit ?? '未建立' }}</dd></div>
          </dl>
          <div v-if="measurementState(selectedTicket) === 'anomaly'" class="inspector-alert inspector-alert--danger">
            比当前筛选的异常阈值高 {{ formatTokens((selectedTicket.execTokenTotal ?? 0) - anomalyThreshold) }} token。
          </div>
          <div v-else-if="measurementState(selectedTicket) === 'missing'" class="inspector-alert">
            该工单尚无执行用量回写，建议在交付前补齐来源与计量。
          </div>
          <GButton variant="secondary" block class="inspector-card__action" @click="router.push({ name: 'ticket-detail', params: { no: selectedTicket.no } })">
            打开工单工作台
          </GButton>
        </GCard>
        <GCard v-else class="inspector-empty">
          <GIcon name="chart" :size="21" aria-hidden="true" />
          <strong>选择一张工单查看上下文</strong>
          <p>表格的查看操作会展示来源、锚点和花费风险。</p>
        </GCard>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.cost-decision {
  flex: 1;
  min-height: 0;
  width: min(100%, 1520px);
  margin: 0 auto;
  padding: clamp(20px, 3vw, 38px) clamp(18px, 3.4vw, 52px) 30px;
  overflow: auto;
  color: var(--text);
}

.cost-head,
.cost-overview,
.cost-overview__meta,
.card-heading,
.filter-controls,
.session-like {
  display: flex;
}

.cost-head {
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.section-kicker,
.card-heading__eyebrow {
  margin: 0;
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.cost-head h2 {
  margin: 5px 0 0;
  color: var(--ink);
  font-size: clamp(25px, 3.2vw, 35px);
  font-weight: 790;
  letter-spacing: -0.045em;
  line-height: 1.12;
}

.cost-head p:not(.section-kicker) {
  max-width: 600px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.cost-head :deep(.g-btn) {
  flex: none;
  margin-top: 2px;
  border-radius: 9px;
}

.sr-notice {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.cost-overview {
  gap: 16px;
  margin-top: 24px;
  padding: 18px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 14px;
  background: linear-gradient(100deg, var(--panel), rgba(230, 109, 47, 0.055));
  box-shadow: 0 8px 22px rgba(36, 52, 70, 0.05);
}

.cost-overview__primary {
  flex: 1;
  min-width: 0;
}

.cost-overview__primary > span,
.cost-overview__focus > span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.cost-overview__primary > strong {
  display: block;
  margin-top: 4px;
  color: var(--ink);
  font-size: clamp(30px, 4.2vw, 48px);
  font-weight: 820;
  letter-spacing: -0.07em;
  line-height: 1;
}

.cost-overview__primary > p {
  margin: 9px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.cost-overview__primary > p span {
  margin: 0 5px;
  color: var(--text-faint);
}

.cost-overview__meta {
  flex-wrap: wrap;
  gap: 6px 13px;
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 10px;
}

.cost-overview__focus {
  display: grid;
  align-content: start;
  flex: 0 0 min(34%, 350px);
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--panel-2);
}

.cost-overview__focus button {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 0;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.cost-overview__focus button:hover strong:first-child,
.cost-overview__focus button:hover span {
  color: var(--accent-hover);
}

.cost-overview__focus button strong:first-child {
  color: var(--accent-hover);
  font-size: 11px;
}

.cost-overview__focus button span {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cost-overview__focus button b {
  color: var(--ink);
  font-size: 16px;
}

.cost-overview__focus > p {
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.signal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr minmax(220px, 0.9fr);
  gap: 12px;
  margin-top: 14px;
}

.signal-card {
  min-height: 116px;
  padding: 16px 17px;
  border: 1px solid var(--border);
  border-radius: 13px;
  color: var(--text-secondary);
  background: var(--panel);
  box-shadow: 0 7px 18px rgba(36, 52, 70, 0.045);
  text-align: left;
}

button.signal-card {
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.15s ease, box-shadow 0.15s ease;
}

button.signal-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 11px 24px rgba(36, 52, 70, 0.075);
}

.signal-card__label,
.signal-card strong,
.signal-card p {
  display: block;
}

.signal-card__label {
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.signal-card strong {
  margin-top: 8px;
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: 27px;
  letter-spacing: -0.05em;
  line-height: 1;
}

.signal-card p {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.signal-card--missing {
  border-left: 3px solid var(--warning);
}

.signal-card--missing strong {
  color: var(--warning);
}

.signal-card--anomaly {
  border-left: 3px solid var(--danger);
}

.signal-card--anomaly strong {
  color: var(--danger);
}

.signal-card--coverage {
  background: var(--panel-2);
}

.signal-card--coverage strong {
  color: var(--success);
}

.filter-card {
  margin-top: 14px;
  border-radius: 14px;
  border-color: var(--border);
  background: var(--panel);
  box-shadow: 0 7px 18px rgba(36, 52, 70, 0.045);
}

.filter-card :deep(.g-card__head),
.cost-table-card :deep(.g-card__head),
.inspector-card :deep(.g-card__head) {
  min-height: 57px;
  padding: 13px 16px;
}

.card-heading {
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.card-heading strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.filter-card :deep(.g-card__head) :deep(.g-btn) {
  border-radius: 8px;
}

.filter-card :deep(.g-card__body) {
  padding: 15px 16px 17px;
}

.filter-controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(150px, 0.8fr)) minmax(230px, 1.3fr);
  gap: 11px;
}

.filter-controls label {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.filter-controls label > span {
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 760;
}

.filter-controls :deep(.g-select),
.filter-controls :deep(.g-input) {
  min-height: 39px;
  border-radius: 8px;
}

.cost-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(285px, 340px);
  gap: 17px;
  align-items: start;
  margin-top: 17px;
}

.cost-table-card,
.inspector-card,
.inspector-empty {
  border-radius: 14px;
  border-color: var(--border);
  background: var(--panel);
  box-shadow: 0 9px 24px rgba(36, 52, 70, 0.055);
}

.cost-table-card :deep(.g-card__body) {
  padding: 0;
}

.record-count {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.table-wrap {
  overflow: auto;
}

.cost-table {
  width: 100%;
  min-width: 860px;
  border-collapse: collapse;
  font-size: 12px;
}

.cost-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-align: left;
  white-space: nowrap;
}

.cost-table th button {
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.cost-table th button:hover {
  color: var(--accent-hover);
}

.cost-table td {
  padding: 12px;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.cost-table tbody tr {
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.cost-table tbody tr:hover {
  background: var(--hover);
}

.cost-table tbody tr.selected {
  background: var(--accent-soft);
  box-shadow: inset 3px 0 0 var(--accent);
}

.cost-table tbody tr:last-child td {
  border-bottom: 0;
}

.cost-table__no {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 800;
}

.cost-table__title,
.cost-table__project {
  display: block;
}

.cost-table__title {
  max-width: 250px;
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cost-table__project {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 10px;
}

.source-cell {
  color: var(--text-muted);
  font-size: 11px;
}

.cost-table__tokens {
  display: block;
  color: var(--ink);
  font-size: 12px;
}

.cost-table td :deep(.g-badge) {
  margin-top: 4px;
  padding: 2px 6px;
  font-size: 9px;
}

.cost-table__date {
  color: var(--text-muted);
  font-size: 10px;
  white-space: nowrap;
}

.cost-table td :deep(.g-btn) {
  border-radius: 7px;
}

.records-empty {
  display: grid;
  min-height: 355px;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 22px;
  color: var(--text-muted);
  text-align: center;
}

.records-empty :deep(svg) {
  color: var(--accent-hover);
}

.records-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.records-empty p {
  margin: 0 0 3px;
  font-size: 12px;
}

.records-empty :deep(.g-btn) {
  border-radius: 8px;
}

.cost-inspector {
  position: sticky;
  top: 16px;
}

.inspector-card :deep(.g-card__body) {
  padding: 16px;
}

.inspector-card__ticket > span {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 800;
}

.inspector-card__ticket h3 {
  margin: 5px 0 0;
  color: var(--ink);
  font-size: 16px;
  font-weight: 760;
  letter-spacing: -0.02em;
  line-height: 1.35;
}

.inspector-metric {
  display: grid;
  gap: 4px;
  margin-top: 15px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
}

.inspector-metric span,
.inspector-metric small {
  color: var(--text-muted);
  font-size: 10px;
}

.inspector-metric strong {
  color: var(--ink);
  font-size: 25px;
  letter-spacing: -0.055em;
}

.inspector-list {
  display: grid;
  gap: 0;
  padding: 0;
  margin: 13px 0 0;
}

.inspector-list > div {
  display: grid;
  gap: 4px;
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
}

.inspector-list > div:last-child {
  border-bottom: 0;
}

.inspector-list dt {
  color: var(--text-muted);
  font-size: 10px;
}

.inspector-list dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspector-list dd :deep(.g-badge) {
  margin: 0;
}

.inspector-alert {
  margin-top: 12px;
  padding: 9px 10px;
  border-radius: 8px;
  color: #98651a;
  background: var(--warning-soft);
  font-size: 11px;
  line-height: 1.5;
}

.inspector-alert--danger {
  color: #a5423d;
  background: var(--danger-soft);
}

.inspector-card__action {
  margin-top: 14px;
  border-radius: 8px;
}

.inspector-empty :deep(.g-card__body) {
  display: grid;
  min-height: 230px;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 20px;
  color: var(--text-muted);
  text-align: center;
}

.inspector-empty :deep(svg) {
  color: var(--accent-hover);
}

.inspector-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.inspector-empty p {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
}

@media (max-width: 1080px) {
  .filter-controls {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .filter-controls__search {
    grid-column: 1 / -1;
  }

  .cost-body {
    grid-template-columns: 1fr;
  }

  .cost-inspector {
    position: static;
  }
}

@media (max-width: 720px) {
  .cost-decision {
    padding: 18px 14px 24px;
  }

  .cost-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .cost-head :deep(.g-btn) {
    width: 100%;
  }

  .cost-overview {
    flex-direction: column;
  }

  .cost-overview__focus {
    flex-basis: auto;
    width: 100%;
  }

  .signal-grid {
    grid-template-columns: 1fr;
  }

  .filter-controls {
    grid-template-columns: 1fr;
  }

  .filter-controls__search {
    grid-column: auto;
  }
}
</style>
