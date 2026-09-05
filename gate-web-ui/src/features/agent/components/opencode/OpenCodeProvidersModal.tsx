import { useEffect, useState } from "react";
import { ArrowClockwise, CircleNotch, Lightning, PencilSimple, Plug, Plus, Trash, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { testOcModelLive } from "@/features/agent";
import type { OpenCodeProvider } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";
import { normalizeModelEntries } from "./presets";
import { OcProviderPanel } from "./OcProviderPanel";

/** 供应商管理全屏弹窗：从 OpenCode 运行时卡片的入口进入。 */
export function OpenCodeProvidersModal({ onClose }: { onClose: () => void }) {
  const ocProviders = useApp((s) => s.ocProviders);
  const ocConfigPath = useApp((s) => s.ocConfigPath);
  const mode = useApp((s) => s.mode);
  const backdrop = useBackdropClose(onClose);
  const [dialog, setDialog] = useState<{ open: boolean; provider: OpenCodeProvider | null }>({
    open: false,
    provider: null,
  });
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [testing, setTesting] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ key: string; ok: boolean; text: string } | null>(null);

  useEffect(() => {
    actions.loadOcProviders();
  }, []);

  const testProvider = async (p: OpenCodeProvider) => {
    const model = normalizeModelEntries(p.models)[0]?.id;
    if (!model || !p.baseURL) {
      setTestResult({ key: p.key, ok: false, text: "缺少 Base URL 或模型，无法测试" });
      return;
    }
    setTesting(p.key);
    setTestResult(null);
    try {
      const r = await testOcModelLive(p.baseURL, p.apiKey ?? "", model);
      setTestResult({
        key: p.key,
        ok: r.ok,
        text: r.ok ? `${model} · ${r.latency_ms}ms${r.reply ? ` · ${r.reply}` : ""}` : `${model} · ${r.error ?? `HTTP ${r.status_code}`}`,
      });
    } catch (e) {
      setTestResult({ key: p.key, ok: false, text: (e as Error).message });
    } finally {
      setTesting(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      {/* 编辑面板打开时整个弹窗加宽：因居中布局，列表自然左移，面板在右侧拼接。
          宽度用 CSS transition（motion 对 auto→px 的宽度插值不可靠），滑入用 motion。 */}
      <div
        className="relative flex card shadow-2xl shadow-black/60 animate-rise overflow-hidden transition-[width] duration-300 ease-out"
        style={{ width: dialog.open ? 1180 : 700, maxWidth: "96vw", height: 640 }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="w-[700px] max-w-full shrink-0 flex flex-col min-w-0">
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge shrink-0">
          <Plug size={15} className="text-accent" weight="fill" />
          <span className="text-[13.5px] font-semibold">OpenCode 供应商管理</span>
          {ocConfigPath && <span className="font-mono text-[11px] text-faint truncate">{ocConfigPath}</span>}
          <span className="flex-1" />
          <button className="btn h-8" onClick={() => actions.loadOcProviders()} title="重新读取配置文件">
            <ArrowClockwise size={13} />
            刷新
          </button>
          <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, provider: null })}>
            <Plus size={14} weight="bold" />
            新增供应商
          </button>
          <button className="icon-btn" title="关闭" aria-label="关闭" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="p-5 overflow-y-auto space-y-2 flex-1">
          {ocProviders.map((p) => (
            <div key={p.key} className="card p-4">
              <div className="flex items-center gap-2.5">
                <span className="w-7 h-7 rounded-lg bg-raised border border-edge grid place-items-center text-accent shrink-0">
                  <Plug size={14} />
                </span>
                <span className="text-[13.5px] font-semibold">{p.name}</span>
                <span className="chip border border-edge-strong bg-raised text-dim font-mono">{p.key}</span>
                {p.npm && <span className="chip border border-edge bg-canvas text-faint font-mono">{p.npm}</span>}
                <span className="flex-1" />
                {testResult?.key === p.key && (
                  <span className={`text-[11.5px] truncate max-w-[260px] ${testResult.ok ? "text-accent" : "text-danger"}`}>
                    {testResult.ok ? "✓ " : "✗ "}
                    {testResult.text}
                  </span>
                )}
                {confirmDelete === p.key ? (
                  <span className="flex items-center gap-1">
                    <button
                      className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer"
                      onClick={() => {
                        void actions.deleteOcProvider(p.key);
                        setConfirmDelete(null);
                      }}
                    >
                      确认删除
                    </button>
                    <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                      返回
                    </button>
                  </span>
                ) : (
                  <>
                    <button
                      className="icon-btn"
                      title="测试连通（第一个模型）"
                      aria-label="测试连通"
                      disabled={testing === p.key}
                      onClick={() => void testProvider(p)}
                    >
                      {testing === p.key ? <CircleNotch size={13} className="animate-spin" /> : <Lightning size={13} />}
                    </button>
                    <button
                      className="icon-btn"
                      title="编辑"
                      aria-label="编辑"
                      onClick={() => setDialog({ open: true, provider: p })}
                    >
                      <PencilSimple size={13} />
                    </button>
                    <button
                      className="icon-btn hover:!text-danger"
                      title="删除"
                      aria-label="删除"
                      onClick={() => setConfirmDelete(p.key)}
                    >
                      <Trash size={13} />
                    </button>
                  </>
                )}
              </div>
              <div className="mt-2.5 flex items-center gap-3 text-[11.5px] text-faint">
                {p.baseURL && (
                  <span className="font-mono truncate max-w-[380px]" title={p.baseURL}>
                    {p.baseURL}
                  </span>
                )}
                <span>
                  模型{" "}
                  <span className="font-mono text-dim">
                    {normalizeModelEntries(p.models).length > 0
                      ? normalizeModelEntries(p.models).map((m) => m.id).join(" · ")
                      : "—"}
                  </span>
                </span>
              </div>
            </div>
          ))}
          {ocProviders.length === 0 && (
            <div className="card border-dashed p-8 text-center">
              <div className="text-[13px] text-dim">
                {mode === "live"
                  ? "opencode.json 里还没有 provider，或文件尚未创建"
                  : "还没有供应商配置"}
              </div>
              <div className="mt-1 text-[12px] text-faint">新增一个 OpenCode 兼容供应商后，智能体即可选用它的模型</div>
            </div>
          )}
        </div>
        </div>

        {/* 编辑面板：右侧拼接；滑入用 CSS 关键帧（后台标签页也不受 rAF 节流影响） */}
        {dialog.open && (
          <div
            key={`provider-panel-${dialog.provider?.key ?? "new"}`}
            className="w-[480px] shrink-0 border-l border-edge bg-canvas animate-panel-in"
          >
            <OcProviderPanel initial={dialog.provider} onClose={() => setDialog({ open: false, provider: null })} />
          </div>
        )}
      </div>
    </div>
  );
}
