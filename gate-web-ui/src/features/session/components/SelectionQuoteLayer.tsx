/**
 * 全局划选引用层（SelectionQuoteLayer）：监听整页的左键划选，选区非空时在选区
 * 最长那一行的末尾浮出「月牙盘」——动作排布成一段竖直的弧线，弧顶那一枚拱在最右，
 * 上下两枚依次向左侧内收（排布算法移植自 opentreelearn 的 SelectionMenu）。
 *
 * 盘内动作与排序：宿主原生的「添加到对话框」恒居首位（主路径，插件 API 的
 * addToComposer 同源）——写入当前工单的 pendingQuotes，Composer 渲染成引用胶囊
 * 随下一条消息发送；「询问小助手」次之（T-109 原生 LLM 助手，偏好里可关）；
 * 插件动作按注册顺序沿弧线顺位追加——原生能力不被插件挤位。
 *
 * 图标按钮没有常驻文案：短名进 aria-label（读屏），完整提示进 title（悬浮），
 * 插件动作的 title 仍带上来源插件。
 *
 * 生命周期：mouseup（左键）定锚 → 选区塌陷/滚动/Esc/点击菜单外收起。
 * 输入框与富文本编辑区内的划选不触发（那是编辑自己的内容，不是引用素材）。
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
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

/* ── 月牙盘几何（px / 度） ──
   按钮直径与图标、弧线半径、相邻两枚的基准圆心角、上下各最多张开的半角、
   相邻两枚的最小净距。半径与张角由「相邻两枚不能叠在一起」定：
   相邻圆心距 2R·sin(步长/2) 要大于按钮直径 + 净距。 */
const BUTTON = 30;
const BUTTON_ICON = 15;
const ARC_RADIUS = 68;
const ARC_STEP = 31;
const MAX_HALF_SPREAD = 66;
const BUTTON_GAP = 6;
/** 月牙盘收进视口的边距。 */
const VIEWPORT_EDGE = 8;

interface Anchor {
  /** 锚点：选区终点所在块里最长那一行的右端（菜单中轴对准这里）。 */
  x: number;
  /** 该行的垂直中线（菜单中腰对准这一行）。 */
  y: number;
  text: string;
  /** 划选来源标注（提示卡片底部「引用自 xxx」），addPendingQuote 落库用。 */
  source: string;
}

interface ArcSlot {
  left: number;
  top: number;
}

interface ArcLayout {
  width: number;
  height: number;
  slot(index: number): ArcSlot;
}

/**
 * 月牙盘排布：按钮沿一段竖直弧线铺开——弧顶（中轴那一枚）拱在最右，离弧顶越远
 * 越向左侧内收，整体是一弯竖起来的月牙。动作枚数不固定（内置 1~2 + 插件动作），
 * 张角随枚数增长、半角封顶在 MAX_HALF_SPREAD；张角不够铺开时整体加大半径，
 * 任一枚数都不叠。容器尺寸只用于定位与视口避让。
 */
function arcLayout(count: number): ArcLayout {
  const half = count <= 1 ? 0 : Math.min(MAX_HALF_SPREAD, (ARC_STEP * (count - 1)) / 2);
  const step = count <= 1 ? 0 : (half * 2) / (count - 1);
  const radius =
    count <= 1
      ? ARC_RADIUS
      : Math.max(ARC_RADIUS, (BUTTON + BUTTON_GAP) / (2 * Math.sin((step * Math.PI) / 360)));
  const width = Math.round(radius * (1 - Math.cos((half * Math.PI) / 180)) + BUTTON);
  const height = Math.round(2 * radius * Math.sin((half * Math.PI) / 180) + BUTTON);
  return {
    width,
    height,
    slot: (index) => {
      const angle = ((-half + index * step) * Math.PI) / 180;
      return {
        left: Math.round(width - BUTTON - radius * (1 - Math.cos(angle))),
        top: Math.round(height / 2 + radius * Math.sin(angle) - BUTTON / 2),
      };
    },
  };
}

/** 夹进 [min, max]；容器比视口还大时 min 会大于 max，取 min 兜底。 */
function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
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

