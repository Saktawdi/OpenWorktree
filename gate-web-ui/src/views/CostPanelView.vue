<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { listTickets } from '@/api/tickets';
import { GBadge, GButton, GField, GIcon, GInput, GSelect } from '@/components/ui';
import { stageLabels } from '@/constants/stages';
import type { ExecTokenSource, Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type TimeFilter = 'all' | '12h' | '24h';
type StageFilter = 'all' | 'delivery' | 'review' | 'ready' | 'closed';
type SourceFilter = 'all' | ExecTokenSource | 'missing';
type SortKey = 'tokens' | 'updated' | 'title' | 'stage';
type SortDirection = 'asc' | 'desc';
type MeasurementState = 'tracked' | 'manual' | 'missing' | 'anomaly';

const router = useRouter();
const tickets = ref<Ticket[]>([]);
const loading = ref(true);
const loadError = ref('');
const timeFilter = ref<TimeFilter>('all');
const stageFilter = ref<StageFilter>('all');
const sourceFilter = ref<SourceFilter>('all');
const query = ref('');
const sortKey = ref<SortKey>('tokens');
const sortDirection = ref<SortDirection>('desc');
const selectedNo = ref<string | null>(null);
const drillNotice = ref('');

const latestUpdatedAt = computed(() => {
  if (!tickets.value.length) return Date.now();
  return Math.max(...tickets.value.map((ticket) => new Date(ticket.updatedAt).getTime()));
});
const filteredTickets = computed(() => {
  const normalizedQuery = query.value.trim().toLowerCase();
  return tickets.value.filter((ticket) => {
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
  if (source === 'agent_cli') return '智能体命令行';
  if (source === 'manual') return '人工回填';
  if (source === 'unavailable') return '不可用';
  return '未回写';
}

function sourceFilterLabel(filter: SourceFilter) {
  if (filter === 'all') return '全部来源';
  if (filter === 'agent_cli') return '智能体命令行';
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

function openSelectedTicket() {
  const ticket = selectedTicket.value;
  if (!ticket?.projectId) {
    drillNotice.value = '该工单尚未绑定项目，无法进入项目工单工作台。';
    router.push({ name: 'home' });
    return;
  }
  router.push({ name: 'ticket-detail', params: { projectId: ticket.projectId, no: ticket.no } });
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

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    tickets.value = await listTickets();
    if (!tickets.value.some((ticket) => ticket.no === selectedNo.value)) {
      selectedNo.value = tickets.value[0]?.no ?? null;
    }
  } catch {
    loadError.value = '无法读取成本数据，请确认 Gate 后端正在运行。';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <div class="cost-decision" :aria-busy="loading">
    <div class="cost-actions">
      <GButton variant="secondary" :disabled="loading" @click="exportCsv">
        <GIcon name="external" :size="15" />
        导出当前筛选
      </GButton>
    </div>

    <p class="sr-notice" role="status" aria-live="polite">{{ drillNotice }}</p>

    <div v-if="loading" class="page-state page-state--loading" aria-live="polite">
      <span class="loading-line loading-line--wide" />
      <span class="loading-line loading-line--short" />
      <p>正在读取成本记录</p>
    </div>

    <div v-else-if="loadError" class="page-state" role="alert">
      <strong>{{ loadError }}</strong>
      <GButton variant="secondary" size="sm" @click="load">重新读取</GButton>
    </div>

    <template v-else>
      <section class="cost-summary" aria-label="成本汇总">
        <div class="cost-summary__lead">
          <span class="summary-label">筛选后执行令牌</span>
          <strong class="mono">{{ formatTokens(totalTokens) }}</strong>
          <p>{{ measuredTickets.length }} / {{ filteredTickets.length }} 张工单已计量</p>
          <div class="cost-summary__context">
            <span>筛选：{{ activeFilterSummary }}</span>
            <span>最近活动 {{ formatDate(new Date(latestUpdatedAt).toISOString()) }}</span>
          </div>
        </div>

        <div class="cost-summary__stats" aria-label="成本辅助指标">
          <div class="summary-stat">
            <span>计量覆盖率</span>
            <strong class="mono">{{ coverage }}%</strong>
          </div>
          <div class="summary-stat">
            <span>平均用量</span>
            <strong class="mono">{{ formatTokens(Math.round(averageTokens)) }}</strong>
            <small>令牌 / 工单</small>
          </div>
          <div class="summary-stat">
            <span>中位数</span>
            <strong class="mono">{{ formatTokens(medianTokens) }}</strong>
            <small>令牌</small>
          </div>
          <div class="summary-stat summary-stat--highest">
            <span>最高花费</span>
            <button v-if="largestTicket" type="button" @click="selectTicket(largestTicket)">
              <strong class="mono">{{ largestTicket.no }}</strong>
              <span>{{ largestTicket.title }}</span>
              <b class="mono">{{ formatTokens(largestTicket.execTokenTotal) }}</b>
            </button>
            <small v-else>暂无已计量工单</small>
          </div>
        </div>
      </section>

      <section v-if="missingTickets.length || anomalyTickets.length" class="cost-alerts" aria-label="成本提醒">
        <button v-if="missingTickets.length" type="button" class="cost-alert cost-alert--warning" @click="showMissing">
          <span><strong>{{ missingTickets.length }}</strong> 个工单缺失计量</span>
          <span>查看</span>
        </button>
        <button v-if="anomalyTickets.length" type="button" class="cost-alert cost-alert--danger" @click="showAnomalies">
          <span><strong>{{ anomalyTickets.length }}</strong> 个工单花费偏高</span>
          <span>定位</span>
        </button>
      </section>

      <section class="filter-toolbar" aria-label="筛选条件">
        <div class="filter-toolbar__title">
          <strong>筛选</strong>
          <span>{{ sortedTickets.length }} 条结果</span>
        </div>
        <div class="filter-controls">
          <GField class="filter-field" label="时间窗口" for-id="cost-time-filter">
            <GSelect v-model="timeFilter" :options="[
              { label: '全部时间', value: 'all' },
              { label: '最近 12 小时', value: '12h' },
              { label: '最近 24 小时', value: '24h' },
            ]" id="cost-time-filter" />
          </GField>
          <GField class="filter-field" label="工单状态" for-id="cost-stage-filter">
            <GSelect v-model="stageFilter" :options="[
              { label: '全部状态', value: 'all' },
              { label: '交付进行中', value: 'delivery' },
              { label: '审核相关', value: 'review' },
              { label: '可发布', value: 'ready' },
              { label: '已结束', value: 'closed' },
            ]" id="cost-stage-filter" />
          </GField>
          <GField class="filter-field" label="数据来源" for-id="cost-source-filter">
            <GSelect v-model="sourceFilter" :options="[
              { label: '全部来源', value: 'all' },
              { label: '智能体命令行', value: 'agent_cli' },
              { label: '人工回填', value: 'manual' },
              { label: '缺失计量', value: 'missing' },
            ]" id="cost-source-filter" />
          </GField>
          <GField class="filter-field filter-controls__search" label="搜索工单" for-id="cost-query-filter">
            <GInput id="cost-query-filter" v-model="query" placeholder="工单号、标题、项目或标签" />
          </GField>
        </div>
        <GButton variant="ghost" size="sm" @click="resetFilters">重置</GButton>
      </section>

      <div class="cost-body">
        <section class="cost-table-section" aria-label="工单成本记录">
          <header class="section-head">
            <div>
              <h2>工单成本</h2>
              <span>按执行令牌排序，点击记录查看上下文</span>
            </div>
            <span class="record-count mono">{{ sortedTickets.length }} 条</span>
          </header>

          <div v-if="sortedTickets.length" class="table-wrap">
            <table class="cost-table">
              <thead>
                <tr>
                  <th><button type="button" @click="toggleSort('title')">工单 <span aria-hidden="true">{{ sortMark('title') }}</span></button></th>
                  <th><button type="button" @click="toggleSort('stage')">状态 <span aria-hidden="true">{{ sortMark('stage') }}</span></button></th>
                  <th><button type="button" @click="toggleSort('tokens')">执行令牌 <span aria-hidden="true">{{ sortMark('tokens') }}</span></button></th>
                  <th><button type="button" @click="toggleSort('updated')">最近活动 <span aria-hidden="true">{{ sortMark('updated') }}</span></button></th>
                  <th aria-label="操作" />
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="ticket in sortedTickets"
                  :key="ticket.no"
                  :class="{ selected: ticket.no === selectedTicket?.no }"
                  tabindex="0"
                  @click="selectTicket(ticket)"
                  @keydown.enter="selectTicket(ticket)"
                >
                  <td>
                    <div class="cost-table__ticket">
                      <span class="cost-table__no mono">{{ ticket.no }}</span>
                      <strong class="cost-table__title">{{ ticket.title }}</strong>
                      <span class="cost-table__project">{{ ticket.project ?? '未指定项目' }}</span>
                    </div>
                  </td>
                  <td><GBadge :tone="stageTone(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge></td>
                  <td>
                    <strong class="cost-table__tokens mono">{{ formatTokens(ticket.execTokenTotal) }}</strong>
                    <span class="token-meta">
                      <span class="source-cell">{{ sourceLabel(ticket.execTokenSource) }}</span>
                      <GBadge :tone="measurementMeta(ticket).tone">{{ measurementMeta(ticket).label }}</GBadge>
                    </span>
                  </td>
                  <td class="cost-table__date mono">{{ formatDate(ticket.updatedAt) }}</td>
                  <td>
                    <GButton variant="ghost" size="sm" :aria-label="`查看 ${ticket.no} 成本上下文`" @click.stop="selectTicket(ticket)">详情</GButton>
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
        </section>

        <aside class="cost-inspector" aria-label="选中工单成本上下文">
          <section v-if="selectedTicket" class="inspector-panel">
            <div class="inspector-head">
              <span>当前选择</span>
              <GBadge :tone="measurementMeta(selectedTicket).tone">{{ measurementMeta(selectedTicket).label }}</GBadge>
            </div>
            <div class="inspector-title">
              <span class="mono">{{ selectedTicket.no }}</span>
              <h2>{{ selectedTicket.title }}</h2>
            </div>
            <div class="inspector-metric">
              <span>执行令牌</span>
              <strong class="mono">{{ formatTokens(selectedTicket.execTokenTotal) }}</strong>
              <small>{{ sourceLabel(selectedTicket.execTokenSource) }}</small>
            </div>
            <dl class="inspector-list">
              <div><dt>当前阶段</dt><dd><GBadge :tone="stageTone(selectedTicket.stage)">{{ stageLabels[selectedTicket.stage] }}</GBadge></dd></div>
              <div><dt>目标分支</dt><dd class="mono">{{ selectedTicket.targetRef }}</dd></div>
              <div><dt>树锚点</dt><dd class="mono">{{ selectedTicket.treeHash ?? '未建立' }}</dd></div>
              <div><dt>基线提交</dt><dd class="mono">{{ selectedTicket.baseCommit ?? '未建立' }}</dd></div>
            </dl>
            <div v-if="measurementState(selectedTicket) === 'anomaly'" class="inspector-alert inspector-alert--danger">
              比当前筛选的异常阈值高 {{ formatTokens((selectedTicket.execTokenTotal ?? 0) - anomalyThreshold) }} 令牌。
            </div>
            <div v-else-if="measurementState(selectedTicket) === 'missing'" class="inspector-alert">
              该工单尚无执行用量回写，建议在交付前补齐来源与计量。
            </div>
            <GButton variant="secondary" block class="inspector-action" @click="openSelectedTicket">
              打开工单工作台
            </GButton>
          </section>
          <section v-else class="inspector-empty">
            <GIcon name="chart" :size="21" aria-hidden="true" />
            <strong>选择一张工单查看上下文</strong>
            <p>表格的详情操作会展示来源、锚点和花费风险。</p>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cost-decision {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 24px clamp(20px, 3vw, 42px) 32px;
  overflow: auto;
  color: var(--text);
}

.cost-actions {
  display: flex;
  justify-content: flex-end;
}

.cost-actions :deep(.g-btn) {
  flex: none;
}

.sr-notice {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.page-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  min-height: 280px;
  margin-top: 24px;
  padding: 28px;
  place-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  color: var(--text-muted);
  background: var(--panel);
  text-align: center;
}

.page-state strong {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
}

.page-state p {
  margin: 0;
  font-size: 12px;
}

.loading-line {
  position: relative;
  display: block;
  height: 12px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--skeleton-base);
}

.loading-line::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, var(--skeleton-shine), transparent);
  animation: cost-skeleton-shimmer 1.4s ease-in-out infinite;
}

