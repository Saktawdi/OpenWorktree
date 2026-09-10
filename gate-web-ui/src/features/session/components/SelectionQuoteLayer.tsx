/**
 * 全局划选引用层（SelectionQuoteLayer）：监听整页的左键划选，选区非空时在选区
 * 附近浮出动作菜单——内置两个宿主一等能力：「询问小助手」（T-109 原生 LLM 助手，
 * 偏好里可关）与「添加到对话框」（写入当前工单的 pendingQuotes，Composer 渲染成
 * 引用胶囊随下一条消息发送）；插件的「添加到 xxx」等动作经 selection.menu 插槽
 * 追加（对具体插件零感知，与 ChatActionChips 同构）。
 *
 * 生命周期：mouseup（左键）定锚 → 选区塌陷/滚动/Esc/点击菜单外收起。
 * 输入框与富文本编辑区内的划选不触发（那是编辑自己的内容，不是引用素材）。
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { Quotes, Sparkle } from "@phosphor-icons/react";
import { appStore, showToast, useApp } from "@/store";
import { usePlugins } from "@/app/plugins/state";
import { SLOT_SELECTION_MENU } from "@/app/plugins/slots";
import { pluginIcon } from "@/app/plugins/icons";
import type { SelectionActionApi, SelectionActionContribution } from "@/app/plugins/types";
import { addPendingQuote } from "@/features/session";
import { askAssistant } from "@/features/assistant";
import { ASSISTANT_SETTINGS_DEFAULTS } from "@/store/prefs";
import { focusComposer, insertIntoComposer } from "../composerBridge";
import { QUOTE_MAX_CHARS } from "@/shared/quotes";
import { useT, type Translate } from "@/i18n";

/** 菜单估宽（px）：单行图标 + 文案，用于水平方向收进视口。 */
const MENU_W = 150;
const VIEWPORT_EDGE = 8;

interface Anchor {
  left: number;
  top: number;
  below: boolean;
  text: string;
  /** 划选来源标注（提示卡片底部「引用自 xxx」），addPendingQuote 落库用。 */
  source: string;
}

/** 编辑宿主（输入框/富文本）里的划选属于内容编辑，不提供引用菜单。 */
function inEditableHost(target: EventTarget | null): boolean {
  return !!(
    target instanceof Element &&
    target.closest("textarea, input, [contenteditable='true'], [contenteditable='plaintext-only']")
  );
}

/** 划选来源的展示标注：优先按命中元素的特征容器，退回当前视图/页签。 */
function describeSource(target: EventTarget | null, t: Translate): string {
  if (target instanceof Element && target.closest("[data-chat-msg]")) {
    return t("quote.src.chatMessage");
  }
  const st = appStore.getState();
  if (st.view === "workbench") {
    const byTab: Record<string, string> = {
      chat: t("wb.tab.chat"),
      diff: t("wb.tab.diff"),
      findings: t("wb.tab.findings"),
    };
    return byTab[st.centerTab] ?? t("quote.src.workbench");
  }
  const byView: Record<string, string> = {
    kanban: t("topbar.view.kanban"),
    projects: t("topbar.view.projects"),
    repo: t("quote.src.repo"),
    agents: t("topbar.view.agents"),
    plugins: t("topbar.view.plugins"),
    settings: t("topbar.openSettings"),
    "plugin-page": t("quote.src.pluginPage"),
  };
  return byView[st.view] ?? t("quote.src.workbench");
}

