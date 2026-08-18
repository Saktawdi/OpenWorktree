<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getTicket } from '@/api/tickets';
import {
  abortSession,
  getHistory,
  listTicketSessions,
  normalizeSessionMessage,
  sendMessage,
  sessionEventsPath,
  startSession as apiStartSession,
} from '@/api/sessions';
import { listAgentConfigs } from '@/api/agentConfig';
import { useSSE } from '@/composables/useSSE';
import { GAvatar, GBadge, GButton, GCard, GIcon, GInput, GSkeleton, GTooltip } from '@/components/ui';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { AgentConfig } from '@/types/agentConfig';
import type { Session } from '@/types/session';
import type { SessionMessage } from '@/types/session-message';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

const route = useRoute();
const router = useRouter();
const no = String(route.params.no ?? 'T-104');
const projectId = String(route.params.projectId ?? '');
const ticket = ref<Ticket>({
  no,
  title: '读取工单中...',
  stage: 'PENDING' as const,
  targetRef: '',
  reviewRound: null as number | null,
  treeHash: null as string | null,
  baseCommit: null as string | null,
  execTokenTotal: null,
  execTokenSource: null,
  agentConfigId: null as string | null,
  createdAt: '',
  updatedAt: '',
});
const loading = ref(true);
const loadError = ref('');
const sessions = ref<Session[]>([]);
const selectedId = ref('');
const messagesBySession = ref<Record<string, SessionMessage[]>>({});
const input = ref('');
const sending = ref(false);
const feedback = ref('');
const agentConfigs = ref<AgentConfig[]>([]);
const activeSessionPath = ref('');

const fallbackAgent: AgentConfig = {
  id: 'claude-sonnet-default',
  name: 'Claude Sonnet',
  cli: 'CLAUDE',
  providerId: 'newapi',
  model: 'claude-3-5-sonnet-20241022',
  systemPrompt: null,
  extraFlags: [],
  description: null,
};

const sessionSse = useSSE({
  path: activeSessionPath,
  immediate: false,
  handlers: {
    message: handleSessionEvent,
    usage: handleSessionEvent,
    tool_call: handleSessionEvent,
    done: handleSessionEvent,
    error: handleSessionEvent,
  },
});

function handleSessionEvent(data: string) {
  try {
    const parsed = JSON.parse(data) as { kind?: string; message?: unknown; session_id?: string };
    if (parsed.message) {
      const message = normalizeSessionMessage(parsed.message);
      appendMessage(message);
    }
    if (parsed.kind === 'done' || parsed.kind === 'error') {
      feedback.value = parsed.kind === 'done' ? '会话事件流已结束。' : '会话事件流报告错误。';
    }
  } catch {
    // 非 JSON 事件忽略.
  }
}

function appendMessage(message: SessionMessage) {
  const sid = message.sessionId;
  const list = messagesBySession.value[sid] ?? (messagesBySession.value[sid] = []);
  if (!list.some((item) => item.id === message.id)) {
    list.push(message);
  }
}

function dedupeMessages(list: SessionMessage[]): SessionMessage[] {
  const seen = new Set<string>();
  return list.filter((message) => {
    if (seen.has(message.id)) return false;
    seen.add(message.id);
    return true;
  });
}

async function loadTicket() {
  loading.value = true;
  loadError.value = '';
  try {
    const loaded = await getTicket(no, projectId);
    ticket.value = {
      no: loaded.no,
      title: loaded.title,
      stage: loaded.stage,
      targetRef: loaded.targetRef,
      reviewRound: loaded.reviewRound,
      treeHash: loaded.treeHash,
      baseCommit: loaded.baseCommit,
      execTokenTotal: loaded.execTokenTotal,
      execTokenSource: loaded.execTokenSource,
      agentConfigId: loaded.agentConfigId,
      createdAt: loaded.createdAt,
      updatedAt: loaded.updatedAt,
      ...(loaded.project ? { project: loaded.project } : {}),
      ...(loaded.branch ? { branch: loaded.branch } : {}),
    };
  } catch {
    loadError.value = '无法读取工单数据，请确认工单仍存在且 Gate 后端正在运行。';
    ticket.value.title = '工单数据不可用';
  } finally {
    loading.value = false;
  }
}