@keyframes cost-skeleton-shimmer {
  100% { transform: translateX(100%); }
}

@media (prefers-reduced-motion: reduce) {
  .loading-line::after {
    animation: none;
  }
}

.loading-line--wide {
  width: min(360px, 60vw);
  height: 48px;
}

.loading-line--short {
  width: min(220px, 42vw);
}

.cost-summary {
  display: grid;
  grid-template-columns: minmax(250px, 0.9fr) minmax(0, 1.8fr);
  gap: 30px;
  margin-top: 24px;
  padding: 23px 0 20px;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.cost-summary__lead {
  min-width: 0;
  padding-left: 17px;
  border-left: 3px solid var(--accent);
}

.summary-label,
.cost-summary__lead > p,
.summary-stat > span,
.summary-stat small,
.cost-summary__context {
  color: var(--text-muted);
  font-size: 11px;
}

.summary-label {
  font-weight: 700;
}

.cost-summary__lead > strong {
  display: block;
  margin-top: 5px;
  color: var(--ink);
  font-size: clamp(34px, 4vw, 50px);
  font-weight: 820;
  letter-spacing: -0.07em;
  line-height: 1;
}

.cost-summary__lead > p {
  margin: 9px 0 0;
  color: var(--text-secondary);
}

.cost-summary__context {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 15px;
  font-size: 10px;
}

.cost-summary__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: stretch;
}

