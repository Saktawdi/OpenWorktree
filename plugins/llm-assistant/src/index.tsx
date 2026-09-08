/**
 * 通用 LLM 助手插件入口。
 * 包含：
 *  - 顶部右上角胶囊入口（header.actions 插槽）
 *  - 全局悬浮可拖拽 mini 对话面板（floating.widgets 插槽）
 *  - 划选文字菜单交互「询问小助手」（selection.menu 插槽，可在设置中开关）
 *  - 设置中心插件挂件（settings.plugins 插槽）
 */
import "./style.css";
import type { PluginContext } from "@gate/plugin-sdk";
import {
  assistantSettingsStore,
  assistantStore,
  ASSISTANT_SETTINGS_DEFAULTS,
  type AssistantSettings,
  type ChatEntry,
} from "./state";
import { TopBarCapsule } from "./TopBarCapsule";
import { FloatingChatPanel } from "./FloatingChatPanel";
import { AssistantSettingsPanel } from "./AssistantSettingsPanel";

export function activate(ctx: PluginContext) {
  const kv = ctx.kv;
  let selectionDisposer: (() => void) | null = null;

  // 1. 注册顶部右上角胶囊动作
  const disposeHeader = ctx.registerHeaderAction({
    id: "llm-assistant-capsule",
    order: 10,
    render: () => <TopBarCapsule />,
  });

  // 2. 注册全局悬浮挂件
  const disposeFloating = ctx.registerFloatingWidget({
    id: "llm-assistant-floating-panel",
    render: () => <FloatingChatPanel ctx={ctx} />,
  });

  // 3. 注册设置中心面板
  const disposeSettings = ctx.registerPanelWidget({
    id: "llm-assistant-settings",
    title: "LLM 助手设置",
    render: () => <AssistantSettingsPanel ctx={ctx} />,
  });

  // 4. 同步划选动作：仅当 settings 发生实质变更时重新注册，避免因拖拽/输入/流式消息频繁触发
  const syncSelection = (settings = assistantSettingsStore.get()) => {
    selectionDisposer?.();
    selectionDisposer = null;
    if (!settings.selectionAskEnabled) return;

    selectionDisposer = ctx.registerSelectionAction({
      id: "ask-llm-assistant",
      label: "询问小助手",
      icon: "Sparkle",
      run(_api, text) {
        const prompt = `${settings.selectionAskPrompt || ASSISTANT_SETTINGS_DEFAULTS.selectionAskPrompt}${text}`;
        assistantStore.askQuestion(prompt);
      },
    });
  };

  const unsubSettings = assistantSettingsStore.subscribe((newSettings) => {
    syncSelection(newSettings);
  });

  // 初始加载历史消息与设置
  const boot = async () => {
    if (kv) {
      try {
        const savedSettings = await kv.get<AssistantSettings>("assistant-settings");
        if (savedSettings) {
          assistantSettingsStore.set({ ...ASSISTANT_SETTINGS_DEFAULTS, ...savedSettings });
        }
        const history = await kv.get<ChatEntry[]>("chat-history");
        if (history && history.length > 0) {
          assistantStore.set({ messages: history });
        }
      } catch (e) {
        ctx.log("初始化历史记录或配置失败", e);
      }
    }
    syncSelection();
  };
  void boot();

  return () => {
    unsubSettings();
    disposeHeader();
    disposeFloating();
    disposeSettings();
    selectionDisposer?.();
  };
}