export function SelectionQuoteLayer() {
  const t = useT();
  const [anchor, setAnchor] = useState<Anchor | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const contributions = usePlugins((s) => s.contributions);
  const askEnabled = useApp((s) => s.assistantSettings.selectionAskEnabled);
  const askPrompt = useApp((s) => s.assistantSettings.selectionAskPrompt);
  const pluginActions = useMemo(
    () =>
      contributions
        .filter((c) => c.slot === SLOT_SELECTION_MENU)
        .map((c) => ({ pluginId: c.pluginId, action: c.contribution as SelectionActionContribution })),
    [contributions],
  );

  useEffect(() => {
    const hide = () => setAnchor(null);

    /** 左键松开且选区非空 → 在选区边缘定锚浮出菜单。 */
    const onMouseUp = (e: MouseEvent) => {
      if (e.button !== 0) return;
      if (menuRef.current?.contains(e.target as Node)) return;
      const sel = window.getSelection();
      const text = sel?.toString() ?? "";
      if (!sel || sel.isCollapsed || !text.trim()) {
        hide();
        return;
      }
      if (inEditableHost(e.target)) {
        hide();
        return;
      }
      let rect: DOMRect;
      try {
        rect = sel.getRangeAt(0).getBoundingClientRect();
      } catch {
        hide();
        return;
      }
      if (!rect || (rect.width === 0 && rect.height === 0)) {
        hide();
        return;
      }
      // 选区顶到视口上缘时菜单改弹下方；水平方向收进视口。
      const below = rect.top < 48;
      const vw = window.innerWidth;
      setAnchor({
        left: Math.min(Math.max(rect.left, VIEWPORT_EDGE), Math.max(VIEWPORT_EDGE, vw - MENU_W - VIEWPORT_EDGE)),
        top: below ? rect.bottom + 6 : rect.top - 6,
        below,
        text: text.slice(0, QUOTE_MAX_CHARS),
        source: describeSource(e.target, t),
      });
    };

    /** 点击别处 / 选区塌陷 / 滚动（锚点随滚失效）/ Esc / 调整窗口 → 收起。 */
    const onSelectionChange = () => {
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed) hide();
    };
    const onMouseDown = (e: MouseEvent) => {
      if (menuRef.current?.contains(e.target as Node)) return;
      hide();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") hide();
    };
    const onInvalidate = () => hide();

    document.addEventListener("mouseup", onMouseUp);
    document.addEventListener("mousedown", onMouseDown);
    document.addEventListener("selectionchange", onSelectionChange);
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("scroll", onInvalidate, true);
    window.addEventListener("resize", onInvalidate);
    return () => {
      document.removeEventListener("mouseup", onMouseUp);
      document.removeEventListener("mousedown", onMouseDown);
      document.removeEventListener("selectionchange", onSelectionChange);
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("scroll", onInvalidate, true);
      window.removeEventListener("resize", onInvalidate);
    };
  }, []);

  if (!anchor) return null;

  /** 目标工单校验：未选工单/工单已终态时给出提示（菜单条目仍可点，反馈即引导）。 */
  const quoteTarget = (): string | null => {
    const st = appStore.getState();
    const no = st.selectedNo;
    if (!no) {
      showToast(t("quote.selectTicketFirst"));
      return null;
    }
    const stage = st.tickets.find((t) => t.ticketNo === no)?.stage;
    if (stage === "DONE" || stage === "CANCELLED") {
      showToast(t("quote.ticketEnded"));
      return null;
    }
    return no;
  };

  const dismiss = () => {
    window.getSelection()?.removeAllRanges();
    setAnchor(null);
  };

  const addToComposer = (text: string) => {
    const no = quoteTarget();
    if (!no) return;
    addPendingQuote(no, text, anchor.source);
    showToast(t("quote.addedToast"));
    focusComposer();
    dismiss();
  };

  const pluginApi: SelectionActionApi = {
    addToComposer,
    insertText: (text) => insertIntoComposer(text),
    toast: showToast,
  };

  /** 插件动作：when 异常按隐藏处理；run 异常 toast，不污染主应用。 */
  const visiblePluginActions = pluginActions.filter(({ action }) => {
    try {
      return action.when ? action.when({ text: anchor.text }) : true;
    } catch {
      return false;
    }
  });

  const runPluginAction = (action: SelectionActionContribution) => {
    try {
      action.run(pluginApi, anchor.text);
    } catch (e) {
      showToast(t("quote.pluginActionFailed", { err: (e as Error).message }));
    }
  };

  return (
    <div
      ref={menuRef}
      className="fixed z-[85] animate-rise"
      style={{
        left: anchor.left,
        top: anchor.top,
        transform: anchor.below ? "translateY(0)" : "translateY(-100%)",
      }}
      // 按下菜单不夺走选区（preventDefault 阻止浏览器塌陷选区），click 才拿得到稳定文本
      onMouseDown={(e) => e.preventDefault()}
    >
      <div className="flex flex-col rounded-xl border border-edge bg-panel p-1 shadow-xl shadow-black/40">
        {askEnabled && (
          <button
            className="menu-action"
            title={t("quote.askAssistantTip")}
            onClick={() => {
              askAssistant(`${askPrompt || ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}${anchor.text}`);
              dismiss();
            }}
          >
            <Sparkle size={12} weight="fill" className="text-accent" />
            {t("quote.askAssistant")}
          </button>
        )}
        <button
          className="menu-action"
          title={t("quote.addToComposerTip")}
          onClick={() => addToComposer(anchor.text)}
        >
          <Quotes size={12} weight="fill" className="text-info" />
          {t("quote.addToChat")}
        </button>
        {visiblePluginActions.map(({ pluginId, action }) => {
          const QIcon = pluginIcon(action.icon);
          return (
            <button
              key={`${pluginId}:${action.id}`}
              className="menu-action"
              title={t("quote.fromPlugin", { id: pluginId })}
              onClick={() => runPluginAction(action)}
            >
              <QIcon size={12} weight="fill" className="opacity-60" />
              {action.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