async function loadAgentConfigs() {
  try {
    agentConfigs.value = await listAgentConfigs();
  } catch {
    agentConfigs.value = [];
  }
}

async function loadSessions() {
  try {
    sessions.value = await listTicketSessions(no);
    if (sessions.value.length && !sessions.value.some((item) => item.id === selectedId.value)) {
      selectedId.value = sessions.value[0]!.id;
    }
    // 首次设置 selectedId 会触发 watch 加载历史并打开 SSE；此处不重复调用.
  } catch {
    feedback.value = '无法读取会话列表，请确认 Gate 后端正在运行。';
  }
}

async function loadHistory(sid: string) {
  try {
    const history = await getHistory(sid);
    messagesBySession.value = {
      ...messagesBySession.value,
      [sid]: dedupeMessages(history),
    };
  } catch {
    feedback.value = `无法读取会话 ${sid} 的历史消息。`;
  }
}

function openSessionSse(sid: string) {
  sessionSse.close();
  activeSessionPath.value = sessionEventsPath(sid);
  sessionSse.reopen();
}

const session = computed(() => sessions.value.find((item) => item.id === selectedId.value) ?? sessions.value[0] ?? null);
const messages = computed(() => (session.value ? messagesBySession.value[session.value.id] ?? [] : []));
const activeSessions = computed(() => sessions.value.filter((item) => item.status === 'ACTIVE'));
const closedSessions = computed(() => sessions.value.filter((item) => item.status !== 'ACTIVE'));
const preferredAgent = computed(() =>
  agentConfigs.value.find((agent) => agent.id === ticket.value.agentConfigId) ?? agentConfigs.value[0] ?? fallbackAgent,
);
const activeAgent = computed(() => (session.value ? agentFor(session.value) : preferredAgent.value));
const quickPrompts = computed(() => [
  {
    label: '继续当前任务',
    text: `请继续处理 ${ticket.value.no}，并在完成后总结修改、验证结果与下一步建议。`,
  },
  {
    label: '请求进度',
    text: '请汇报当前进度、已完成工作、阻塞项以及需要我确认的决定。',
  },
  {
    label: '准备预审',
    text: '请检查当前变更是否满足预审条件，并列出需要补齐的验证或证据。',
  },
]);

function agentFor(value: Session) {
  return agentConfigs.value.find((agent) => agent.id === value.agentConfigId) ?? fallbackAgent;
}

