<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { GAvatar, GBadge, GButton, GIcon, GInput, GModal, GSelect } from '@/components/ui';
import { mockAgents } from '@/mocks/prototypeData';
import type { AgentCli, AgentConfig } from '@/types/agentConfig';

type TestStatus = 'idle' | 'testing' | 'success' | 'error';

interface AgentForm {
  name: string;
  cli: AgentCli;
  providerId: string;
  model: string;
  systemPrompt: string;
  extraFlags: string;
  description: string;
}

interface RiskSignal {
  label: string;
  detail: string;
  tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
}

const router = useRouter();
const agents = ref<AgentConfig[]>(
  mockAgents.map((agent) => ({
    ...agent,
    extraFlags: agent.extraFlags ? [...agent.extraFlags] : null,
  })),
);
const selectedId = ref(agents.value[0]?.id ?? '');
const defaultConfigId = ref(agents.value[0]?.id ?? '');
const showForm = ref(false);
const editingId = ref<string | null>(null);
const formSubmitted = ref(false);
const formNotice = ref('');
const actionNotice = ref('');
const testStatus = ref<TestStatus>('idle');
let testTimer: ReturnType<typeof setTimeout> | null = null;

const emptyForm = (): AgentForm => ({
  name: '',
  cli: 'CLAUDE',
  providerId: 'newapi',
  model: '',
  systemPrompt: '',
  extraFlags: '',
  description: '',
});

const form = ref<AgentForm>(emptyForm());

const activeAgent = computed<AgentConfig | null>(
  () => agents.value.find((agent) => agent.id === selectedId.value) ?? agents.value[0] ?? null,
);

const defaultAgent = computed<AgentConfig | null>(
  () => agents.value.find((agent) => agent.id === defaultConfigId.value) ?? null,
);

const formErrors = computed(() => {
  const errors: Partial<Record<keyof AgentForm, string>> = {};
  const name = form.value.name.trim();
  const providerId = form.value.providerId.trim();
  const model = form.value.model.trim();
  const flags = parseFlags(form.value.extraFlags);
  const duplicate = agents.value.some(
    (agent) => agent.name.trim().toLowerCase() === name.toLowerCase() && agent.id !== editingId.value,
  );

  if (name.length < 2) errors.name = '名称至少需要 2 个字符。';
  else if (duplicate) errors.name = '已有同名配置，请换一个名称。';
  if (!providerId) errors.providerId = '请填写 Provider ID。';
  if (!model) errors.model = '请填写实际调用的模型。';
  if (new Set(flags).size !== flags.length) errors.extraFlags = '额外 flags 中包含重复项。';
  return errors;
});

const formValid = computed(() => Object.keys(formErrors.value).length === 0);

const riskSignals = computed<RiskSignal[]>(() => {
  const agent = activeAgent.value;
  if (!agent) return [];
  const flags = agent.extraFlags ?? [];
  const allowsEdits = flags.some((flag) => /acceptedits|permission-mode/i.test(flag));
  const hasPrompt = Boolean(agent.systemPrompt?.trim());

  return [
    {
      label: '文件修改权限',
      detail: allowsEdits
        ? '当前 flags 允许或请求更宽的编辑权限。会话仍需经过审核和发布闸门。'
        : '未发现自动文件编辑相关 flags，修改行为由会话运行时单独确认。',
      tone: allowsEdits ? 'warning' : 'success',
    },
    {
      label: '系统提示注入',
      detail: hasPrompt ? '会在创建会话时附加到 Agent 上下文，直接影响执行边界与工具使用方式。' : '未设置额外系统提示，会话只使用系统默认上下文。',
      tone: hasPrompt ? 'accent' : 'neutral',
    },
    {
      label: '执行隔离',
      detail:
        agent.cli === 'OPENCODE'
          ? 'OpenCode 会话按独立 clone 运行，避免直接污染项目工作区。'
          : 'Claude 会话按当前工单上下文运行，目标引用和闸门工具会一并注入。',
      tone: 'success',
    },
  ];
});

function parseFlags(value: string) {
  return value
    .split(/[,\n]/)
    .map((flag) => flag.trim())
    .filter(Boolean);
}

