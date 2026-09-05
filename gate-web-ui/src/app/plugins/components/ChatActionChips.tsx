/**
 * 插件系统（app/plugins）：对话输入区上方的「快捷动作」通用槽位。
 *
 * 这是插件贡献点（ChatInputActionContribution）在宿主侧的唯一渲染点：读取注册表、
 * 组装 ChatInputState、执行插件的可见性判断与动作分发、条目过多时折叠/展开。
 * 本组件对具体插件零感知——新增/删除/更新插件都不需要改这里，更不需要改业务组件；
 * 业务组件（如 session/Composer）只需一行挂载并按需注入输入框能力。
 */
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { CaretUp, DotsThree } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { showToast, useApp } from "@/store";
import { usePlugins } from "@/app/plugins/state";
import { pluginIcon } from "@/app/plugins/icons";
import type { ChatActionApi, ChatInputActionContribution, ChatInputState } from "@/app/plugins/types";

/** 折叠阈值：可见条目超过它就只显示前 N 条 + 「+余量」展开按钮。 */
const CHIP_COLLAPSE_LIMIT = 4;

interface Props {
  ticketNo: string;
  /** 跟随「当前查看的会话」的忙态（会话语义归业务组件所有，此处只透传）。 */
  busy: boolean;
  /** 在输入框光标处插入文本（textarea 能力，由挂载方注入）。 */
  insertText(text: string): void;
  /** 行尾附加内容（如 token 用量）；无可见 chips 且无附加内容时整行不渲染。 */
  children?: ReactNode;
}

export function ChatActionChips({ ticketNo, busy, insertText, children }: Props) {
  const pluginActions = usePlugins((s) => s.actions);
  const mode = useApp((s) => s.mode);
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const restartCount = useApp(
    (s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.restartCount ?? 0,
  );
  const diffs = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);

  const [chipsExpanded, setChipsExpanded] = useState(false);
  useEffect(() => setChipsExpanded(false), [ticketNo]);

  const terminal = stage === "DONE" || stage === "CANCELLED";
  const chatState: ChatInputState = {
    ticketNo,
    mode,
    busy: !!busy,
    terminal,
    stage: stage ?? "",
    diffs,
    findingsCount,
    restartCount,
  };

  const visibleChips = useMemo(
    () =>
      pluginActions.filter(({ action }) => {
        try {
          return action.when ? action.when(chatState) : true;
        } catch {
          return false; // 插件可见性判断异常按隐藏处理，不污染输入区
        }
      }),
    // chatState 是字面量对象，按其字段展开做依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      pluginActions,
      ticketNo,
      mode,
      busy,
      terminal,
      stage,
      diffs,
      findingsCount,
      restartCount,
    ],
  );

  const runAction = (action: ChatInputActionContribution) => {
    const api: ChatActionApi = {
      insertText,
      sendPrompt: (text) => void actions.sendPrompt(ticketNo, text),
      presubmit: () => void actions.presubmit(ticketNo),
      returnWithFindings: () => void actions.returnWithFindings(ticketNo),
      toast: showToast,
    };
    try {
      action.run(api, chatState);
    } catch (e) {
      showToast(`插件动作执行失败：${(e as Error).message}`);
    }
  };

  const hasExtras = useMemo(() => {
    const arr = [];
    for (const child of [children]) {
      if (child === null || child === false || child === undefined) continue;
      arr.push(child);
    }
    return arr.length > 0;
  }, [children]);

  if (visibleChips.length === 0 && !hasExtras) return null;

  return (
    <div className="flex items-center gap-3">
      {visibleChips.length > 0 && (
        <div className="flex flex-wrap gap-1.5 min-w-0">
          {(chipsExpanded ? visibleChips : visibleChips.slice(0, CHIP_COLLAPSE_LIMIT)).map(
            ({ pluginId, action }) => {
              const QIcon = pluginIcon(action.icon);
              return (
                <button
                  key={`${pluginId}:${action.id}`}
                  disabled={busy}
                  className="composer-chip"
                  onClick={() => runAction(action)}
                >
                  <QIcon size={12} weight="fill" className="opacity-60" />
                  {action.label}
                </button>
              );
            },
          )}
          {!chipsExpanded && visibleChips.length > CHIP_COLLAPSE_LIMIT && (
            <button
              className="composer-chip"
              disabled={busy}
              title={`展开其余 ${visibleChips.length - CHIP_COLLAPSE_LIMIT} 条快捷动作`}
              onClick={() => setChipsExpanded(true)}
            >
              <DotsThree size={12} weight="bold" className="opacity-60" />
              {`+${visibleChips.length - CHIP_COLLAPSE_LIMIT}`}
            </button>
          )}
          {chipsExpanded && visibleChips.length > CHIP_COLLAPSE_LIMIT && (
            <button
              className="composer-chip"
              disabled={busy}
              title="收起快捷动作"
              onClick={() => setChipsExpanded(false)}
            >
              <CaretUp size={12} weight="bold" className="opacity-60" />
              收起
            </button>
          )}
        </div>
      )}
      <span className="flex-1" />
      {children}
    </div>
  );
}
