/**
 * 插件系统（app/plugins）：一级「插件」管理页（view="plugins"）。
 *
 * 位于工作台/看板/项目/智能体同级；展示本地插件列表（启停/重载）、
 * 权限声明，以及各插件挂在 settings.plugins 槽位的管理面板（如快捷语录管理）。
 */
import { PuzzlePiece } from "@phosphor-icons/react";
import { openConnect, useApp } from "@/store";
import { PluginsBlock } from "./PluginsBlock";

export function PluginsPage() {
  const mode = useApp((s) => s.mode);

  if (mode === "demo") {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
        <div className="max-w-[880px] mx-auto px-6 py-10">
          <div className="card p-10 text-center">
            <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
              <PuzzlePiece size={22} className="text-faint" />
            </div>
            <div className="mt-4 text-[15px] font-semibold">插件管理需要连接后端</div>
            <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed">
              当前为演示模式，本地插件目录扫描与运行时挂载仅在连接后端后可用
            </div>
            <button className="btn btn-primary mt-5" onClick={openConnect}>
              连接后端
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
      <div className="max-w-[1080px] mx-auto px-6 py-5">
        <PluginsBlock />
      </div>
    </div>
  );
}
