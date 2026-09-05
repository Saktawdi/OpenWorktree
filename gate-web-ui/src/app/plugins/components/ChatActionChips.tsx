/**
 * 插件系统（app/plugins）：composer.chips 区域的插件贡献段。
 *
 * 原生快捷 chip（预提审/解释变更/单测…）是 Composer 域一等数据，**不进插件注册表**、
 * 不经过本组件——宿主 UI 在插件全禁用/加载失败时仍完整可用。本组件只负责渲染插件的
 * 追加贡献（原生段之后）：读取注册表、组装 ChatInputState、执行可见性判断与动作分发、
 * 条目过多时折叠/展开。对具体插件零感知，新增/删除/更新插件都不需要改这里。
 */
import { useEffect, useMemo, useState } from "react";
import { CaretUp, DotsThree } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { showToast, useApp } from "@/store";
import { usePlugins } from "@/app/plugins/state";
import { SLOT_COMPOSER_CHIPS } from "@/app/plugins/slots";
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
}

export function ChatActionChips({ ticketNo, busy, insertText }: Props) {
  const contributions = usePlugins((s) => s.contributions);
  const pluginActions = useMemo(
    () =>
      contributions
        .filter((c) => c.slot === SLOT_COMPOSER_CHIPS)
        .map((c) => ({ pluginId: c.pluginId, action: c.contribution as ChatInputActionContribution })),
    [contributions],
  );
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

  if (visibleChips.length === 0) return null;

  return (
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
  );
}