function makeConfigId(name: string) {
  const base =
    name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 42) || 'agent';
  let candidate = base;
  let index = 2;
  while (agents.value.some((agent) => agent.id === candidate)) {
    candidate = base + '-' + index;
    index += 1;
  }
  return candidate;
}

function chooseAgent(id: string) {
  selectedId.value = id;
  testStatus.value = 'idle';
}

function isDefault(agent: AgentConfig) {
  return agent.id === defaultConfigId.value;
}

function openCreate() {
  editingId.value = null;
  form.value = emptyForm();
  formSubmitted.value = false;
  formNotice.value = '';
  showForm.value = true;
}

function openEdit(agent: AgentConfig) {
  editingId.value = agent.id;
  form.value = {
    name: agent.name,
    cli: agent.cli,
    providerId: agent.providerId,
    model: agent.model,
    systemPrompt: agent.systemPrompt ?? '',
    extraFlags: (agent.extraFlags ?? []).join(', '),
    description: agent.description ?? '',
  };
  formSubmitted.value = false;
  formNotice.value = '';
  showForm.value = true;
}

function closeForm() {
  showForm.value = false;
  formNotice.value = '';
}

function saveForm() {
  formSubmitted.value = true;
  if (!formValid.value) {
    formNotice.value = '请先处理表单中的校验项。';
    return;
  }

  const savedConfig: Omit<AgentConfig, 'id'> = {
    name: form.value.name.trim(),
    cli: form.value.cli,
    providerId: form.value.providerId.trim(),
    model: form.value.model.trim(),
    systemPrompt: form.value.systemPrompt.trim() || null,
    extraFlags: parseFlags(form.value.extraFlags),
    description: form.value.description.trim() || null,
  };

  if (editingId.value) {
    const index = agents.value.findIndex((agent) => agent.id === editingId.value);
    if (index >= 0) {
      const existing = agents.value[index]!;
      agents.value[index] = { ...existing, ...savedConfig };
      selectedId.value = existing.id;
      actionNotice.value = '已保存 ' + savedConfig.name + ' 的执行策略。';
    }
  } else {
    const id = makeConfigId(savedConfig.name);
    agents.value.push({ id, ...savedConfig });
    selectedId.value = id;
    actionNotice.value = '已新建 ' + savedConfig.name + '，可以继续测试连接或设为默认。';
  }

  testStatus.value = 'idle';
  closeForm();
}

function setDefault(agent: AgentConfig) {
  defaultConfigId.value = agent.id;
  actionNotice.value = agent.name + ' 已设为默认执行配置。未指定 Agent 的新会话会使用它。';
}

function copyAgent(agent: AgentConfig) {
  const name = agent.name + ' 副本';
  const id = makeConfigId(name);
  agents.value.push({
    ...agent,
    id,
    name,
    extraFlags: agent.extraFlags ? [...agent.extraFlags] : null,
  });
  selectedId.value = id;
  testStatus.value = 'idle';
  actionNotice.value = '已复制 ' + agent.name + '。请检查副本的模型、提示词与 flags。';
}

function testConnection() {
  const agent = activeAgent.value;
  if (!agent || testStatus.value === 'testing') return;
  if (!agent.providerId.trim() || !agent.model.trim()) {
    testStatus.value = 'error';
    actionNotice.value = '缺少 Provider 或模型，无法执行本地连接校验。';
    return;
  }

  if (testTimer) clearTimeout(testTimer);
  testStatus.value = 'testing';
  actionNotice.value = '正在校验 ' + agent.providerId + ' 到 ' + agent.model + ' 的本地配置。';
  testTimer = setTimeout(() => {
    testStatus.value = 'success';
    actionNotice.value = agent.name + ' 的本地配置校验通过。CLI、Provider 与模型字段可以创建会话。';
    testTimer = null;
  }, 760);
}

function openSessions() {
  router.push({ name: 'session', params: { no: 'T-104' } });
}

onBeforeUnmount(() => {
  if (testTimer) clearTimeout(testTimer);
});
</script>