.summary-stat {
  display: grid;
  align-content: start;
  gap: 4px;
  min-width: 0;
  padding: 3px 18px;
  border-left: 1px solid var(--border);
}

.summary-stat > span {
  font-weight: 700;
}

.summary-stat > strong {
  color: var(--ink);
  font-size: 21px;
  letter-spacing: -0.05em;
  line-height: 1.1;
}

.summary-stat small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-stat--highest button {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 0;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.summary-stat--highest button:hover strong,
.summary-stat--highest button:hover span {
  color: var(--accent-hover);
}

.summary-stat--highest button strong {
  color: var(--accent-hover);
  font-size: 11px;
}

.summary-stat--highest button span {
  overflow: hidden;
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-stat--highest button b {
  color: var(--ink);
  font-size: 14px;
}

.cost-alerts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 13px;
}

.cost-alert {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 35px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--warning);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--panel);
  font-size: 11px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.cost-alert:hover {
  background: var(--hover);
  border-color: var(--border-strong);
}

.cost-alert > span:last-child {
  color: var(--text-muted);
  font-weight: 700;
}

.cost-alert strong {
  color: var(--warning);
  font-family: var(--font-mono);
}

.cost-alert--danger {
  border-left-color: var(--danger);
}

.cost-alert--danger strong {
  color: var(--danger);
}

