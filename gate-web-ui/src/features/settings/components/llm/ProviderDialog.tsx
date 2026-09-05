import { useState } from "react";
import { Robot, X } from "@phosphor-icons/react";
import { createProvider, setProviderCredential, updateProvider } from "@/features/settings";
import { showToast } from "@/store";
import type { LlmProvider } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";

/** LLM Provider 新建/编辑弹窗（API Key 直填走凭据接口，KMS 加密落库）。 */
export function ProviderDialog({ initial, onClose, onSaved }: { initial: LlmProvider | null; onClose: () => void; onSaved: () => void }) {
  const isNew = !initial;
  const [id, setId] = useState(initial?.id ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(initial?.base_url ?? "");
  const [type, setType] = useState(initial?.type ?? "openai");
  const [apiKey, setApiKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const backdrop = useBackdropClose(onClose);

  const valid = id.trim() && name.trim() && baseUrl.trim() && type.trim();

  const save = async () => {
    if (!valid || saving) return;
    setSaving(true); setErr(null);
    try {
      const body: { id?: string; name: string; base_url: string; type: string } = {
        name: name.trim(), base_url: baseUrl.trim(), type: type.trim(),
      };
      let providerId = initial?.id;
      if (isNew) {
        body.id = id.trim();
        const created = await createProvider(body as { id: string; name: string; base_url: string; type: string });
        providerId = created?.id ?? id.trim();
      } else {
        await updateProvider(initial!.id, body as { name: string; base_url: string; type: string });
      }
      // 直填的 API Key 走凭据接口：KMS 加密落库，明文不进 provider 行的 api_key_ref。
      if (apiKey.trim() && providerId) {
        await setProviderCredential(providerId, apiKey.trim());
      }
      showToast(isNew ? "Provider 已创建" : "Provider 已更新");
      onSaved();
      onClose();
    } catch (e) { setErr((e as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      <div className="w-[480px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <Robot size={15} className="text-accent" />
          <span className="text-[13.5px] font-semibold">{isNew ? "新建 Provider" : "编辑 Provider"}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} aria-label="关闭"><X size={15} /></button>
        </div>
        <div className="p-5 space-y-4">
          {isNew && (
            <div>
              <label className="field-label">ID *</label>
              <input className="text-input font-mono text-[12px]" placeholder="例如：openai-main" value={id} onChange={(e) => setId(e.target.value)} />
            </div>
          )}
          {!isNew && <div className="text-[11.5px] text-faint">ID：<span className="font-mono text-ink">{initial!.id}</span>（不可修改）</div>}
          <div>
            <label className="field-label">名称 *</label>
            <input className="text-input" placeholder="例如：OpenAI 主用" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div>
            <label className="field-label">Base URL *</label>
            <input className="text-input font-mono text-[12px]" placeholder="https://api.openai.com/v1" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
          </div>
          <div>
            <label className="field-label">Type *</label>
            <input className="text-input font-mono text-[12px]" placeholder="openai / anthropic / custom" value={type} onChange={(e) => setType(e.target.value)} />
          </div>
          <div>
            <label className="field-label">API Key{initial?.credential_configured ? "（已配置）" : ""}</label>
            <input
              className="text-input font-mono text-[12px]"
              type="password"
              placeholder={initial?.credential_configured ? "已配置 · 留空保持不变" : "sk-…"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              autoComplete="off"
            />
            <div className="mt-1.5 text-[11px] text-faint leading-relaxed">
              加密存入本地库，审查引擎与模型拉取共用。
              {initial?.credential_configured ? " 再次输入将覆盖旧密钥。" : ""}
            </div>
          </div>
          {err && <div className="text-[12.5px] text-danger">{err}</div>}
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn btn-primary" disabled={!valid || saving} onClick={() => void save()}>{saving ? "保存中…" : isNew ? "创建" : "保存修改"}</button>
        </div>
      </div>
    </div>
  );
}
