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

export function AssistantSettingsBlock() {
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
          <span className="text-[13px] font-semibold">划选提问</span>
          <span className="flex-1" />
          <BoolSwitch
            value={settings.selectionAskEnabled}
            onChange={(v) => updateAssistantSettings({ selectionAskEnabled: v })}
          />
        </div>
        <div className="mt-2 text-[11.5px] text-faint leading-relaxed">
          开启后，在页面任意位置划选文字，快捷菜单将出现「询问小助手」——一键把选中内容送进悬浮对话面板。
        </div>
        {settings.selectionAskEnabled && (
          <label className="block mt-3 pt-3 border-t border-edge/60">
            <span className="field-label">提问前置提示词</span>
            <input
              className="text-input"
              value={settings.selectionAskPrompt}
              placeholder={ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}
              onChange={(e) => updateAssistantSettings({ selectionAskPrompt: e.target.value })}
            />
            <span className="mt-1 block text-[11px] text-faint">
              划选提问时拼接在选中文字之前的引导语，留空使用默认。
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
          <span className="text-[13px] font-semibold">采样温度</span>
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
          <span>0 · 严谨稳定</span>
          <span>1 · 发散创意</span>
        </div>
      </div>

      {/* 数据与能力说明 + 历史清理 */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <Info size={13} />
          </span>
          <span className="text-[13px] font-semibold">数据说明</span>
        </div>
        <ul className="mt-2.5 space-y-1.5 text-[11.5px] text-faint leading-relaxed list-disc pl-4">
          <li>对话使用的 Provider / 模型与插件系统 llm.chat 同源，统一在「LLM 设置」维护，密钥不落前端。</li>
          <li>对话历史、面板布局与以上偏好仅存于本机浏览器（localStorage），不上传后端。</li>
        </ul>
        <div className="mt-3 flex items-center gap-2">
          <button className="btn btn-sm" onClick={() => goSettingsTab("llm")}>
            前往 LLM 设置
          </button>
          <button
            className="btn btn-danger-ghost btn-sm"
            disabled={messageCount === 0}
            onClick={clearAssistantHistory}
          >
            清空对话历史{messageCount > 0 ? `（${messageCount} 条）` : ""}
          </button>
        </div>
      </div>
    </div>
  );
}
