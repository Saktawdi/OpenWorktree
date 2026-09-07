/**
 * 插件系统（app/plugins）：顶栏「插件」一级页面的核心面板。
 * 每个插件一张卡：插件信息（启停/重载/错误展示）与其挂件管理面板（settings.plugins
 * 槽位）同卡分区呈现——面板是插件的一部分，不再是飘在列表后面的独立卡片。
 */
import { useEffect, useState } from "react";
import {
  ArrowClockwise,
  PuzzlePiece,
  Spinner,
  WarningCircle,
} from "@phosphor-icons/react";
import { refreshPlugins, reloadPluginById, togglePlugin } from "@/app/plugins/host";
import { usePlugins } from "@/app/plugins/state";
import { PluginSlot } from "./PluginSlot";
import type { PanelWidgetContribution } from "@/app/plugins/types";
import type { PluginView } from "@/app/plugins/state";

/** 启停开关（原生语义 role=switch，样式与 LlmBlock 控件一致）。 */
function EnabledSwitch({ on, onToggle, disabled }: { on: boolean; onToggle: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      disabled={disabled}
      onClick={onToggle}
      className={`relative w-8 h-[18px] rounded-full transition-colors cursor-pointer shrink-0 ${
        on ? "bg-accent" : "bg-raised border border-edge-strong"
      } ${disabled ? "opacity-40 pointer-events-none" : ""}`}
      title={on ? "点击禁用插件（贡献点随即移除）" : "点击启用插件"}
    >
      <span
        className={`absolute top-[1.5px] size-[15px] rounded-full transition-all duration-150 ${
          on ? "left-[16px] bg-sunken" : "left-[1.5px] bg-faint"
        }`}
      />
    </button>
  );
}

function statusView(p: PluginView): { label: string; cls: string } {
  if (p.status === "active") return { label: "运行中", cls: "text-accent border-accent/30 bg-accent/10" };
  if (p.status === "error") return { label: "激活失败", cls: "text-danger border-danger/30 bg-danger/10" };
  return { label: "已停用", cls: "text-faint border-edge-strong bg-raised" };
}

function PluginCard({ plugin }: { plugin: PluginView }) {
  const [busy, setBusy] = useState(false);
  const status = statusView(plugin);
  const enabled = plugin.status !== "off";

  const withBusy = (fn: () => Promise<void>) => async () => {
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      console.warn("[plugins] 操作失败", e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3.5 flex items-start gap-3">
        <span className="w-8 h-8 rounded-lg bg-raised border border-edge grid place-items-center shrink-0">
          <PuzzlePiece size={15} className={enabled ? "text-accent" : "text-faint"} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-[13px] font-semibold ${enabled ? "text-ink" : "text-dim"}`}>{plugin.name}</span>
            <span className="chip border border-edge-strong bg-sunken text-faint font-mono text-[10.5px]">
              v{plugin.version}
            </span>
            <span className={`chip border ${status.cls}`}>{status.label}</span>
            <span className="flex-1" />
            <button
              className="icon-btn"
              title="重载：停用后以最新产物重新加载（改了插件 dist 后点这里）"
              aria-label={`重载插件 ${plugin.name}`}
              disabled={busy}
              onClick={withBusy(() => reloadPluginById(plugin.id))}
            >
              <ArrowClockwise size={14} />
            </button>
            <EnabledSwitch
              on={enabled}
              disabled={busy}
              onToggle={withBusy(() => togglePlugin(plugin.id, !enabled))}
            />
          </div>
          {plugin.description && (
            <div className="mt-1 text-[12px] text-dim leading-relaxed">{plugin.description}</div>
          )}
          <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
            <span className="font-mono text-[10.5px] text-faint">{plugin.id}</span>
            {plugin.permissions.map((perm) => (
              <span key={perm} className="chip border border-info/30 bg-info/10 text-info font-mono text-[10.5px]">
                {perm}
              </span>
            ))}
          </div>
          {plugin.error && (
            <div className="mt-2 rounded-lg border border-danger/30 bg-danger-dim/30 px-2.5 py-2 text-[11.5px] text-danger break-all">
              {plugin.error}
            </div>
          )}
        </div>
      </div>
      {/* 该插件的管理面板：与插件信息同卡，分区线上方是插件本体，下方是它的设置区。
          插件停用/重载期间贡献点被移除，此区自动消失，卡片收合成纯信息条。 */}
      <PluginSlot
        name="settings.plugins"
        pluginId={plugin.id}
        wrap={(node, { contribution }) => {
          const widget = contribution as PanelWidgetContribution;
          return (
            <div className="border-t border-edge px-4 py-3.5">
              <div className="flex items-center gap-1.5 mb-3">
                <PuzzlePiece size={11} className="text-faint shrink-0" />
                <span className="text-[11px] font-medium text-faint">{widget.title ?? plugin.id}</span>
              </div>
              {node}
            </div>
          );
        }}
      />
    </div>
  );
}

export function PluginsBlock() {
  const plugins = usePlugins((s) => s.plugins);
  const loaded = usePlugins((s) => s.loaded);
  const listError = usePlugins((s) => s.listError);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    void refreshPlugins();
  }, []);

  const refresh = async () => {
    setRefreshing(true);
    try {
      await refreshPlugins();
    } finally {
      setRefreshing(false);
    }
  };

  if (!loaded) {
    return (
      <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint">
        <Spinner />
        {listError ?? "正在读取插件目录 …"}
        {listError && (
          <button className="btn btn-sm ml-2" onClick={() => void refresh()}>
            重试
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* 分节头：不占卡片——插件卡才是页面主角，头部只提供目录说明与统计/刷新 */}
      <div className="flex items-center gap-2.5 px-1 pt-1">
        <span className="w-9 h-9 rounded-lg bg-accent-dim border border-accent/30 grid place-items-center shrink-0">
          <PuzzlePiece size={16} className="text-accent" />
        </span>
        <div className="min-w-0">
          <div className="text-[13.5px] font-semibold">本地插件</div>
          <div className="text-[11.5px] text-faint">
            将插件目录拷入 <code className="font-mono">gate-home/plugins/</code> 后刷新即可发现
          </div>
        </div>
        <span className="flex-1" />
        <span className="text-[11.5px] text-faint whitespace-nowrap">
          共 <span className="font-mono text-ink">{plugins.length}</span> 个 · 运行中{" "}
          <span className="font-mono text-accent">{plugins.filter((p) => p.status === "active").length}</span>
        </span>
        <button className="btn btn-sm" disabled={refreshing} onClick={() => void refresh()}>
          {refreshing ? <Spinner /> : <ArrowClockwise size={13} />}刷新
        </button>
      </div>

      {listError && (
        <div className="card px-4 py-3 flex items-center gap-2 text-[12.5px] text-danger">
          <WarningCircle size={14} weight="fill" />
          {listError}
        </div>
      )}

      {plugins.length === 0 ? (
        <div className="card p-10 text-center">
          <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
            <PuzzlePiece size={22} className="text-faint" />
          </div>
          <div className="mt-4 text-[15px] font-semibold">暂无本地插件</div>
          <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed max-w-[420px] mx-auto">
            复制仓库根的 <code className="font-mono">plugin-template/</code> 为起点开发插件，
            构建后把整个目录拷入 <code className="font-mono">gate-home/plugins/</code>，回到本页刷新即可加载
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {plugins.map((p) => (
            <PluginCard key={p.id} plugin={p} />
          ))}
        </div>
      )}
    </div>
  );
}