interface LineBox {
  rect: DOMRect;
}

/**
 * 逐文本节点量出的行框（逐行），只走 root 子树。
 *
 * 不能直接用 range.getBoundingClientRect()：选区整段跨过某个块时，浏览器给的是
 * 那个块的**盒模型**——段落、标题、列表项都会给一个占满版心的整宽框（高度还可能
 * 是一整块）。「最右」一挑就挑到版心边缘去了，月牙盘于是飘到内容区最右边。
 * 把 range 逐文本节点裁一刀再量，得到的才是文字实宽。
 *
 * 只走「选区终点所在的那个块」而不是整个选区：Ctrl+A 之类的大选区横跨整页时，
 * 从共同祖先往下遍历（终端的每一行都是节点）会逐节点触发布局，量到卡顿。
 */
function blockBoxes(range: Range, root: Node): LineBox[] {
  const boxes: LineBox[] = [];

  const collect = (node: Text) => {
    if (!range.intersectsNode(node)) return;
    const length = node.nodeValue?.length ?? 0;
    const start = node === range.startContainer ? range.startOffset : 0;
    const end = node === range.endContainer ? range.endOffset : length;
    if (start >= end) return;

    const slice = document.createRange();
    slice.setStart(node, start);
    slice.setEnd(node, end);
    for (const rect of slice.getClientRects()) {
      if (rect.width > 0 && rect.height > 0) boxes.push({ rect });
    }
  };

  if (root.nodeType === Node.TEXT_NODE) {
    collect(root as Text);
  } else {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) collect(walker.currentNode as Text);
  }

  return boxes;
}

/** 块级盒子的 display：用来认出「一个自然段 / 一个列表项 / 一行代码」。 */
const BLOCK_DISPLAY = /^(block|list-item|flow-root|table|flex|grid)$/;

/** 一个节点所属的那个块（p / li / 代码行 …）；一路到 body 都没找到就返回 null。 */
function ownerBlock(node: Node): Element | null {
  let element: Element | null = node instanceof Element ? node : node.parentElement;
  while (element && element !== document.body) {
    if (BLOCK_DISPLAY.test(getComputedStyle(element).display)) return element;
    element = element.parentElement;
  }
  return null;
}

/** 取右端最靠右的那个行框：同左缩进的文字里，它也就是最长的那一行。 */
function rightmost(boxes: LineBox[]): LineBox | null {
  let best: LineBox | null = null;
  for (const box of boxes) {
    if (best === null || box.rect.left + box.rect.width > best.rect.left + best.rect.width) {
      best = box;
    }
  }
  return best;
}

/**
 * 月牙盘的锚点：选区终点所在块里最长那一行的右端 + 该行垂直中线。
 *
 * 只在「选区终点所在的那个块」里挑最长的一行：跨块框选时全局最长的一行常常在别的
 * 块里，而一个长段落的首行本就占满版心——挑中它菜单就飘到版心右缘，离选区十万八千里。
 * 收在终点所在的块里，多行选区仍然挂在块的最右边界外。
 */
function lineAnchor(range: Range): { x: number; y: number } | null {
  const block = ownerBlock(range.endContainer);
  // 终点落在空块/元素边界上时块里量不到文字行框，退回 range 自己的行框集合兜底
  let boxes = block ? blockBoxes(range, block) : [];
  if (boxes.length === 0) {
    boxes = [];
    for (const rect of range.getClientRects()) {
      if (rect.width > 0 && rect.height > 0) boxes.push({ rect });
    }
  }

  // 横向可滚动的块（diff 代码、宽表格都是 overflow-x: auto）里，文字的排版宽度可以远超
  // 可见区，量出来的「最右」落在滚出视野的内容深处——先剔掉伸出视口的行框；整段选区都在
  // 可滚动块里时没得挑，退回原集合，靠下面的 Math.min 把它拉回视口边缘。
  const vw = window.innerWidth;
  const inside = boxes.filter((box) => box.rect.left + box.rect.width <= vw + 2);
  const box = rightmost(inside.length > 0 ? inside : boxes);
  if (!box) return null;

  return {
    x: Math.min(box.rect.left + box.rect.width, vw),
    y: (box.rect.top + box.rect.bottom) / 2,
  };
}

