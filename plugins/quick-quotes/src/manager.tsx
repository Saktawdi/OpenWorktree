/**
 * 快捷语录管理面板：拖拽排序（dnd-kit）、增删改查、JSON 导入导出、恢复内置。
 * 渲染在设置中心「插件」分区；数据经 quoteStore → 宿主 pluginStore 同步重注册 chips。
 */
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import {
  ArrowsCounterClockwise,
  Brain,
  Bug,
  ChatText,
  Check,
  CheckCircle,
  DotsSixVertical,
  DownloadSimple,
  Eye,
  Lightning,
  LockKey,
  PencilSimple,
  PlugsConnected,
  Plus,
  Rocket,
  ShieldCheck,
  TerminalWindow,
  Trash,
  UploadSimple,
  WarningCircle,
  Wrench,
  X,
} from "@phosphor-icons/react";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  ICON_CHOICES,
  KIND_LABEL,
  WHEN_LABEL,
  mergeBuiltins,
  newQuoteId,
  sanitizeQuotes,
  type QuoteItem,
  type QuoteKind,
  type QuoteWhen,
} from "./quotes";
import { quoteStore } from "./quote-store";

/* 与宿主图标白名单同名的本地映射（插件自带 phosphor，React 走宿主共享实例） */
const ICONS = {
  ChatText,
  LockKey,
  Eye,
  TerminalWindow,
  CheckCircle,
  Wrench,
  PlugsConnected,
  Lightning,
  Brain,
  Bug,
  Rocket,
  ShieldCheck,
} as const;

interface McpToolInfo {
  name: string;
  domain: string;
  description: string;
}

interface Props {
  hasKv: boolean;
  onChange(next: QuoteItem[]): void;
  loadMcpTools(): Promise<{ tools: McpToolInfo[] }>;
}

interface Draft {
  label: string;
  kind: QuoteKind;
  when: QuoteWhen;
  prompt: string;
  mcpTool: string;
  icon: string;
}

const KIND_BADGE: Record<QuoteKind, string> = {
  message: "text-dim border-edge-strong bg-raised",
  presubmit: "text-accent border-accent/30 bg-accent/10",
  findings: "text-warn border-warn/30 bg-warn/10",
  mcp: "text-info border-info/30 bg-info/10",
};

function draftOf(q: QuoteItem): Draft {
  return {
    label: q.label,
    kind: q.kind,
    when: q.when,
    prompt: q.prompt ?? "",
    mcpTool: q.mcpTool ?? "",
    icon: q.icon ?? "",
  };
}

/* ─── 可排序行 ─── */

function SortableRow({
  quote,
  editable,
  onEdit,
  onDelete,
}: {
  quote: QuoteItem;
  editable: boolean;
  onEdit(): void;
  onDelete(): void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: quote.id,
    disabled: !editable,
  });
  const QIcon = quote.icon ? (ICONS as Record<string, typeof ChatText>)[quote.icon] : undefined;
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={`qq-row${isDragging ? " qq-row-dragging" : ""}`}
    >
      <button
        type="button"
        className="qq-handle"
        title="拖拽调整顺序（顺序即对话上方显示顺序）"
        disabled={!editable}
        {...attributes}
        {...listeners}
      >
        <DotsSixVertical size={14} />
      </button>
      {QIcon ? <QIcon size={13} className="text-faint shrink-0" /> : null}
      <span className="text-[12.5px] font-medium text-ink truncate">{quote.label}</span>
      <span className={`chip border shrink-0 ${KIND_BADGE[quote.kind]}`}>{KIND_LABEL[quote.kind]}</span>
      <span className="text-[11px] text-faint whitespace-nowrap hidden sm:inline">{WHEN_LABEL[quote.when]}</span>
      {quote.builtin && <span className="chip border border-violet/30 bg-violet/10 text-violet shrink-0">内置</span>}
      <span className="flex-1" />
      <button type="button" className="icon-btn" title="编辑" disabled={!editable} onClick={onEdit}>
        <PencilSimple size={13} />
      </button>
      <button type="button" className="icon-btn" title="删除" disabled={!editable} onClick={onDelete}>
        <Trash size={13} />
      </button>
    </div>
  );
}

