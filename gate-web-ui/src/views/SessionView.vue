<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { GAvatar, GBadge, GButton, GCard, GIcon, GInput } from '@/components/ui';
import { mockAgents, mockMessages, mockSessions, mockTickets, stageLabels } from '@/mocks/prototypeData';
import type { Session } from '@/types/session';
import type { SessionMessage } from '@/types/session-message';
import type { TicketStage } from '@/types/stage';

type SessionAction = 'start' | 'resume' | 'prompt';

const route = useRoute();
const router = useRouter();
const no = String(route.params.no ?? 'T-104');
const ticket = computed(() => mockTickets.find((item) => item.no === no) ?? mockTickets[3]!);
const sessions = ref<Session[]>(
  mockSessions.filter((session) => session.ticketNo === no).map((session) => ({ ...session })),
);
const selectedId = ref(sessions.value[0]?.id ?? '');
const messagesBySession = ref<Record<string, SessionMessage[]>>(
  Object.fromEntries(sessions.value.map((session) => [session.id, [...(mockMessages[session.id] ?? [])]])),
);
const input = ref('');
const sending = ref(false);
const feedback = ref('');

const session = computed(() => sessions.value.find((item) => item.id === selectedId.value) ?? sessions.value[0] ?? null);
const messages = computed(() => (session.value ? messagesBySession.value[session.value.id] ?? [] : []));
const activeSessions = computed(() => sessions.value.filter((item) => item.status === 'ACTIVE'));
const closedSessions = computed(() => sessions.value.filter((item) => item.status !== 'ACTIVE'));
const preferredAgent = computed(() =>
  mockAgents.find((agent) => agent.id === ticket.value.agentConfigId) ?? mockAgents[0]!,
);
const activeAgent = computed(() => (session.value ? agentFor(session.value) : preferredAgent.value));
const totalTokens = computed(() =>
  sessions.value.reduce((sum, item) => sum + (item.cumulativeUsage?.totalTokens ?? 0), 0),
);
const nextStep = computed(() => {
  if (!session.value) {
    return {
      action: 'start' as SessionAction,
      title: '先建立一个执行会话',
      detail: '会话会绑定当前工单、执行器与上下文，后续可直接续接。',
      label: '建立会话',
    };
  }
  if (session.value.status !== 'ACTIVE') {
    return {
      action: 'resume' as SessionAction,
      title: '恢复最近一次会话',
      detail: '保留已有消息和用量记录，继续在同一上下文内协作。',
      label: '继续会话',
    };
  }
  if (!messages.value.length) {
    return {
      action: 'prompt' as SessionAction,
      title: '写下第一条任务指令',
      detail: '先明确目标、约束和验收条件，Agent 才能开始有据可查的工作。',
      label: '填入引导语',
    };
  }
  return {
    action: 'prompt' as SessionAction,
    title: '沿用当前上下文继续处理',
    detail: '补充下一步目标，Agent 会在当前分支和审核锚点上继续工作。',
    label: '继续协作',
  };
});

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
  return mockAgents.find((agent) => agent.id === value.agentConfigId) ?? mockAgents[0]!;
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

function createSession() {
  const agent = preferredAgent.value;
  const id = `sess-local-${no.toLowerCase()}-${sessions.value.length + 1}`;
  const created: Session = {
    id,
    ticketNo: ticket.value.no,
    agentConfigId: agent.id,
    cli: agent.cli,
    status: 'ACTIVE',
    cliSessionId: null,
    clonePath: `local/${ticket.value.no}`,
    allocatedPort: agent.cli === 'OPENCODE' ? 51000 + sessions.value.length : -1,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    cumulativeUsage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 },
  };
  sessions.value.unshift(created);
  messagesBySession.value[id] = [];
  selectedId.value = id;
  return created;
}

function startSession() {
  const created = createSession();
  feedback.value = `已为 ${ticket.value.no} 建立 ${created.id}，可直接写入第一条指令。`;
}