/** 月牙盘里的一枚：圆形图标按钮。短名进 aria-label（读屏），完整提示进 title（悬浮）。 */
function DiscButton({
  slot,
  label,
  tip,
  onSelect,
  children,
}: {
  slot: ArcSlot;
  label: string;
  tip: string;
  onSelect: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={tip}
      style={{ left: slot.left, top: slot.top, width: BUTTON, height: BUTTON }}
      // 按下不夺走选区（preventDefault 阻止浏览器塌陷选区），click 才拿得到稳定文本
      onMouseDown={(e) => e.preventDefault()}
      onClick={onSelect}
      className="disc-action pointer-events-auto"
    >
      {children}
    </button>
  );
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

    /** 左键松开且选区非空 → 在选区最长那一行的末尾定锚浮出月牙盘。 */
    const onMouseUp = (e: MouseEvent) => {
      if (e.button !== 0) return;
      if (menuRef.current?.contains(e.target as Node)) return;
      const sel = window.getSelection();
      const text = sel?.toString() ?? "";
      if (!sel || sel.isCollapsed || sel.rangeCount === 0 || !text.trim()) {
        hide();
        return;
      }
      if (inEditableHost(e.target)) {
        hide();
        return;
      }
      let point: { x: number; y: number } | null = null;
      try {
        point = lineAnchor(sel.getRangeAt(0));
      } catch {
        point = null;
      }
      // 锚点不在视口里（选区滚出去了）就别再弹了，月牙盘是贴着文字出现的
      if (!point || point.y < 0 || point.y > window.innerHeight) {
        hide();
        return;
      }
      setAnchor({
        x: point.x,
        y: point.y,
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
  }, [t]);

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

  // 月牙盘的枚数决定弧线几何：内置「添加到对话框」恒居首位，小助手次之（偏好关闭不占位），
  // 插件动作按注册顺序接在弧线末尾
  const pluginBase = 1 + (askEnabled ? 1 : 0);
  const layout = arcLayout(pluginBase + visiblePluginActions.length);
  // 视口边缘只做整体平移避让（中轴落在行末、中腰对准那一行），不改锚点与盘的相对关系
  const left = clamp(
    anchor.x - layout.width / 2,
    VIEWPORT_EDGE,
    window.innerWidth - VIEWPORT_EDGE - layout.width,
  );
  const top = clamp(
    anchor.y - layout.height / 2,
    VIEWPORT_EDGE,
    window.innerHeight - VIEWPORT_EDGE - layout.height,
  );

  return (
    <div
      ref={menuRef}
      className="pointer-events-none fixed z-[85] animate-scale-in"
      style={{ left, top, width: layout.width, height: layout.height }}
    >
      <DiscButton
        slot={layout.slot(0)}
        label={t("quote.addToChat")}
        tip={t("quote.addToComposerTip")}
        onSelect={() => addToComposer(anchor.text)}
      >
        <Quotes size={BUTTON_ICON} weight="fill" className="text-info" />
      </DiscButton>
      {askEnabled && (
        <DiscButton
          slot={layout.slot(1)}
          label={t("quote.askAssistant")}
          tip={t("quote.askAssistantTip")}
          onSelect={() => {
            askAssistant(
              `${askPrompt || ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}${anchor.text}`,
            );
            dismiss();
          }}
        >
          <Sparkle size={BUTTON_ICON} weight="fill" className="text-accent" />
        </DiscButton>
      )}
      {visiblePluginActions.map(({ pluginId, action }, i) => {
        const QIcon = pluginIcon(action.icon);
        return (
          <DiscButton
            key={`${pluginId}:${action.id}`}
            slot={layout.slot(pluginBase + i)}
            label={action.label}
            tip={`${action.label} · ${t("quote.fromPlugin", { id: pluginId })}`}
            onSelect={() => runPluginAction(action)}
          >
            <QIcon size={BUTTON_ICON} weight="fill" className="opacity-60" />
          </DiscButton>
        );
      })}
    </div>
  );
}