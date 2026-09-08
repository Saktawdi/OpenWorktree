import { useSyncExternalStore } from "react";
import type { PluginContext } from "@gate/plugin-sdk";
import { assistantSettingsStore, ASSISTANT_SETTINGS_DEFAULTS } from "./state";

export function AssistantSettingsPanel({ ctx }: { ctx: PluginContext }) {
  const settings = useSyncExternalStore(assistantSettingsStore.subscribe, assistantSettingsStore.get);

  const update = (partial: Partial<typeof settings>) => {
    const next = { ...settings, ...partial };
    assistantSettingsStore.set(next);
    if (ctx.kv) {
      void ctx.kv.set("assistant-settings", next);
    }
  };

  return (
    <div className="space-y-4 text-ink">
      <div className="rounded-xl border border-edge bg-raised/40 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-[13.5px] font-semibold text-ink">划选交互：「询问小助手」</div>
            <div className="text-[12px] text-faint mt-0.5">
              在网页划选文本时，是否在右键/划选快捷气泡菜单中显示「询问小助手」
            </div>
          </div>
          <label className="relative inline-flex items-center cursor-pointer">
            <input
              type="checkbox"
              className="sr-only peer"
              checked={settings.selectionAskEnabled}
              onChange={(e) => update({ selectionAskEnabled: e.target.checked })}
            />
            <div className="w-9 h-5 bg-edge-strong peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-accent" />
          </label>
        </div>

        {settings.selectionAskEnabled && (
          <div className="pt-2 border-t border-edge/60">
            <label className="block">
              <span className="field-label">提问前置提示词</span>
              <input
                className="text-input text-[12px]"
                value={settings.selectionAskPrompt}
                placeholder={ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}
                onChange={(e) => update({ selectionAskPrompt: e.target.value })}
              />
            </label>
          </div>
        )}
      </div>

      <div className="rounded-xl border border-edge bg-raised/40 p-4 space-y-3">
        <div>
          <div className="text-[13.5px] font-semibold text-ink">模型调用参数</div>
          <div className="text-[12px] text-faint mt-0.5">
            控制 LLM 生成的随机性与采样温度
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[12px] text-dim">Temperature:</span>
          <input
            type="range"
            min="0"
            max="1"
            step="0.1"
            value={settings.temperature}
            onChange={(e) => update({ temperature: parseFloat(e.target.value) })}
            className="flex-1 accent-accent"
          />
          <span className="font-mono text-[12px] text-accent w-8">{settings.temperature}</span>
        </div>
      </div>
    </div>
  );
}
