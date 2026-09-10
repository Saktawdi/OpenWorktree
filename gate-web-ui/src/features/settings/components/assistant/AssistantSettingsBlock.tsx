/**
 * 设置中心「LLM 助手」分区（T-109 原生化）：划选提问交互、采样参数与数据说明。
 * 状态读写走 features/assistant（localStorage 本地偏好），Provider/模型配置本体
 * 在「LLM 设置」分区，这里不重复建设。
 */
import { Info, Sparkle, Thermometer } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { clearAssistantHistory, goSettingsTab, updateAssistantSettings } from "@/features/assistant";
import { ASSISTANT_SETTINGS_DEFAULTS } from "@/store/prefs";
import { BoolSwitch } from "../toml/controls";
import { useT } from "@/i18n";

export function AssistantSettingsBlock() {
  const t = useT();
  const settings = useApp((s) => s.assistantSettings);
  const messageCount = useApp((s) => s.assistantMessages.length);

  return (
    <div className="space-y-4">
      {/* 划选提问交互 */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-accent-dim border-accent/30 text-accent">
            <Sparkle size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("assistant.settings.selectionAsk")}</span>
          <span className="flex-1" />
          <BoolSwitch
            value={settings.selectionAskEnabled}
            onChange={(v) => updateAssistantSettings({ selectionAskEnabled: v })}
          />
        </div>
        <div className="mt-2 text-[11.5px] text-faint leading-relaxed">
          {t("assistant.settings.selectionAskDesc")}
        </div>
        {settings.selectionAskEnabled && (
          <label className="block mt-3 pt-3 border-t border-edge/60">
            <span className="field-label">{t("assistant.settings.promptLabel")}</span>
            <input
              className="text-input"
              value={settings.selectionAskPrompt}
              placeholder={ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}
              onChange={(e) => updateAssistantSettings({ selectionAskPrompt: e.target.value })}
            />
            <span className="mt-1 block text-[11px] text-faint">
              {t("assistant.settings.promptHint")}
            </span>
          </label>
        )}
      </div>

      {/* 采样参数 */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <Thermometer size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("assistant.settings.temperature")}</span>
          <span className="flex-1" />
          <span className="font-mono text-[12px] text-accent">{settings.temperature.toFixed(1)}</span>
        </div>
        <input
          type="range"
          min={0}
          max={1}
          step={0.1}
          value={settings.temperature}
          onChange={(e) => updateAssistantSettings({ temperature: parseFloat(e.target.value) })}
          className="mt-3 w-full accent-accent cursor-pointer"
        />
        <div className="mt-1 flex justify-between text-[10.5px] text-faint">
          <span>{t("assistant.settings.tempLow")}</span>
          <span>{t("assistant.settings.tempHigh")}</span>
        </div>
      </div>

      {/* 数据与能力说明 + 历史清理 */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <Info size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("assistant.settings.dataNote")}</span>
        </div>
        <ul className="mt-2.5 space-y-1.5 text-[11.5px] text-faint leading-relaxed list-disc pl-4">
          <li>{t("assistant.settings.dataLine1")}</li>
          <li>{t("assistant.settings.dataLine2")}</li>
        </ul>
        <div className="mt-3 flex items-center gap-2">
          <button className="btn btn-sm" onClick={() => goSettingsTab("llm")}>
            {t("assistant.settings.gotoLlm")}
          </button>
          <button
            className="btn btn-danger-ghost btn-sm"
            disabled={messageCount === 0}
            onClick={clearAssistantHistory}
          >
            {t("assistant.settings.clearHistory")}{messageCount > 0 ? t("assistant.settings.historyCount", { n: messageCount }) : ""}
          </button>
        </div>
      </div>
    </div>
  );
}