function stageTone(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function sessionTone(value: Session): 'neutral' | 'success' | 'warning' | 'danger' {
  if (value.status === 'ACTIVE') return 'success';
  if (value.status === 'ABORTED') return 'danger';
  return 'neutral';
}

function sessionLabel(value: Session) {
  if (value.status === 'ACTIVE') return '活跃';
  if (value.status === 'ABORTED') return '已中止';
  return '已结束';
}

function formatTime(iso: string | null | undefined) {
  if (!iso) return '刚刚';
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function formatTokens(value: number | null | undefined) {
  if (value == null) return '未回写';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function lastMessageFor(sessionId: string) {
  const list = messagesBySession.value[sessionId] ?? [];
  return list.length ? list[list.length - 1]!.content : '尚无消息，等待第一条任务指令。';
}

async function createSession(): Promise<Session | null> {
  const agent = preferredAgent.value;
  try {
    const created = await apiStartSession(ticket.value.no, {
      agentConfigId: agent.id,
      ...(input.value.trim() ? { initialPrompt: input.value.trim() } : {}),
    });
    sessions.value.unshift(created);
    messagesBySession.value[created.id] = [];
    selectedId.value = created.id;
    openSessionSse(created.id);
    feedback.value = `已为 ${ticket.value.no} 建立 ${created.id}，可直接写入第一条指令。`;
    return created;
  } catch {
    feedback.value = '创建会话失败，请检查智能体配置与后端日志。';
    return null;
  }
}

async function startSession() {
  await createSession();
}

function resumeSession() {
  if (!session.value) {
    void startSession();
    return;
  }
  if (session.value.status !== 'ACTIVE') {
    session.value.status = 'ACTIVE';
    session.value.finishedAt = null;
    feedback.value = `${session.value.id} 已恢复为活跃会话。`;
  } else {
    feedback.value = `${session.value.id} 已就绪，可以继续协作。`;
  }
  if (!input.value.trim()) {
    input.value = `请继续处理 ${ticket.value.no}，并基于已有上下文给出下一步。`;
  }
}

async function endSession() {
  if (!session.value || session.value.status !== 'ACTIVE') return;
  try {
    await abortSession(session.value.id);
  } catch {
    // 后端可能没有进行中的任务; 本地仍标记结束.
  }
  session.value.status = 'CLOSED';
  session.value.finishedAt = new Date().toISOString();
  feedback.value = `${session.value.id} 已结束，消息与用量记录已保留。`;
}

function applyPrompt(text: string) {
  if (!session.value) {
    void startSession();
  }
  input.value = text;
  feedback.value = '引导语已填入，可修改后发送给智能体。';
}

async function ensureActiveSession(): Promise<Session | null> {
  if (!session.value) return createSession();
  if (session.value.status !== 'ACTIVE') {
    session.value.status = 'ACTIVE';
    session.value.finishedAt = null;
    feedback.value = `${session.value.id} 已自动恢复，以便继续发送消息。`;
  }
  return session.value;
}

async function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;
  const current = await ensureActiveSession();
  if (!current) return;
  const list = messagesBySession.value[current.id] ?? (messagesBySession.value[current.id] = []);
  list.push({
    id: `local-${Date.now()}`,
    sessionId: current.id,
    role: 'USER',
    content: text,
    toolCalls: [],
    usage: null,
    degraded: false,
    timestamp: new Date().toISOString(),
  });
  input.value = '';
  sending.value = true;
  feedback.value = '消息已发送，智能体正在基于当前工单上下文处理。';
  try {
    await sendMessage(current.id, text);
    openSessionSse(current.id);
  } catch {
    feedback.value = '消息发送失败，请检查后端日志。';
  } finally {
    sending.value = false;
  }
}

watch(selectedId, async (id) => {
  if (!id) return;
  await loadHistory(id);
  openSessionSse(id);
});

onMounted(async () => {
  await Promise.all([loadTicket(), loadAgentConfigs()]);
  await loadSessions();
});
onBeforeUnmount(() => sessionSse.close());
</script>

<template>
  <div class="session-workbench">
    <div v-if="loading" class="session-skeleton" role="status" aria-label="正在读取会话数据">
      <div class="session-skeleton__context">
        <GSkeleton variant="text" width="30%" height="14px" />
        <GSkeleton variant="text" width="52%" height="18px" />
        <GSkeleton variant="text" width="44%" />
      </div>
      <div class="session-skeleton__body">
        <GSkeleton variant="block" height="260px" />
      </div>
    </div>
    <p v-else-if="loadError" class="session-data-state session-data-state--error" role="alert">
      {{ loadError }}
      <button type="button" @click="loadTicket">重新读取</button>
    </p>

    <p class="sr-feedback" role="status" aria-live="polite">{{ feedback }}</p>

    <section class="ticket-context" aria-label="当前工单上下文">
      <div class="ticket-context__copy">
        <div class="ticket-context__tags">
          <span class="mono">{{ ticket.no }}</span>
          <GBadge :tone="stageTone(ticket.stage)">{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge>
        </div>
        <h3>{{ ticket.title }}</h3>
        <div class="ticket-context__meta">
          <span><GIcon name="folder" :size="13" />{{ ticket.project ?? '本地项目' }}</span>
          <span><GIcon name="git-branch" :size="13" />{{ ticket.branch ?? ticket.targetRef }}</span>
          <span><GIcon name="shield" :size="13" />R{{ ticket.reviewRound ?? 0 }}</span>
        </div>
      </div>
      <div class="ticket-context__actions" aria-label="工单操作">
        <GTooltip content="查看当前工单" side="bottom">
          <GButton
            class="ticket-context__icon-action"
            variant="secondary"
            size="sm"
            aria-label="查看当前工单"
            @click="router.push({ name: 'ticket-detail', params: { projectId, no: ticket.no } })"
          >
            <GIcon name="external" :size="15" />
          </GButton>
        </GTooltip>
        <GTooltip content="打开审核台" side="bottom">
          <GButton
            class="ticket-context__icon-action"
            variant="secondary"
            size="sm"
            aria-label="打开审核台"
            @click="router.push({ name: 'review', params: { projectId, no: ticket.no } })"
          >
            <GIcon name="shield" :size="15" />
          </GButton>
        </GTooltip>
      </div>
    </section>

    <div class="collaboration-grid">
      <aside class="session-rail" aria-label="关联会话列表">
        <GCard class="session-list-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">会话列表</span>
              <strong>关联会话</strong>
            </div>
            <div class="session-list-card__tools">
              <span class="session-list-card__count">{{ sessions.length }} 个</span>
              <GTooltip content="新建会话" side="top">
                <GButton
                  class="session-list-card__new"
                  variant="primary"
                  size="sm"
                  aria-label="新建会话"
                  @click="startSession"
                >
                  <GIcon name="plus" :size="15" />
                </GButton>
              </GTooltip>
            </div>
          </template>

          <div v-if="sessions.length" class="session-list" aria-label="选择会话">
            <button
              v-for="item in sessions"
              :key="item.id"
              type="button"
              class="session-row"
              :class="{ active: item.id === session?.id }"
              :aria-pressed="item.id === session?.id"
              @click="selectedId = item.id"
            >
              <GAvatar :name="agentFor(item).name" :tone="item.status === 'ACTIVE' ? 'accent' : 'neutral'" />
              <span class="session-row__copy">
                <span class="session-row__top">
                  <strong>{{ agentFor(item).name }}</strong>
                  <GBadge :tone="sessionTone(item)">{{ sessionLabel(item) }}</GBadge>
                </span>
                <span class="session-row__id mono">{{ item.id }}</span>
                <span class="session-row__preview">{{ lastMessageFor(item.id) }}</span>
              </span>
              <span class="session-row__time mono">{{ formatTime(item.finishedAt ?? item.startedAt) }}</span>
            </button>
          </div>
          <div v-else class="session-list-empty">
            <GIcon name="chat" :size="18" aria-hidden="true" />
            <strong>还没有关联会话</strong>
            <p>创建首个会话后，智能体会获得这张工单的上下文。</p>
            <GButton variant="secondary" size="sm" @click="startSession">创建首个会话</GButton>
          </div>

          <template v-if="sessions.length" #foot>
            <div class="session-list-card__foot">
              <span><i class="status-dot status-dot--active" />{{ activeSessions.length }} 活跃</span>
              <span><i class="status-dot" />{{ closedSessions.length }} 已结束</span>
            </div>
          </template>
        </GCard>
      </aside>

      <main class="collaboration-main" aria-label="当前会话消息">
        <GCard class="chat-card">
          <template #head>
            <template v-if="session">
              <div class="chat-card__title">
                <GAvatar :name="activeAgent.name" size="md" tone="accent" />
                <div>
                  <strong>{{ activeAgent.name }}</strong>
                  <span class="mono">{{ session.id }} · {{ activeAgent.cli }}</span>
                </div>
              </div>
              <div class="chat-card__head-actions">
                <GBadge :tone="sessionTone(session)">{{ sessionLabel(session) }}</GBadge>
                <GButton
                  v-if="session.status === 'ACTIVE'"
                  variant="ghost"
                  size="sm"
                  aria-label="结束当前会话"
                  @click="endSession"
                >
                  结束
                </GButton>
                <GButton v-else variant="secondary" size="sm" @click="resumeSession">继续</GButton>
              </div>
            </template>
            <template v-else>
              <div class="card-heading">
                <span class="card-heading__eyebrow">当前会话</span>
                <strong>等待建立协作会话</strong>
              </div>
              <GButton variant="secondary" size="sm" @click="startSession">开始</GButton>
            </template>
          </template>

          <div class="chat-messages" aria-label="会话消息">
            <template v-if="session">
              <div v-if="messages.length" class="message-stack">
                <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
                  <div class="message__bubble">{{ message.content }}</div>
                  <div v-if="message.toolCalls?.length" class="message__tool">
                    <GIcon name="spark" :size="12" />
                    调用了 {{ message.toolCalls.length }} 个工具
                  </div>
                  <div class="message__meta">
                    <span class="mono">{{ formatTime(message.timestamp) }}</span>
                    <GBadge v-if="message.usage" tone="warning">
                      {{ formatTokens(message.usage.totalTokens) }} 令牌
                    </GBadge>
                  </div>
                </article>
              </div>
              <div v-else class="chat-empty">
                <GIcon name="chat" :size="21" aria-hidden="true" />
                <strong>会话已经就绪</strong>
                <p>使用下方快捷引导，或直接写下想让智能体处理的事项。</p>
              </div>
            </template>
            <div v-else class="chat-empty">
              <GIcon name="chat" :size="21" aria-hidden="true" />
              <strong>先建立执行会话</strong>
              <p>会话会把当前工单、智能体配置与消息记录放在同一个协作上下文里。</p>
              <GButton variant="secondary" size="sm" @click="startSession">建立会话</GButton>
            </div>
          </div>

          <div class="quick-prompts" aria-label="快捷引导">
            <span>快捷引导</span>
            <button v-for="prompt in quickPrompts" :key="prompt.label" type="button" @click="applyPrompt(prompt.text)">
              {{ prompt.label }}
            </button>
          </div>

          <div class="chat-composer">
            <GInput
              v-model="input"
              type="textarea"
              :rows="1"
              aria-label="发送给智能体的消息"
              placeholder="输入要交给智能体的下一步任务，使用换行键可以换行"
              @keydown="(event: KeyboardEvent) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); } }"
            />
            <GButton variant="primary" :loading="sending" :disabled="!input.trim()" @click="send">
              发送
            </GButton>
          </div>
        </GCard>
      </main>

    </div>

    <p v-if="feedback" class="session-feedback" role="status">{{ feedback }}</p>
  </div>
</template>

<style scoped>
.session-workbench {
  display: flex;
  flex-direction: column;
  flex: 1;
  height: 100%;
  min-height: 0;
  width: 100%;
  padding: clamp(18px, 2.4vw, 30px) clamp(18px, 3vw, 42px) 24px;
  overflow: hidden;
  color: var(--text);
}

.ticket-context,
.ticket-context__tags,
.ticket-context__meta,
.ticket-context__actions,
.chat-card__title,
.chat-card__head-actions,
.session-row__top,
.session-list-card__foot,
.session-list-card__tools,
.quick-prompts,
.chat-composer {
  display: flex;
  align-items: center;
}

.card-heading__eyebrow {
  margin: 0;
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.sr-feedback {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.ticket-context {
  justify-content: space-between;
  gap: 18px;
  margin-top: 24px;
  padding: 17px 19px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 14px;
  background: var(--panel-2);
  box-shadow: var(--shadow-panel);
}

.ticket-context__actions {
  flex: none;
  gap: 8px;
}

.ticket-context__icon-action {
  width: 32px;
  min-width: 32px;
  padding: 0 !important;
}

.ticket-context__copy {
  min-width: 0;
}

.ticket-context__tags {
  flex-wrap: wrap;
  gap: 7px;
}

.ticket-context__tags > .mono {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.05em;
}

.ticket-context h3 {
  margin: 7px 0 0;
  color: var(--ink);
  font-size: 17px;
  font-weight: 760;
  letter-spacing: -0.02em;
}

.ticket-context__meta {
  flex-wrap: wrap;
  gap: 8px 15px;
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 11px;
}

.ticket-context__meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.collaboration-grid {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(250px, 310px) minmax(0, 1fr);
  gap: 17px;
  align-items: stretch;
  min-height: 0;
  margin-top: 16px;
}

.session-rail,
.collaboration-main {
  display: flex;
  min-width: 0;
  min-height: 0;
}

.session-list-card,
.chat-card {
  border-radius: 14px;
  border-color: var(--border);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.session-list-card,
.chat-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.session-list-card :deep(.g-card__head),
.chat-card :deep(.g-card__head) {
  min-height: 58px;
  padding: 13px 16px;
}

.card-heading {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.card-heading strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.session-list-card__count {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.session-list-card__tools {
  gap: 8px;
}

.session-list-card__new {
  width: 30px;
  min-width: 30px;
  padding: 0 !important;
}

.session-list-card :deep(.g-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 9px;
}

.session-list {
  display: grid;
  align-content: start;
  gap: 5px;
  overflow: auto;
}

.session-skeleton {
  display: grid;
  gap: 16px;
  margin-bottom: 18px;
}

.session-skeleton__context {
  display: grid;
  gap: 10px;
  padding: 16px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.session-skeleton__body {
  min-height: 0;
}

.session-data-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 12px;
}

.session-data-state--error {
  border-color: var(--danger-soft);
  color: var(--danger);
  background: var(--danger-soft);
}

.session-data-state button {
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: inherit;
  font-weight: 750;
  cursor: pointer;
}

.session-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 9px;
  align-items: start;
  width: 100%;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, transform 0.15s ease;
}

.session-row:hover {
  border-color: var(--border);
  background: var(--hover);
}

.session-row.active {
  border-color: var(--accent-border);
  background: var(--accent-soft);
}

.session-row.active :deep(.g-avatar) {
  box-shadow: 0 0 0 3px var(--accent-focus-ring);
}

.session-row__copy {
  display: grid;
  min-width: 0;
}

.session-row__top {
  justify-content: space-between;
  gap: 7px;
}

.session-row__top strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-row__top :deep(.g-badge) {
  flex: none;
  padding: 2px 6px;
  font-size: 9px;
}

.session-row__id {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 10px;
}

.session-row__preview {
  display: -webkit-box;
  margin-top: 5px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.session-row__time {
  margin-top: 2px;
  color: var(--text-faint);
  font-size: 9px;
}

.session-list-empty {
  display: grid;
  flex: 1;
  align-content: center;
  justify-items: start;
  gap: 7px;
  padding: 18px 9px;
}

.session-list-empty :deep(svg) {
  color: var(--accent-hover);
}

.session-list-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.session-list-empty p {
  margin: 0 0 3px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.55;
}

.session-list-empty :deep(.g-btn) {
  border-radius: 8px;
}

.session-list-card :deep(.g-card__foot) {
  padding: 11px 13px;
}

.session-list-card__foot {
  justify-content: space-between;
  width: 100%;
  gap: 10px;
  color: var(--text-muted);
  font-size: 10px;
}

.session-list-card__foot span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--border-strong);
}

.status-dot--active {
  background: var(--success);
}

.chat-card :deep(.g-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 0;
}

.chat-card__title {
  gap: 9px;
  min-width: 0;
}

.chat-card__title > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.chat-card__title strong {
  overflow: hidden;
  color: var(--ink);
  font-size: 13px;
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-card__title span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-card__head-actions {
  flex: none;
  gap: 7px;
}

.chat-card__head-actions :deep(.g-btn) {
  border-radius: 8px;
}

.chat-messages {
  display: flex;
  flex: 1;
  min-height: 235px;
  overflow: auto;
  margin-top: 12px;
  padding: 18px clamp(14px, 2.5vw, 28px);
  background: var(--panel-2);
}

.message-stack {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 15px;
}

.message {
  display: grid;
  max-width: min(82%, 700px);
  gap: 5px;
}

.message.user {
  align-self: flex-end;
}

.message.assistant,
.message.tool {
  align-self: flex-start;
}

.message__bubble {
  padding: 11px 13px;
  border: 1px solid var(--border);
  border-radius: 12px;
  color: var(--text-secondary);
  background: var(--panel-2);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.message.user .message__bubble {
  border-color: var(--accent);
  border-bottom-right-radius: 4px;
  color: var(--accent-contrast);
  background: var(--accent);
}

.message.assistant .message__bubble {
  border-left: 3px solid var(--accent);
  border-bottom-left-radius: 4px;
}

.message.tool .message__bubble {
  color: var(--text-muted);
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 11px;
}

.message__tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--warning);
  font-size: 10px;
}

.message__meta {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
  font-size: 10px;
}

.message.user .message__meta {
  justify-content: flex-end;
}

.message__meta :deep(.g-badge) {
  padding: 2px 6px;
  font-size: 9px;
}

.chat-empty {
  display: grid;
  flex: 1;
  align-content: center;
  justify-items: center;
  gap: 8px;
  min-height: 210px;
  padding: 18px;
  color: var(--text-muted);
  text-align: center;
}

.chat-empty :deep(svg) {
  color: var(--accent-hover);
}

.chat-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.chat-empty p {
  max-width: 360px;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.chat-empty :deep(.g-btn) {
  margin-top: 3px;
  border-radius: 8px;
}

.quick-prompts {
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  background: var(--panel);
}

.quick-prompts > span {
  margin-right: 3px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
}

.quick-prompts button {
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--panel-2);
  font-size: 10px;
  cursor: pointer;
}

.quick-prompts button:hover {
  border-color: var(--accent-border);
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.chat-composer {
  gap: 10px;
  padding: 13px 14px 14px;
  border-top: 1px solid var(--border);
  background: var(--panel);
}

.chat-composer :deep(.g-input) {
  flex: 1;
  min-height: 44px;
  max-height: 116px;
  border-radius: 9px;
}

.chat-composer :deep(.g-btn) {
  align-self: stretch;
  min-width: 68px;
  border-radius: 9px;
}

.session-feedback {
  margin: 12px 0 0;
  padding: 9px 11px;
  border: 1px solid var(--accent-border);
  border-radius: 9px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
}

@media (max-width: 820px) {
  .session-workbench {
    height: auto;
    padding: 18px 14px 24px;
    overflow: auto;
  }

  .ticket-context {
    align-items: flex-start;
    flex-direction: column;
  }

  .collaboration-grid {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .session-list-card {
    min-height: 220px;
    max-height: 310px;
  }

}

@media (max-width: 560px) {
  .ticket-context {
    padding: 15px;
  }

  .session-list-card :deep(.g-card__head),
  .chat-card :deep(.g-card__head) {
    padding-inline: 14px;
  }

  .message {
    max-width: 91%;
  }

  .quick-prompts {
    padding-inline: 12px;
  }

  .chat-composer {
    align-items: flex-end;
    padding-inline: 12px;
  }

  .chat-composer :deep(.g-btn) {
    min-width: 58px;
  }

}
</style>