/* ─── 编辑器 ─── */

function QuoteEditor({
  draft,
  mcpTools,
  mcpError,
  onRefreshMcp,
  onChange,
  onSave,
  onCancel,
}: {
  draft: Draft;
  mcpTools: McpToolInfo[] | null;
  mcpError: string | null;
  onRefreshMcp(): void;
  onChange(d: Draft): void;
  onSave(): void;
  onCancel(): void;
}) {
  const needPrompt = draft.kind === "message" || draft.kind === "mcp";
  const labelOk = draft.label.trim().length > 0;
  const promptOk = draft.kind === "mcp" ? draft.mcpTool.trim().length > 0 : !needPrompt || draft.prompt.trim().length > 0;
  const canSave = labelOk && promptOk;

  const pickTool = (name: string) => {
    const tool = mcpTools?.find((t) => t.name === name);
    onChange({
      ...draft,
      mcpTool: name,
      // 选定工具且文案为空时预填默认模板
      prompt: draft.prompt.trim() ? draft.prompt : `请调用 MCP 工具 ${name} 完成相应操作。`,
    });
    void tool;
  };

  return (
    <div className="qq-editor">
      <div className="grid sm:grid-cols-2 gap-3">
        <label className="block">
          <span className="field-label">标签（chip 上显示的文字）</span>
          <input
            className="text-input"
            value={draft.label}
            maxLength={32}
            placeholder="如：运行本地单测"
            onChange={(e) => onChange({ ...draft, label: e.target.value })}
          />
        </label>
        <label className="block">
          <span className="field-label">动作类型</span>
          <select
            className="text-input"
            value={draft.kind}
            onChange={(e) => onChange({ ...draft, kind: e.target.value as QuoteKind })}
          >
            {(Object.keys(KIND_LABEL) as QuoteKind[]).map((k) => (
              <option key={k} value={k}>
                {KIND_LABEL[k]}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="field-label">显示条件</span>
          <select
            className="text-input"
            value={draft.when}
            onChange={(e) => onChange({ ...draft, when: e.target.value as QuoteWhen })}
          >
            {(Object.keys(WHEN_LABEL) as QuoteWhen[]).map((w) => (
              <option key={w} value={w}>
                {WHEN_LABEL[w]}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="field-label">图标</span>
          <select
            className="text-input"
            value={draft.icon}
            onChange={(e) => onChange({ ...draft, icon: e.target.value })}
          >
            <option value="">默认（对话）</option>
            {ICON_CHOICES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {draft.kind === "presubmit" && (
        <div className="text-[12px] text-faint leading-relaxed">
          点击 chip 触发宿主的「预提审」功能：冻结当前工作区并启动一轮审查（与门禁流程一致）。
        </div>
      )}
      {draft.kind === "findings" && (
        <div className="text-[12px] text-faint leading-relaxed">
          点击 chip 触发宿主的「按审查意见修复」：把审查意见逐条拼进消息发给 Agent。
        </div>
      )}

      {draft.kind === "mcp" && (
        <label className="block">
          <span className="field-label">
            MCP 工具
            <button type="button" className="btn btn-sm ml-2" onClick={onRefreshMcp}>
              刷新工具列表
            </button>
          </span>
          {mcpTools && mcpTools.length > 0 ? (
            <select className="text-input" value={draft.mcpTool} onChange={(e) => pickTool(e.target.value)}>
              <option value="">选择工具 …</option>
              {mcpTools.map((t) => (
                <option key={t.name} value={t.name}>
                  {t.name}（{t.domain}）
                </option>
              ))}
            </select>
          ) : (
            <input
              className="text-input"
              value={draft.mcpTool}
              placeholder={mcpError ? "工具列表读取失败，可手填工具名" : "工具名，如 presubmit_create"}
              onChange={(e) => onChange({ ...draft, mcpTool: e.target.value })}
            />
          )}
          {mcpError && (
            <span className="block mt-1 text-[11px] text-warn break-all">MCP 状态读取失败：{mcpError}</span>
          )}
        </label>
      )}

      {needPrompt && (
        <label className="block">
          <span className="field-label">{draft.kind === "mcp" ? "发送文案（指示 Agent 调用该工具）" : "发送文案"}</span>
          <textarea
            className="textarea"
            rows={3}
            value={draft.prompt}
            maxLength={2000}
            placeholder={draft.kind === "mcp" ? "留空将自动生成默认指示文案" : "点击 chip 后发送给 Agent 的消息"}
            onChange={(e) => onChange({ ...draft, prompt: e.target.value })}
          />
        </label>
      )}

      <div className="flex items-center gap-2">
        <button type="button" className="btn btn-primary" disabled={!canSave} onClick={onSave}>
          <Check size={13} weight="bold" /> 保存
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          取消
        </button>
        {!labelOk && <span className="text-[11px] text-warn">标签不能为空</span>}
        {labelOk && !promptOk && (
          <span className="text-[11px] text-warn">{draft.kind === "mcp" ? "MCP 工具不能为空" : "发送文案不能为空"}</span>
        )}
      </div>
    </div>
  );
}

/* ─── 管理面板 ─── */

export function QuotesManager({ hasKv, onChange, loadMcpTools }: Props) {
  const quotes = useSyncExternalStore(quoteStore.subscribe, quoteStore.get);
  const [editingId, setEditingId] = useState<string | "new" | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState("");
  const [notice, setNotice] = useState<{ text: string; bad: boolean } | null>(null);
  const [mcpTools, setMcpTools] = useState<McpToolInfo[] | null>(null);
  const [mcpError, setMcpError] = useState<string | null>(null);
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const res = await loadMcpTools();
        setMcpTools(res.tools ?? []);
      } catch (e) {
        setMcpError((e as Error).message);
      }
    })();
  }, [loadMcpTools]);

  const say = (text: string, bad = false) => {
    setNotice({ text, bad });
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setNotice(null), 2600);
  };

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const onDragEnd = (e: DragEndEvent) => {
    const { active, over } = e;
    if (!over || active.id === over.id) return;
    const from = quotes.findIndex((q) => q.id === active.id);
    const to = quotes.findIndex((q) => q.id === over.id);
    if (from < 0 || to < 0) return;
    onChange(arrayMove(quotes, from, to));
  };

  const startAdd = () => {
    setEditingId("new");
    setDraft({ label: "", kind: "message", when: "always", prompt: "", mcpTool: "", icon: "ChatText" });
  };

  const startEdit = (q: QuoteItem) => {
    setEditingId(q.id);
    setDraft(draftOf(q));
  };

  const saveDraft = () => {
    if (!draft) return;
    const label = draft.label.trim();
    if (!label) return;
    const item: QuoteItem = {
      id: editingId === "new" || editingId === null ? newQuoteId() : editingId,
      label,
      kind: draft.kind,
      when: draft.when,
      prompt:
        draft.kind === "mcp" && !draft.prompt.trim()
          ? `请调用 MCP 工具 ${draft.mcpTool.trim()} 完成相应操作。`
          : draft.prompt.trim(),
      mcpTool: draft.kind === "mcp" ? draft.mcpTool.trim() : undefined,
      icon: draft.icon || undefined,
      builtin: editingId !== "new" ? quotes.find((q) => q.id === editingId)?.builtin : false,
    };
    const exists = editingId !== "new" && quotes.some((q) => q.id === editingId);
    onChange(exists ? quotes.map((q) => (q.id === item.id ? item : q)) : [...quotes, item]);
    setEditingId(null);
    setDraft(null);
    say(exists ? "已保存" : "已新增");
  };

  const remove = (id: string) => {
    onChange(quotes.filter((q) => q.id !== id));
    if (editingId === id) {
      setEditingId(null);
      setDraft(null);
    }
    say("已删除");
  };

  const restoreBuiltins = () => {
    const next = mergeBuiltins(quotes);
    if (next === quotes) {
      say("内置语录均已存在");
      return;
    }
    onChange(next);
    say("已恢复缺失的内置语录");
  };

  const doExport = async () => {
    const json = JSON.stringify(quotes, null, 2);
    try {
      await navigator.clipboard.writeText(json);
      say(`已复制 ${quotes.length} 条语录的 JSON 到剪贴板`);
    } catch {
      setImportOpen(true);
      setImportText(json);
      say("剪贴板不可用，已填入下方导入框，可从中复制", true);
    }
  };

  const doImport = () => {
    try {
      const parsed = sanitizeQuotes(JSON.parse(importText));
      if (parsed.length === 0) {
        say("未解析到合法语录（每条至少需要 id 与标签）", true);
        return;
      }
      onChange(parsed);
      setImportOpen(false);
      setImportText("");
      say(`已导入 ${parsed.length} 条语录（覆盖原列表）`);
    } catch {
      say("JSON 解析失败", true);
    }
  };

  return (
    <div className="qq-root">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-[12px] text-faint">
          共 <span className="font-mono text-ink">{quotes.length}</span> 条 · 顺序即对话上方 chips 的显示顺序
        </span>
        <span className="flex-1" />
        <button type="button" className="btn btn-sm" onClick={startAdd} disabled={!hasKv}>
          <Plus size={12} weight="bold" /> 新增
        </button>
        <button type="button" className="btn btn-sm" onClick={() => void doExport()}>
          <DownloadSimple size={12} /> 导出
        </button>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => setImportOpen((v) => !v)}
          disabled={!hasKv}
        >
          <UploadSimple size={12} /> 导入
        </button>
        <button type="button" className="btn btn-sm" onClick={restoreBuiltins} disabled={!hasKv}>
          <ArrowsCounterClockwise size={12} /> 恢复内置
        </button>
      </div>

      {notice && (
        <div className={`text-[12px] ${notice.bad ? "text-danger" : "text-accent"}`}>
          {notice.bad && <WarningCircle size={12} className="inline-block mr-1 -translate-y-px" weight="fill" />}
          {notice.text}
        </div>
      )}

      {!hasKv && (
        <div className="rounded-lg border border-warn/30 bg-warn-dim/30 px-3 py-2 text-[12px] text-warn leading-relaxed">
          manifest 未声明 kv 权限或后端拒绝了数据访问：语录只读（当前展示内置种子）。编辑需要在
          manifest.json 的 permissions 里加入 "kv" 并重载插件。
        </div>
      )}

      {importOpen && (
        <div className="rounded-lg border border-edge bg-canvas p-3 space-y-2">
          <div className="flex items-center gap-2">
            <span className="field-label !mb-0">导入 JSON（将覆盖当前列表）</span>
            <span className="flex-1" />
            <button type="button" className="icon-btn" title="关闭" onClick={() => setImportOpen(false)}>
              <X size={13} />
            </button>
          </div>
          <textarea
            className="textarea font-mono text-[11.5px]"
            rows={6}
            value={importText}
            placeholder="粘贴导出的 JSON 数组 …"
            onChange={(e) => setImportText(e.target.value)}
          />
          <button type="button" className="btn btn-sm btn-primary" disabled={!importText.trim()} onClick={doImport}>
            覆盖导入
          </button>
        </div>
      )}

      {quotes.length === 0 ? (
        <div className="text-center py-8 text-[12.5px] text-faint leading-relaxed">
          暂无语录。点击「新增」创建，或「恢复内置」找回预置语录
          <br />
          <span className="text-[11px]">（预置：预提审 / 解释当前变更 / 运行本地单测 / 完成此工单 / 按审查意见修复 / MCP 预提审）</span>
        </div>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={quotes.map((q) => q.id)} strategy={verticalListSortingStrategy}>
            <div className="qq-list">
              {quotes.map((q) => (
                <SortableRow
                  key={q.id}
                  quote={q}
                  editable={hasKv}
                  onEdit={() => startEdit(q)}
                  onDelete={() => remove(q.id)}
                />
              ))}
            </div>
          </SortableContext>
        </DndContext>
      )}

      {editingId !== null && draft && (
        <QuoteEditor
          draft={draft}
          mcpTools={mcpTools}
          mcpError={mcpError}
          onRefreshMcp={() => {
            void (async () => {
              try {
                const res = await loadMcpTools();
                setMcpTools(res.tools ?? []);
                setMcpError(null);
              } catch (e) {
                setMcpError((e as Error).message);
              }
            })();
          }}
          onChange={setDraft}
          onSave={saveDraft}
          onCancel={() => {
            setEditingId(null);
            setDraft(null);
          }}
        />
      )}
    </div>
  );
}
