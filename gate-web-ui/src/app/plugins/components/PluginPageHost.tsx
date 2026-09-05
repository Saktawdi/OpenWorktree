/**
 * 插件系统（app/plugins）：插件整页宿主（view="plugin-page" 的全页载体）。
 *
 * 惰性挂载：只在插件页视图激活时渲染；打开的贡献消失（插件被禁用/重载）时由
 * host.unloadPlugin 优雅关闭并回工作台，这里不再渲染。页面体包 PluginBoundary，
 * 渲染崩溃只塌本页（错误卡片上有「重试渲染」）。
 */
import { useMemo } from "react";
import { useApp } from "@/store";
import { usePlugins } from "@/app/plugins/state";
import { SLOT_NAV_PAGES } from "@/app/plugins/slots";
import { PluginBoundary } from "./PluginBoundary";
import type { PageContribution } from "@/app/plugins/types";

export function PluginPageHost() {
  const contributions = usePlugins((s) => s.contributions);
  const pageId = useApp((s) => s.pluginPageId);

  const pages = useMemo(
    () =>
      contributions
        .filter((c) => c.slot === SLOT_NAV_PAGES)
        .map((c) => c.contribution as PageContribution),
    [contributions],
  );
  const page = pages.find((p) => p.id === pageId);
  if (!page) return null;

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <PluginBoundary label={page.title}>{page.render()}</PluginBoundary>
    </div>
  );
}
