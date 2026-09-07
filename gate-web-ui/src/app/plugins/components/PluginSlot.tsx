/**
 * 插件系统（app/plugins）：渲染型区域插槽的通用载体。
 *
 * 只做两件事：按注册顺序渲染目标区域的所有贡献物、逐个包 PluginBoundary（渲染崩溃只塌一格）。
 * region 级行为（卡片边框、标题栏、折叠、整行隐藏……）由挂载方通过 wrap 注入或留在区域
 * wrapper 组件里，本组件对具体区域零感知——新增渲染型区域零改此处。
 *
 * 动作型区域（composer.chips）不经过本组件：贡献物不渲染而是被执行，见 ChatActionChips。
 */
import { useMemo, type ReactNode } from "react";
import { usePlugins } from "@/app/plugins/state";
import { PluginBoundary } from "./PluginBoundary";
import type { RenderSlotName, SlotContributionMap } from "@/app/plugins/slots";

interface PluginSlotProps<S extends RenderSlotName> {
  /** 目标区域名（slots.ts 的 SlotContributionMap 登记）。 */
  name: S;
  /** 只渲染该插件的贡献物（如把管理面板嵌进所属插件卡片）；缺省渲染全部。 */
  pluginId?: string;
  /** 每条贡献物的 region 级包装（卡片边框/标题栏等）；缺省直接渲染 render() 输出。 */
  wrap?: (
    node: ReactNode,
    item: { pluginId: string; contribution: SlotContributionMap[S] },
  ) => ReactNode;
}

export function PluginSlot<S extends RenderSlotName>({ name, pluginId, wrap }: PluginSlotProps<S>) {
  const contributions = usePlugins((s) => s.contributions);
  const items = useMemo(
    () => contributions.filter((c) => c.slot === name && (pluginId === undefined || c.pluginId === pluginId)),
    [contributions, name, pluginId],
  );

  if (items.length === 0) return null;

  return (
    <>
      {items.map(({ pluginId, contribution }, i) => {
        const c = contribution as SlotContributionMap[S];
        const label = ("title" in c && typeof c.title === "string" ? c.title : null) ?? pluginId;
        const node = (
          // 延迟到 React 渲染期才调 render()，同步抛错落进 PluginBoundary（只塌一格）
          <PluginBoundary label={label}>{c.render()}</PluginBoundary>
        );
        return (
          <div key={`${pluginId}:${i}`}>
            {wrap ? wrap(node, { pluginId, contribution: c }) : node}
          </div>
        );
      })}
    </>
  );
}