<template>
  <div class="agent-workbench">
    <header class="agent-header">
      <div class="agent-header__copy">
        <p class="section-kicker">执行配置</p>
        <h2>把会话策略说清楚。</h2>
        <p>选择一个配置，检查它将如何驱动 CLI、模型、上下文和权限边界。</p>
      </div>
      <div class="agent-header__actions">
        <span class="agent-header__summary">{{ agents.length }} 个配置</span>
        <GButton variant="primary" @click="openCreate">
          <GIcon name="plus" :size="15" />
          新建配置
        </GButton>
      </div>
    </header>

    <p v-if="actionNotice" class="action-notice" role="status">{{ actionNotice }}</p>

    <div class="agent-grid">
      <aside class="catalog-panel" aria-labelledby="catalog-heading">
        <div class="panel-head">
          <div>
            <p class="section-label">配置库</p>
            <h3 id="catalog-heading">可用执行者</h3>
          </div>
          <span class="mono panel-count">{{ agents.length }}</span>
        </div>

        <div v-if="agents.length" class="config-list">
          <button
            v-for="agent in agents"
            :key="agent.id"
            type="button"
            class="config-row"
            :class="{ 'config-row--active': activeAgent?.id === agent.id }"
            :aria-pressed="activeAgent?.id === agent.id"
            @click="chooseAgent(agent.id)"
          >
            <GAvatar :name="agent.name" :tone="agent.cli === 'CLAUDE' ? 'accent' : 'neutral'" />
            <span class="config-row__copy">
              <strong>{{ agent.name }}</strong>
              <small class="mono">{{ agent.providerId }} / {{ agent.model }}</small>
            </span>
            <span class="config-row__meta">
              <GBadge :tone="agent.cli === 'CLAUDE' ? 'accent' : 'neutral'">{{ agent.cli }}</GBadge>
              <em v-if="isDefault(agent)">默认</em>
            </span>
          </button>
        </div>

        <div v-else class="empty-state">
          <GIcon name="bot" :size="18" />
          <strong>还没有执行配置</strong>
          <span>创建一个配置后，才能为新会话选择执行策略。</span>
        </div>

        <div class="catalog-panel__foot">
          <span>未指定 Agent 的会话将使用</span>
          <strong>{{ defaultAgent?.name || '未设置默认配置' }}</strong>
        </div>
      </aside>

      <main v-if="activeAgent" class="config-main" aria-labelledby="config-heading">
        <header class="config-main__head">
          <div class="config-main__identity">
            <GAvatar :name="activeAgent.name" size="md" :tone="activeAgent.cli === 'CLAUDE' ? 'accent' : 'neutral'" />
            <div>
              <p class="section-label">{{ isDefault(activeAgent) ? '默认执行配置' : '当前选择' }}</p>
              <h3 id="config-heading">{{ activeAgent.name }}</h3>
              <span class="mono">{{ activeAgent.id }}</span>
            </div>
          </div>
          <div class="config-main__actions">
            <GButton size="sm" variant="secondary" @click="openEdit(activeAgent)">编辑</GButton>
            <GButton size="sm" variant="ghost" @click="copyAgent(activeAgent)">复制</GButton>
          </div>
        </header>

        <section class="connection-strip" aria-label="会话连接策略">
          <div>
            <span>CLI</span>
            <strong>{{ activeAgent.cli }}</strong>
          </div>
          <div>
            <span>Provider</span>
            <strong class="mono">{{ activeAgent.providerId }}</strong>
          </div>
          <div>
            <span>Model</span>
            <strong class="mono">{{ activeAgent.model }}</strong>
          </div>
          <div class="connection-strip__state">
            <span>本地校验</span>
            <strong :class="'test-state--' + testStatus">
              {{ testStatus === 'testing' ? '校验中' : testStatus === 'success' ? '已通过' : testStatus === 'error' ? '不可用' : '未运行' }}
            </strong>
          </div>
        </section>

        <section class="strategy-section">
          <div class="strategy-section__head">
            <div>
              <p class="section-label">会话策略</p>
              <h3>这些字段会在创建时生效</h3>
            </div>
            <GBadge :tone="activeAgent.cli === 'CLAUDE' ? 'accent' : 'neutral'">{{ activeAgent.cli }}</GBadge>
          </div>

          <dl class="strategy-facts">
            <div>
              <dt>执行 Adapter</dt>
              <dd>{{ activeAgent.cli === 'CLAUDE' ? 'Claude Headless Adapter' : 'OpenCode Serve Adapter' }}</dd>
            </div>
            <div>
              <dt>模型提供方</dt>
              <dd class="mono">{{ activeAgent.providerId }}</dd>
            </div>
            <div>
              <dt>调用模型</dt>
              <dd class="mono">{{ activeAgent.model }}</dd>
            </div>
            <div>
              <dt>默认选择</dt>
              <dd>{{ isDefault(activeAgent) ? '未指定 Agent 的会话会自动使用' : '仅在工单显式选择时使用' }}</dd>
            </div>
          </dl>
        </section>

        <section class="strategy-section strategy-section--prompt">
          <div class="strategy-section__head">
            <div>
              <p class="section-label">上下文与命令</p>
              <h3>提示词和 flags 会传给执行侧</h3>
            </div>
          </div>

          <div class="prompt-block">
            <span>系统提示</span>
            <pre>{{ activeAgent.systemPrompt || '未设置额外系统提示。' }}</pre>
          </div>
          <div class="flags-block">
            <span>额外 flags</span>
            <p v-if="activeAgent.extraFlags?.length">
              <b v-for="flag in activeAgent.extraFlags" :key="flag" class="mono">{{ flag }}</b>
            </p>
            <p v-else>未设置</p>
          </div>
        </section>

        <section class="session-path" aria-label="会话应用路径">
          <div>
            <span>创建会话</span>
            <strong>绑定 {{ activeAgent.name }}</strong>
            <small>写入 CLI、Provider 和模型选择。</small>
          </div>
          <div>
            <span>装载上下文</span>
            <strong>注入工单与系统提示</strong>
            <small>目标引用、clone 与提示词进入执行环境。</small>
          </div>
          <div>
            <span>调用工具</span>
            <strong>经过本地闸门</strong>
            <small>配置不能跳过审核或发布决定。</small>
          </div>
        </section>

        <p v-if="activeAgent.description" class="config-description">{{ activeAgent.description }}</p>
      </main>

      <aside v-if="activeAgent" class="risk-panel" aria-labelledby="risk-heading">
        <div class="panel-head">
          <div>
            <p class="section-label">执行影响</p>
            <h3 id="risk-heading">风险与保障</h3>
          </div>
          <GIcon name="shield" :size="18" />
        </div>

        <div class="risk-list">
          <article v-for="signal in riskSignals" :key="signal.label" class="risk-item" :class="'risk-item--' + signal.tone">
            <span class="risk-item__state">{{ signal.tone === 'warning' ? '注意' : signal.tone === 'success' ? '受控' : '已配置' }}</span>
            <strong>{{ signal.label }}</strong>
            <p>{{ signal.detail }}</p>
          </article>
        </div>

        <section class="default-control">
          <span>默认策略</span>
          <strong>{{ isDefault(activeAgent) ? '当前配置已默认' : '当前配置不是默认' }}</strong>
          <p>{{ isDefault(activeAgent) ? '工单未指定 Agent 时会使用这一配置。' : '将它设为默认不会修改已经创建的会话。' }}</p>
          <GButton size="sm" variant="secondary" :disabled="isDefault(activeAgent)" @click="setDefault(activeAgent)">
            {{ isDefault(activeAgent) ? '已设为默认' : '设为默认' }}
          </GButton>
        </section>

        <section class="test-control">
          <span>连接校验</span>
          <p>本地校验会检查 CLI、Provider 与模型字段是否完整，不会发起外部请求。</p>
          <GButton size="sm" variant="primary" :loading="testStatus === 'testing'" @click="testConnection">
            {{ testStatus === 'success' ? '再次校验' : '测试连接' }}
          </GButton>
        </section>

        <GButton size="sm" variant="ghost" block @click="openSessions">
          <GIcon name="chat" :size="14" />
          查看会话
        </GButton>
      </aside>
    </div>

    <GModal :show="showForm" :title="editingId ? '编辑执行配置' : '新建执行配置'" width="620px" @close="closeForm">
      <form class="config-form" @submit.prevent="saveForm">
        <p class="config-form__intro">保存后会更新本地配置工作台，不会改动已经启动的会话。</p>

        <div class="form-grid">
          <label class="field">
            <span>配置名称</span>
            <GInput v-model="form.name" placeholder="例如：Claude 审核配置" />
            <small>用于工单和会话里的选择器。</small>
            <em v-if="formSubmitted && formErrors.name">{{ formErrors.name }}</em>
          </label>
          <label class="field">
            <span>执行 CLI</span>
            <GSelect
              v-model="form.cli"
              :options="[
                { label: 'CLAUDE', value: 'CLAUDE' },
                { label: 'OPENCODE', value: 'OPENCODE' },
              ]"
            />
            <small>决定会话使用的 Adapter。</small>
          </label>
          <label class="field">
            <span>Provider ID</span>
            <GInput v-model="form.providerId" placeholder="例如：newapi" />
            <small>会话调用模型时使用的提供方。</small>
            <em v-if="formSubmitted && formErrors.providerId">{{ formErrors.providerId }}</em>
          </label>
          <label class="field">
            <span>模型</span>
            <GInput v-model="form.model" placeholder="例如：claude-3-5-sonnet-20241022" />
            <small>需与 Provider 可用模型保持一致。</small>
            <em v-if="formSubmitted && formErrors.model">{{ formErrors.model }}</em>
          </label>
        </div>

        <label class="field">
          <span>系统提示</span>
          <GInput v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="会在创建会话时注入的额外上下文。" />
          <small>可选。它会补充系统默认的工单与闸门上下文。</small>
        </label>

        <label class="field">
          <span>额外 flags</span>
          <GInput v-model="form.extraFlags" placeholder="用逗号或换行分隔，例如：--permission-mode, acceptEdits" />
          <small>会原样传给 CLI。重复项会被拦截。</small>
          <em v-if="formSubmitted && formErrors.extraFlags">{{ formErrors.extraFlags }}</em>
        </label>

        <label class="field">
          <span>描述</span>
          <GInput v-model="form.description" type="textarea" :rows="2" placeholder="说明适用场景和使用边界。" />
        </label>

        <p v-if="formNotice" class="form-notice" role="alert">{{ formNotice }}</p>
      </form>
      <template #footer>
        <GButton variant="ghost" @click="closeForm">取消</GButton>
        <GButton variant="primary" @click="saveForm">保存配置</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.agent-workbench {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  width: min(100%, 1520px);
  margin: 0 auto;
  padding: clamp(20px, 2.7vw, 34px) clamp(18px, 3vw, 46px) 30px;
  overflow: auto;
}

