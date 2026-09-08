export interface AssistantSettings {
  selectionAskEnabled: boolean;
  selectionAskPrompt: string;
  defaultProviderId?: string;
  defaultModel?: string;
  temperature: number;
}

export const ASSISTANT_SETTINGS_DEFAULTS: AssistantSettings = {
  selectionAskEnabled: true,
  selectionAskPrompt: "请帮我解释、分析或回答这段文本：\n",
  temperature: 0.7,
};

type SettingsListener = (settings: AssistantSettings) => void;
const settingsListeners = new Set<SettingsListener>();

export const assistantSettingsStore = {
  get(): AssistantSettings {
    return state.settings;
  },
  set(settings: AssistantSettings) {
    state = { ...state, settings };
    settingsListeners.forEach((l) => l(settings));
    listeners.forEach((l) => l());
  },
  subscribe(l: SettingsListener): () => void {
    settingsListeners.add(l);
    return () => {
      settingsListeners.delete(l);
    };
  },
};

export interface ChatEntry {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  timestamp: number;
}

interface AssistantState {
  open: boolean;
  minimized: boolean;
  position: { x: number; y: number };
  messages: ChatEntry[];
  loading: boolean;
  streamingReply: string;
  selectedProviderId: string;
  selectedModel: string;
  input: string;
  settings: AssistantSettings;
}

type Listener = () => void;

let state: AssistantState = {
  open: false,
  minimized: false,
  position: { x: Math.max(16, window.innerWidth - 410), y: 64 },
  messages: [
    {
      id: "welcome",
      role: "assistant",
      content: "你好！我是通用 LLM 小助手。你可以向我咨询技术问题、请求优化建议，或在页面任意位置划选文字直接提问。",
      timestamp: Date.now(),
    },
  ],
  loading: false,
  streamingReply: "",
  selectedProviderId: "",
  selectedModel: "",
  input: "",
  settings: { ...ASSISTANT_SETTINGS_DEFAULTS },
};

const listeners = new Set<Listener>();

export const assistantStore = {
  get(): AssistantState {
    return state;
  },
  set(partial: Partial<AssistantState>) {
    state = { ...state, ...partial };
    listeners.forEach((l) => l());
  },
  subscribe(l: Listener): () => void {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
  toggleOpen() {
    state = { ...state, open: !state.open, minimized: false };
    listeners.forEach((l) => l());
  },
  setOpen(open: boolean) {
    state = { ...state, open, minimized: false };
    listeners.forEach((l) => l());
  },
  askQuestion(text: string) {
    state = {
      ...state,
      open: true,
      minimized: false,
      input: text,
    };
    listeners.forEach((l) => l());
  },
};
