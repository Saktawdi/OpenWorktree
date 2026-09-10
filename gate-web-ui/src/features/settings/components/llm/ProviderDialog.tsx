import { useState } from "react";
import { Robot, X } from "@phosphor-icons/react";
import { createProvider, setProviderCredential, updateProvider } from "@/features/settings";
import { showToast } from "@/store";
import type { LlmProvider } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";
import { useT } from "@/i18n";

/** LLM Provider 新建/编辑弹窗（API Key 直填走凭据接口，KMS 加密落库）。 */
export function ProviderDialog({ initial, onClose, onSaved }: { initial: LlmProvider | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
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
      showToast(isNew ? t("llm.providerCreated") : t("llm.providerUpdated"));
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
          <span className="text-[13.5px] font-semibold">{isNew ? t("llm.newProvider") : t("llm.editProvider")}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} aria-label={t("common.close")}><X size={15} /></button>
        </div>
        <div className="p-5 space-y-4">
          {isNew && (
            <div>
              <label className="field-label">ID *</label>
              <input className="text-input font-mono text-[12px]" placeholder={t("llm.idPlaceholder")} value={id} onChange={(e) => setId(e.target.value)} />
            </div>
          )}
          {!isNew && <div className="text-[11.5px] text-faint">{t("llm.idLabel")}：<span className="font-mono text-ink">{initial!.id}</span>{t("llm.idImmutable")}</div>}
          <div>
            <label className="field-label">{t("common.name")} *</label>
            <input className="text-input" placeholder={t("llm.namePlaceholder")} value={name} onChange={(e) => setName(e.target.value)} />
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
            <label className="field-label">API Key{initial?.credential_configured ? t("llm.keyConfiguredParens") : ""}</label>
            <input
              className="text-input font-mono text-[12px]"
              type="password"
              placeholder={initial?.credential_configured ? t("llm.keyKeepBlank") : "sk-…"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              autoComplete="off"
            />
            <div className="mt-1.5 text-[11px] text-faint leading-relaxed">
              {t("llm.keyEncryptedNote")}
              {initial?.credential_configured ? t("llm.keyOverwriteNote") : ""}
            </div>
          </div>
          {err && <div className="text-[12.5px] text-danger">{err}</div>}
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>{t("common.cancel")}</button>
          <button className="btn btn-primary" disabled={!valid || saving} onClick={() => void save()}>{saving ? t("llm.saving") : isNew ? t("llm.create") : t("llm.saveChanges")}</button>
        </div>
      </div>
    </div>
  );
}
