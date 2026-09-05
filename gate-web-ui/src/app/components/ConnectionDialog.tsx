import { useState } from "react";
import { X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, closeConnect, useApp } from "@/store";
import { useBackdropClose } from "@/shared/components/ui";

export function ConnectionDialog() {
  const open = useApp((s) => s.connectOpen);
  const mode = useApp((s) => s.mode);
  const backdrop = useBackdropClose(closeConnect);
  const [tab, setTab] = useState<"demo" | "live">(mode === "live" ? "live" : "demo");
  const [token, setToken] = useState(appStore.getState().token);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  if (!open) return null;

  const testAndConnect = async () => {
    if (!token.trim()) {
      setMsg({ ok: false, text: "请先粘贴访问令牌" });
      return;
    }
    setTesting(true);
    setMsg(null);
    const ok = await actions.connectLive(token.trim());
    setTesting(false);
    if (ok) {
      setMsg({ ok: true, text: "连接成功，已加载后端数据" });
      setTimeout(() => closeConnect(), 700);
    } else {
      setMsg({ ok: false, text: "令牌无效或后端未启动（18080 端口）" });
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      <div
        className="w-[440px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 h-12 border-b border-edge">
          <span className="text-[13.5px] font-semibold">连接设置</span>
          <button className="icon-btn" onClick={closeConnect} aria-label="关闭">
            <X size={15} />
          </button>
        </div>

        <div className="p-5 space-y-4">
          <div className="flex gap-1 bg-sunken rounded-lg p-0.5 border border-edge">
            {(
              [
                ["demo", "演示模式"],
                ["live", "连接本地后端"],
              ] as const
            ).map(([k, label]) => (
              <button
                key={k}
                className={`flex-1 h-8 rounded-md text-[12.5px] font-medium cursor-pointer transition-colors ${
                  tab === k ? "bg-raised text-ink border border-edge" : "text-dim hover:text-ink border border-transparent"
                }`}
                onClick={() => {
                  setTab(k);
                  setMsg(null);
                }}
              >
                {label}
              </button>
            ))}
          </div>

          {tab === "demo" ? (
            <>
              <p className="text-[12.5px] leading-relaxed text-dim">
                内置完整演示场景：Agent 在沙箱内编码 → 预提审锁定快照 → 门禁审查驳回 → 按意见修复 → 二轮通过 → 一键发布。
                全程无需启动后端。
              </p>
              <button
                className="btn btn-primary w-full"
                onClick={() => {
                  actions.useDemo();
                  closeConnect();
                }}
              >
                载入演示场景
              </button>
            </>
          ) : (
            <>
              <div>
                <label className="field-label">后端地址</label>
                <input className="text-input font-mono" value="http://127.0.0.1:18080（经开发代理转发）" readOnly />
              </div>
              <div>
                <label className="field-label">访问令牌</label>
                <input
                  type="password"
                  className="text-input font-mono"
                  placeholder="GATE_WEB_TOKEN"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && testAndConnect()}
                />
                <p className="mt-1.5 text-[11.5px] text-faint leading-relaxed">
                  令牌见启动日志中的 GATE_WEB_TOKEN，或 local-run/gate-home/web-token 文件。
                </p>
              </div>
              {msg && (
                <div className={`text-[12.5px] ${msg.ok ? "text-accent" : "text-danger"}`}>{msg.text}</div>
              )}
              <div className="flex gap-2">
                <button className="btn flex-1" disabled={testing} onClick={testAndConnect}>
                  {testing ? "测试中…" : "测试并连接"}
                </button>
                <button
                  className="btn"
                  onClick={() => {
                    actions.useDemo();
                    closeConnect();
                  }}
                >
                  返回演示
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