.agent-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.agent-header__copy {
  min-width: 0;
}

.section-kicker,
.section-label {
  margin: 0 0 7px;
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.agent-header h2,
.panel-head h3,
.config-main h3,
.strategy-section h3 {
  margin: 0;
  color: var(--text);
  font-weight: 780;
  letter-spacing: -0.035em;
}

.agent-header h2 {
  font-size: clamp(25px, 3vw, 35px);
}

.agent-header__copy > p:last-child {
  max-width: 610px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.agent-header__actions {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}

.agent-header__summary,
.panel-count {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.action-notice {
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid rgba(176, 75, 28, 0.24);
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
}

.agent-grid {
  display: grid;
  grid-template-columns: minmax(245px, 0.8fr) minmax(440px, 1.55fr) minmax(280px, 0.82fr);
  gap: 14px;
  align-items: start;
}

.catalog-panel,
.config-main,
.risk-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.catalog-panel {
  position: sticky;
  top: 12px;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 17px 16px 14px;
}

.panel-head h3,
.config-main h3,
.strategy-section h3 {
  font-size: 17px;
}

.config-list {
  display: grid;
  border-top: 1px solid var(--border);
}

.config-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 9px;
  align-items: center;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease, box-shadow 0.16s ease;
}

.config-row:last-child {
  border-bottom: 0;
}

.config-row:hover {
  background: var(--hover);
}

.config-row--active {
  background: linear-gradient(90deg, var(--accent-soft), transparent 88%);
  box-shadow: inset 3px 0 0 var(--accent);
}

.config-row__copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.config-row__copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-row__copy small {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-row__meta {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.config-row__meta :deep(.g-badge) {
  font-size: 9px;
}

.config-row__meta em {
  color: var(--accent-hover);
  font-size: 9px;
  font-style: normal;
  font-weight: 800;
}

.catalog-panel__foot {
  display: grid;
  gap: 3px;
  padding: 12px 15px;
  border-top: 1px solid var(--border);
  color: var(--text-faint);
  background: var(--panel-2);
  font-size: 10px;
}

.catalog-panel__foot strong {
  color: var(--text-secondary);
  font-size: 11px;
}

.config-main {
  overflow: hidden;
}

.config-main__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 15px;
  padding: 19px 20px 16px;
  border-bottom: 1px solid var(--border);
}

.config-main__identity {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.config-main__identity > div {
  min-width: 0;
}

.config-main__identity .section-label {
  margin-bottom: 4px;
}

.config-main__identity h3 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-main__identity .mono {
  display: block;
  margin-top: 3px;
  color: var(--text-faint);
  font-size: 10px;
}

.config-main__actions {
  display: flex;
  gap: 4px;
  flex: none;
}

.connection-strip {
  display: grid;
  grid-template-columns: 0.7fr 0.9fr minmax(0, 1.55fr) 0.65fr;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}

.connection-strip > div {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 11px 13px;
  border-right: 1px solid var(--border);
}

.connection-strip > div:last-child {
  border-right: 0;
}

.connection-strip span,
.prompt-block > span,
.flags-block > span,
.default-control > span,
.test-control > span {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 750;
}

.connection-strip strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connection-strip__state strong {
  color: var(--text-muted);
}

.connection-strip__state .test-state--testing {
  color: var(--accent-hover);
}

.connection-strip__state .test-state--success {
  color: var(--success);
}

.connection-strip__state .test-state--error {
  color: var(--danger);
}

.strategy-section {
  padding: 18px 20px;
  border-bottom: 1px solid var(--border);
}

.strategy-section--prompt {
  background: rgba(247, 242, 234, 0.48);
}

.strategy-section__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 13px;
}

.strategy-section__head .section-label {
  margin-bottom: 4px;
}

.strategy-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.strategy-facts > div {
  min-width: 0;
  padding: 11px 12px;
  border-bottom: 1px solid var(--border);
}

.strategy-facts > div:nth-child(odd) {
  border-right: 1px solid var(--border);
}

.strategy-facts > div:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.strategy-facts dt {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 750;
}

.strategy-facts dd {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 650;
  line-height: 1.45;
  text-overflow: ellipsis;
}

.prompt-block,
.flags-block {
  display: grid;
  gap: 6px;
}

.prompt-block {
  margin-bottom: 14px;
}

.prompt-block pre {
  max-height: 108px;
  margin: 0;
  padding: 10px 11px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.flags-block p {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
}

.flags-block b {
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--ink-soft);
  background: rgba(26, 54, 82, 0.08);
  font-size: 10px;
  font-weight: 700;
}

.session-path {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 17px 20px;
  gap: 0;
}

.session-path > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding-right: 14px;
}

.session-path > div:not(:first-child) {
  padding-left: 14px;
  border-left: 1px solid var(--border);
}

.session-path span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.session-path strong {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 720;
}

.session-path small {
  color: var(--text-faint);
  font-size: 10px;
  line-height: 1.45;
}

.config-description {
  margin: 0;
  padding: 0 20px 18px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.risk-panel {
  position: sticky;
  top: 12px;
  overflow: hidden;
}

.risk-panel > .panel-head {
  border-bottom: 1px solid var(--border);
}

.risk-panel > .panel-head > svg {
  color: var(--accent);
}

.risk-list {
  display: grid;
}

.risk-item {
  display: grid;
  gap: 4px;
  padding: 13px 15px;
  border-bottom: 1px solid var(--border);
}

.risk-item__state {
  color: var(--text-faint);
  font-size: 9px;
  font-weight: 800;
}

.risk-item strong {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 720;
}

.risk-item p,
.default-control p,
.test-control p {
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.risk-item--warning {
  background: var(--warning-soft);
}

.risk-item--warning .risk-item__state {
  color: var(--warning);
}

.risk-item--success .risk-item__state {
  color: var(--success);
}

.risk-item--accent .risk-item__state {
  color: var(--accent-hover);
}

.default-control,
.test-control {
  display: grid;
  gap: 6px;
  padding: 14px 15px;
  border-bottom: 1px solid var(--border);
}

.default-control strong {
  color: var(--text-secondary);
  font-size: 12px;
}

.default-control :deep(.g-btn),
.test-control :deep(.g-btn) {
  width: fit-content;
  margin-top: 2px;
}

.risk-panel > :deep(.g-btn--ghost) {
  margin: 10px 14px 14px;
  width: calc(100% - 28px);
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-height: 170px;
  place-content: center;
  padding: 22px;
  color: var(--text-muted);
  text-align: center;
}

.empty-state > svg {
  color: var(--accent);
}

.empty-state strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.empty-state span {
  max-width: 220px;
  color: var(--text-faint);
  font-size: 11px;
  line-height: 1.5;
}

.config-form {
  display: grid;
  gap: 16px;
}

.config-form__intro {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 6px;
}

.field > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.field small {
  color: var(--text-faint);
  font-size: 10px;
  line-height: 1.4;
}

.field em,
.form-notice {
  margin: 0;
  color: var(--danger);
  font-size: 11px;
  font-style: normal;
  font-weight: 650;
}

.form-notice {
  padding: 9px 10px;
  border: 1px solid rgba(173, 63, 55, 0.24);
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
}

@media (max-width: 1220px) {
  .agent-grid {
    grid-template-columns: minmax(235px, 0.75fr) minmax(0, 1.45fr);
  }

  .risk-panel {
    position: static;
    grid-column: 1 / -1;
  }

  .risk-list {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .risk-item {
    border-right: 1px solid var(--border);
    border-bottom: 0;
  }

  .risk-item:last-child {
    border-right: 0;
  }

  .risk-panel > .default-control,
  .risk-panel > .test-control {
    display: inline-grid;
    width: 50%;
    vertical-align: top;
    border-bottom: 0;
  }

  .risk-panel > .default-control {
    border-right: 1px solid var(--border);
  }
}

@media (max-width: 850px) {
  .agent-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .agent-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .catalog-panel,
  .risk-panel {
    position: static;
  }

  .risk-panel {
    grid-column: auto;
  }

  .risk-list {
    grid-template-columns: minmax(0, 1fr);
  }

  .risk-item,
  .risk-item:last-child {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .risk-panel > .default-control,
  .risk-panel > .test-control {
    display: grid;
    width: auto;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }
}

@media (max-width: 620px) {
  .agent-workbench {
    padding: 16px 14px 24px;
  }

  .agent-header__actions {
    width: 100%;
    justify-content: space-between;
  }

  .connection-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .connection-strip > div:nth-child(2) {
    border-right: 0;
  }

  .connection-strip > div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--border);
  }

  .config-main__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .config-main__actions {
    width: 100%;
  }

  .config-main__actions :deep(.g-btn) {
    flex: 1;
  }

  .strategy-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .strategy-facts > div,
  .strategy-facts > div:nth-child(odd),
  .strategy-facts > div:nth-last-child(-n + 2) {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .strategy-facts > div:last-child {
    border-bottom: 0;
  }

  .session-path {
    grid-template-columns: minmax(0, 1fr);
    gap: 11px;
  }

  .session-path > div,
  .session-path > div:not(:first-child) {
    padding: 0;
    border-left: 0;
  }

  .session-path > div:not(:first-child) {
    padding-top: 11px;
    border-top: 1px solid var(--border);
  }

  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