function resumeSession() {
  if (!session.value) {
    startSession();
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

function endSession() {
  if (!session.value || session.value.status !== 'ACTIVE') return;
  session.value.status = 'CLOSED';
  session.value.finishedAt = new Date().toISOString();
  feedback.value = `${session.value.id} 已结束，消息与用量记录已保留。`;
}

function runNextStep() {
  if (nextStep.value.action === 'start') {
    startSession();
    return;
  }
  if (nextStep.value.action === 'resume') {
    resumeSession();
    return;
  }
  applyPrompt(`请继续处理 ${ticket.value.no}，结合当前上下文说明已完成内容、下一步和需要确认的事项。`);
}

function applyPrompt(text: string) {
  if (!session.value) startSession();
  input.value = text;
  feedback.value = '引导语已填入，可修改后发送给 Agent。';
}

function ensureActiveSession() {
  if (!session.value) return createSession();
  if (session.value.status !== 'ACTIVE') {
    session.value.status = 'ACTIVE';
    session.value.finishedAt = null;
    feedback.value = `${session.value.id} 已自动恢复，以便继续发送消息。`;
  }
  return session.value;
}

function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;
  const current = ensureActiveSession();
  const list = messagesBySession.value[current.id] ?? (messagesBySession.value[current.id] = []);
  list.push({
    id: `m${Date.now()}`,
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
  feedback.value = '消息已发送，Agent 正在基于当前工单上下文处理。';
  setTimeout(() => {
    list.push({
      id: `m${Date.now()}`,
      sessionId: current.id,
      role: 'ASSISTANT',
      content: `收到。我会继续围绕 ${ticket.value.no} 处理，并在预审前确认 tree 锚点、目标分支和可验证证据。`,
      toolCalls: [{ name: 'read_file', argumentsJson: 'src/views/ReviewConsoleView.vue', resultJson: null }],
      usage: { promptTokens: 1380, completionTokens: 290, totalTokens: 1670 },
      degraded: false,
      timestamp: new Date().toISOString(),
    });
    sending.value = false;
    feedback.value = 'Agent 已返回新消息。';
  }, 900);
}
</script>

<template>
  <div class="session-workbench">
    <header class="session-head">
      <div>
        <p class="section-kicker">AGENT COLLABORATION</p>
        <h2>工单协作会话</h2>
        <p class="session-head__sub">让执行、审核和上下文在同一个可续接的工作面里流转。</p>
      </div>
      <div class="session-head__actions">
        <GBadge tone="success">{{ activeSessions.length }} 个活跃</GBadge>
        <GButton variant="primary" @click="startSession">
          <GIcon name="plus" :size="15" />
          新建会话
        </GButton>
      </div>
    </header>

    <p class="sr-feedback" role="status" aria-live="polite">{{ feedback }}</p>

    <section class="ticket-context" aria-label="当前工单上下文">
      <div class="ticket-context__copy">
        <div class="ticket-context__tags">
          <span class="mono">{{ ticket.no }}</span>
          <GBadge :tone="stageTone(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge>
        </div>
        <h3>{{ ticket.title }}</h3>
        <div class="ticket-context__meta">
          <span><GIcon name="folder" :size="13" />{{ ticket.project ?? '本地项目' }}</span>
          <span><GIcon name="git-branch" :size="13" />{{ ticket.branch ?? ticket.targetRef }}</span>
          <span><GIcon name="shield" :size="13" />R{{ ticket.reviewRound ?? 0 }}</span>
        </div>
      </div>
      <div class="ticket-context__actions">
        <GButton variant="secondary" size="sm" @click="router.push({ name: 'ticket-detail', params: { no: ticket.no } })">
          查看工单
        </GButton>
        <GButton variant="secondary" size="sm" @click="router.push({ name: 'review', params: { no: ticket.no } })">
          <GIcon name="shield" :size="14" />
          审核台
        </GButton>
      </div>
    </section>

    <div class="collaboration-grid">
      <aside class="session-rail" aria-label="关联会话列表">
        <GCard class="session-list-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">SESSIONS</span>
              <strong>关联会话</strong>
            </div>
            <span class="session-list-card__count">{{ sessions.length }} 个</span>
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
            <p>创建首个会话后，Agent 会获得这张工单的上下文。</p>
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
                <span class="card-heading__eyebrow">WORKING SESSION</span>
                <strong>等待建立协作会话</strong>
              </div>
              <GButton variant="secondary" size="sm" @click="startSession">开始</GButton>
            </template>
          </template>

          <div class="chat-next-step">
            <div class="chat-next-step__icon" aria-hidden="true"><GIcon name="spark" :size="18" /></div>
            <div>
              <span>下一步</span>
              <strong>{{ nextStep.title }}</strong>
              <p>{{ nextStep.detail }}</p>
            </div>
            <GButton variant="primary" size="sm" @click="runNextStep">{{ nextStep.label }}</GButton>
          </div>

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
                      {{ formatTokens(message.usage.totalTokens) }} token
                    </GBadge>
                  </div>
                </article>
              </div>
              <div v-else class="chat-empty">
                <GIcon name="chat" :size="21" aria-hidden="true" />
                <strong>会话已经就绪</strong>
                <p>使用下方快捷引导，或直接写下想让 Agent 处理的事项。</p>
              </div>
            </template>
            <div v-else class="chat-empty">
              <GIcon name="chat" :size="21" aria-hidden="true" />
              <strong>先建立执行会话</strong>
              <p>会话会把当前工单、Agent 配置与消息记录放在同一个协作上下文里。</p>
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
              aria-label="发送给 Agent 的消息"
              placeholder="输入要交给 Agent 的下一步任务，Shift + Enter 可以换行"
              @keydown="(event: KeyboardEvent) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); } }"
            />
            <GButton variant="primary" :loading="sending" :disabled="!input.trim()" @click="send">
              发送
            </GButton>
          </div>
        </GCard>
      </main>

      <aside class="context-rail" aria-label="协作上下文与提示">
        <GCard class="context-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">CONTEXT</span>
              <strong>当前上下文</strong>
            </div>
          </template>
          <dl class="context-list">
            <div>
              <dt>目标分支</dt>
              <dd class="mono">{{ ticket.targetRef }}</dd>
            </div>
            <div>
              <dt>tree 锚点</dt>
              <dd class="mono">{{ ticket.treeHash ?? '未建立' }}</dd>
            </div>
            <div>
              <dt>累计 Token</dt>
              <dd class="mono">{{ formatTokens(totalTokens) }}</dd>
            </div>
            <div>
              <dt>执行器</dt>
              <dd>{{ activeAgent.name }}</dd>
            </div>
          </dl>
        </GCard>

        <GCard class="handoff-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">HANDOFF</span>
              <strong>协作提示</strong>
            </div>
          </template>
          <ol class="handoff-list">
            <li><span>1</span><p>任务完成后，请让 Agent 汇总修改和验证结果。</p></li>
            <li><span>2</span><p>预审前确认 tree 锚点与目标分支是否匹配。</p></li>
            <li><span>3</span><p>遇到决策阻塞时，转到审核台记录人工结论。</p></li>
          </ol>
          <GButton variant="secondary" block class="handoff-card__action" @click="router.push({ name: 'review', params: { no: ticket.no } })">
            打开审核台
          </GButton>
        </GCard>
      </aside>
    </div>

    <p v-if="feedback" class="session-feedback" role="status">{{ feedback }}</p>
  </div>
</template>

<style scoped>
.session-workbench {
  flex: 1;
  min-height: 0;
  width: min(100%, 1520px);
  margin: 0 auto;
  padding: clamp(20px, 3vw, 38px) clamp(18px, 3.4vw, 52px) 30px;
  overflow: auto;
  color: var(--text);
}

.session-head,
.session-head__actions,
.ticket-context,
.ticket-context__tags,
.ticket-context__meta,
.ticket-context__actions,
.chat-card__title,
.chat-card__head-actions,
.session-row__top,
.session-list-card__foot,
.quick-prompts,
.chat-composer {
  display: flex;
  align-items: center;
}

.session-head {
  justify-content: space-between;
  gap: 20px;
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

.session-head h2 {
  margin: 5px 0 0;
  color: var(--ink);
  font-size: clamp(25px, 3.2vw, 35px);
  font-weight: 790;
  letter-spacing: -0.045em;
  line-height: 1.12;
}

.session-head__sub {
  max-width: 620px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.session-head__actions {
  flex: none;
  gap: 9px;
}

.session-head__actions :deep(.g-btn) {
  border-radius: 9px;
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
  background: linear-gradient(100deg, var(--panel), rgba(230, 109, 47, 0.055));
  box-shadow: 0 8px 22px rgba(36, 52, 70, 0.05);
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

.ticket-context__actions {
  flex: none;
  gap: 8px;
}

.ticket-context__actions :deep(.g-btn) {
  border-radius: 8px;
}

.collaboration-grid {
  display: grid;
  grid-template-columns: minmax(250px, 0.78fr) minmax(0, 1.6fr) minmax(245px, 0.72fr);
  gap: 17px;
  align-items: stretch;
  margin-top: 17px;
  min-height: min(690px, calc(100dvh - 235px));
}

.session-rail,
.collaboration-main,
.context-rail {
  min-width: 0;
}

.session-list-card,
.chat-card,
.context-card,
.handoff-card {
  border-radius: 14px;
  border-color: var(--border);
  background: var(--panel);
  box-shadow: 0 9px 24px rgba(36, 52, 70, 0.055);
}

.session-list-card,
.chat-card {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.session-list-card :deep(.g-card__head),
.chat-card :deep(.g-card__head),
.context-card :deep(.g-card__head),
.handoff-card :deep(.g-card__head) {
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
  border-color: rgba(230, 109, 47, 0.32);
  background: var(--accent-soft);
}

.session-row.active :deep(.g-avatar) {
  box-shadow: 0 0 0 3px rgba(230, 109, 47, 0.09);
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

.chat-next-step {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 11px;
  align-items: center;
  margin: 14px 14px 0;
  padding: 11px;
  border: 1px solid rgba(230, 109, 47, 0.24);
  border-radius: 10px;
  background: rgba(230, 109, 47, 0.055);
}

.chat-next-step__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 9px;
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.chat-next-step > div:nth-child(2) {
  min-width: 0;
}

.chat-next-step span,
.chat-next-step strong,
.chat-next-step p {
  display: block;
}

.chat-next-step span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.chat-next-step strong {
  margin-top: 2px;
  color: var(--ink);
  font-size: 12px;
}

.chat-next-step p {
  margin: 2px 0 0;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.45;
}

.chat-next-step :deep(.g-btn) {
  min-width: 88px;
  border-radius: 8px;
}

.chat-messages {
  display: flex;
  flex: 1;
  min-height: 235px;
  overflow: auto;
  margin-top: 12px;
  padding: 18px clamp(14px, 2.5vw, 28px);
  background: linear-gradient(180deg, rgba(247, 242, 234, 0.74), transparent 26%), var(--panel);
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
  border-color: rgba(230, 109, 47, 0.34);
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

.context-rail {
  display: grid;
  align-content: start;
  gap: 17px;
}

.context-card :deep(.g-card__body),
.handoff-card :deep(.g-card__body) {
  padding: 15px;
}

.context-list {
  display: grid;
  gap: 0;
  padding: 0;
  margin: 0;
}

.context-list > div {
  display: grid;
  gap: 4px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
}

.context-list > div:first-child {
  padding-top: 0;
}

.context-list > div:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.context-list dt {
  color: var(--text-muted);
  font-size: 10px;
}

.context-list dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.handoff-list {
  display: grid;
  gap: 11px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.handoff-list li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.handoff-list li > span {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 50%;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
}

.handoff-list p {
  margin: 1px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.55;
}

.handoff-card__action {
  margin-top: 15px;
  border-radius: 8px;
}

.session-feedback {
  margin: 12px 0 0;
  padding: 9px 11px;
  border: 1px solid rgba(230, 109, 47, 0.2);
  border-radius: 9px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
}

@media (max-width: 1190px) {
  .collaboration-grid {
    grid-template-columns: minmax(250px, 0.78fr) minmax(0, 1.6fr);
  }

  .context-rail {
    grid-column: 1 / -1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .session-workbench {
    padding: 18px 14px 24px;
  }

  .session-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .session-head__actions {
    width: 100%;
    justify-content: space-between;
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

  .context-rail {
    grid-column: auto;
  }
}

@media (max-width: 560px) {
  .session-head h2 {
    font-size: 25px;
  }

  .session-head__actions :deep(.g-btn) {
    flex: 1;
  }

  .ticket-context {
    padding: 15px;
  }

  .ticket-context__actions {
    width: 100%;
  }

  .ticket-context__actions :deep(.g-btn) {
    flex: 1;
  }

  .session-list-card :deep(.g-card__head),
  .chat-card :deep(.g-card__head),
  .context-card :deep(.g-card__head),
  .handoff-card :deep(.g-card__head) {
    padding-inline: 14px;
  }

  .chat-next-step {
    grid-template-columns: auto minmax(0, 1fr);
    margin: 12px 12px 0;
  }

  .chat-next-step :deep(.g-btn) {
    grid-column: 1 / -1;
    width: 100%;
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

  .context-rail {
    grid-template-columns: 1fr;
  }
}
</style>