.filter-toolbar {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 16px;
  align-items: end;
  margin-top: 21px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--border);
}

.filter-toolbar__title {
  display: grid;
  align-content: center;
  gap: 3px;
  min-width: 70px;
  padding-bottom: 8px;
}

.filter-toolbar__title strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.filter-toolbar__title span {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.filter-controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(125px, 0.8fr)) minmax(190px, 1.25fr);
  gap: 10px;
  min-width: 0;
}

.filter-controls :deep(.filter-field) {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.filter-controls :deep(.g-field__label) {
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
}

.filter-controls :deep(.g-select),
.filter-controls :deep(.g-input) {
  min-height: 37px;
  border-radius: var(--radius-sm);
}

.cost-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 300px);
  gap: 18px;
  align-items: start;
  margin-top: 20px;
}

.cost-table-section,
.inspector-panel,
.inspector-empty {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 58px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.section-head > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.section-head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.section-head span:not(.record-count) {
  color: var(--text-muted);
  font-size: 10px;
}

.record-count {
  flex: none;
  color: var(--text-muted);
  font-size: 10px;
}

.table-wrap {
  overflow: auto;
}

.cost-table {
  width: 100%;
  min-width: 650px;
  border-collapse: collapse;
  font-size: 12px;
}

.cost-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.04em;
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
  padding: 13px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.cost-table tbody tr {
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.cost-table tbody tr:hover,
.cost-table tbody tr:focus-visible {
  background: var(--hover);
}

.cost-table tbody tr.selected {
  background: var(--accent-soft);
  box-shadow: inset 3px 0 0 var(--accent);
}

.cost-table tbody tr:last-child td {
  border-bottom: 0;
}

.cost-table__ticket {
  display: grid;
  gap: 3px;
  min-width: 160px;
}

.cost-table__no {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.cost-table__title,
.cost-table__project {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cost-table__title {
  max-width: 280px;
  color: var(--text);
  font-size: 12px;
}

.cost-table__project {
  color: var(--text-muted);
  font-size: 10px;
}

.cost-table__tokens {
  display: block;
  color: var(--ink);
  font-size: 12px;
}

.token-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  min-width: 0;
}

.source-cell {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cost-table td :deep(.g-badge) {
  flex: none;
  margin: 0;
  padding: 2px 6px;
  font-size: 9px;
}

.cost-table__date {
  color: var(--text-muted);
  font-size: 10px;
  white-space: nowrap;
}

.cost-table td :deep(.g-btn) {
  border-radius: var(--radius-sm);
}

.records-empty,
.inspector-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-height: 300px;
  padding: 24px;
  place-content: center;
  color: var(--text-muted);
  text-align: center;
}

.records-empty :deep(svg),
.inspector-empty :deep(svg) {
  color: var(--accent-hover);
}

.records-empty strong,
.inspector-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.records-empty p,
.inspector-empty p {
  margin: 0 0 3px;
  font-size: 11px;
  line-height: 1.5;
}

.cost-inspector {
  position: sticky;
  top: 18px;
}

.inspector-panel {
  padding: 17px;
}

.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
}

.inspector-title {
  margin-top: 18px;
}

.inspector-title > span {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 800;
}

.inspector-title h2 {
  margin: 5px 0 0;
  color: var(--ink);
  font-size: 17px;
  font-weight: 760;
  letter-spacing: -0.02em;
  line-height: 1.35;
}

.inspector-metric {
  display: grid;
  gap: 4px;
  margin-top: 17px;
  padding: 14px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.inspector-metric span,
.inspector-metric small {
  color: var(--text-muted);
  font-size: 10px;
}

.inspector-metric strong {
  color: var(--ink);
  font-size: 27px;
  letter-spacing: -0.055em;
}

.inspector-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 15px 12px;
  padding: 0;
  margin: 17px 0 0;
}

.inspector-list > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.inspector-list dt {
  color: var(--text-muted);
  font-size: 10px;
}

.inspector-list dd {
  min-width: 0;
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
  margin-top: 17px;
  padding: 9px 10px;
  border-left: 3px solid var(--warning);
  border-radius: var(--radius-sm);
  color: var(--warning);
  background: var(--warning-soft);
  font-size: 11px;
  line-height: 1.5;
}

.inspector-alert--danger {
  border-left-color: var(--danger);
  color: var(--danger);
  background: var(--danger-soft);
}

.inspector-action {
  margin-top: 18px;
}

@media (max-width: 1160px) {
  .cost-summary {
    grid-template-columns: 1fr;
    gap: 21px;
  }

  .cost-summary__stats {
    border-top: 1px solid var(--border);
    padding-top: 18px;
  }

  .filter-toolbar {
    grid-template-columns: 1fr auto;
  }

  .filter-toolbar__title {
    padding-bottom: 0;
  }

  .filter-controls {
    grid-column: 1 / -1;
    grid-row: 2;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .filter-controls__search {
    grid-column: 1 / -1;
  }
}

@media (max-width: 1040px) {
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

  .cost-actions :deep(.g-btn) {
    width: 100%;
  }

  .cost-summary {
    margin-top: 18px;
    padding-top: 20px;
  }

  .cost-summary__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 17px 0;
  }

  .summary-stat:nth-child(3) {
    border-left: 0;
  }

  .cost-alerts {
    display: grid;
  }

  .cost-alert {
    width: 100%;
  }

  .filter-toolbar {
    grid-template-columns: 1fr auto;
    gap: 12px;
  }

  .filter-controls {
    grid-template-columns: 1fr;
  }

  .filter-controls__search {
    grid-column: auto;
  }

  .filter-toolbar > :last-child {
    align-self: end;
  }

  .section-head {
    padding-inline: 13px;
  }

  .section-head span:not(.record-count) {
    display: none;
  }

  .cost-table {
    min-width: 620px;
  }

  .cost-table th,
  .cost-table td {
    padding-inline: 10px;
  }

  .cost-table__project {
    display: none;
  }

  .inspector-panel {
    padding: 15px;
  }
}
</style>
