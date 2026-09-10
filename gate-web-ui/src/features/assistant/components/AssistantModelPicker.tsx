/**
 * 小助手模型选择器（Composer 工具条同款）：composer-btn 触发 + 向上弹出分组
 * 列表（共享 GroupedModelMenu，与本体会话输入框的 ModelPicker 同一交互语言）。
 * 数据源是 LLM 设置中心的 /api/providers；未配置密钥的 Provider 置灰不可选。
 */
import { useState } from "react";
import { CaretDown, Cpu, WarningCircle } from "@phosphor-icons/react";
import { useApp } from "@/store";
import {
  goSettingsTab,
  loadAssistantProviders,
  selectAssistantProviders,
  setAssistantModel,
} from "@/features/assistant";
import { GroupedModelMenu } from "@/shared/components/GroupedModelMenu";
import { useT } from "@/i18n";

export function AssistantModelPicker() {
  const t = useT();
  const providers = useApp(selectAssistantProviders);
  const sel = useApp((s) => s.assistantModelSel);
  const [open, setOpen] = useState(false);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) void loadAssistantProviders(true);
  };

  const label = sel.model ? `${sel.providerId} · ${sel.model}` : providers.length === 0 ? t("asst.pickerNoModel") : t("asst.pickerPick");

  return (
    <div className="relative min-w-0">
      <button
        className="composer-btn max-w-[230px]"
        onClick={toggle}
        title={t("asst.pickerTip")}
      >
        <Cpu size={12} className="text-info shrink-0" weight="fill" />
        <span className="truncate font-mono text-[11px]">{label}</span>
        <CaretDown size={11} className={`shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[262px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
            {providers.length === 0 ? (
              <div className="px-2.5 py-4 text-center text-[12px] text-faint leading-relaxed">
                <WarningCircle size={16} className="mx-auto mb-1.5 text-warn" />
                {t("asst.pickerEmptyPrefix")}
                <button
                  className="text-accent underline underline-offset-2 cursor-pointer"
                  onClick={() => {
                    setOpen(false);
                    goSettingsTab("llm");
                  }}
                >
                  {t("assistant.settings.gotoLlm")}
                </button>
                {t("llm.newProvider")}
              </div>
            ) : (
              <GroupedModelMenu
                groups={providers.map((p) => ({
                  id: p.id,
                  name: p.name,
                  warning: p.credential_configured ? null : t("asst.pickerNoKey"),
                  models: p.models.map((m) => ({
                    id: m,
                    label: m,
                    active: sel.providerId === p.id && sel.model === m,
                    disabled: !p.credential_configured,
                  })),
                }))}
                onPick={(providerId, modelId) => {
                  setAssistantModel(providerId, modelId);
                  setOpen(false);
                }}
                emptyGroupText={t("asst.pickerEmptyGroup")}
              />
            )}
            <div className="divider my-1.5" />
            <button
              className="menu-action w-full"
              onClick={() => {
                setOpen(false);
                goSettingsTab("llm");
              }}
            >
              <Cpu size={11} className="opacity-60" />
              {t("asst.manageProviders")}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
