import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import {
  CornersOut,
  Minus,
  PaperPlaneTilt,
  Sparkle,
  Trash,
  X,
} from "@phosphor-icons/react";
import type { PluginContext } from "@gate/plugin-sdk";
import { assistantStore, type ChatEntry } from "./state";

interface ProviderSummary {
  id: string;
  name: string;
  models: string[];
  credential_configured: boolean;
}

export function FloatingChatPanel({ ctx }: { ctx: PluginContext }) {
  const state = useSyncExternalStore(assistantStore.subscribe, assistantStore.get);
  const [providers, setProviders] = useState<ProviderSummary[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 加载 Provider 列表
  useEffect(() => {
    void (async () => {
      try {
        const res = await ctx.hostFetch<{ providers: ProviderSummary[] }>("/api/providers");
        const list = res.providers ?? [];
        setProviders(list);

        // 如果未选择 Provider，默认选第一个已配置密钥的
        if (!state.selectedProviderId) {
          const firstConfigured = list.find((p) => p.credential_configured && p.models.length > 0);
          const chosen = firstConfigured ?? list[0];
          if (chosen) {
            assistantStore.set({
              selectedProviderId: chosen.id,
              selectedModel: chosen.models[0] ?? "",
            });
          }
        }
      } catch (e) {
        ctx.log("加载 providers 失败", e);
      }
    })();
  }, [ctx]);

  // 消息滚动触底
  useEffect(() => {
    if (state.open) {
      messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [state.messages, state.streamingReply, state.open]);

  // 拖拽逻辑
  const onMouseDownHeader = (e: React.MouseEvent) => {
    if ((e.target as HTMLElement).closest("button, select")) return;
    setIsDragging(true);
    setDragOffset({
      x: e.clientX - state.position.x,
      y: e.clientY - state.position.y,
    });
  };

  useEffect(() => {
    if (!isDragging) return;

    const onMouseMove = (e: MouseEvent) => {
      const maxX = Math.max(0, window.innerWidth - 390);
      const maxY = Math.max(0, window.innerHeight - 80);
      const newX = Math.min(Math.max(10, e.clientX - dragOffset.x), maxX);
      const newY = Math.min(Math.max(10, e.clientY - dragOffset.y), maxY);
      assistantStore.set({ position: { x: newX, y: newY } });
    };

    const onMouseUp = () => {
      setIsDragging(false);
    };

    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
    return () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
    };
  }, [isDragging, dragOffset]);

  const handleSend = async () => {
    const text = state.input.trim();
    if (!text || state.loading) return;

    const userEntry: ChatEntry = {
      id: `user-${Date.now()}`,
      role: "user",
      content: text,
      timestamp: Date.now(),
    };

    const newMessages = [...state.messages, userEntry];
    assistantStore.set({
      messages: newMessages,
      input: "",
      loading: true,
      streamingReply: "",
    });

    try {
      if (!ctx.llm) {
        throw new Error("当前环境未注入 llm 权限，请在 manifest.json 中添加 'llm' 权限");
      }

      const reqMessages = newMessages.map((m) => ({
        role: m.role,
        content: m.content,
      }));

      let accumulated = "";
      await ctx.llm.chatStream(
        {
          messages: reqMessages,
          providerId: state.selectedProviderId || undefined,
          model: state.selectedModel || undefined,
          temperature: state.settings.temperature,
        },
        (chunk) => {
          accumulated += chunk;
          assistantStore.set({ streamingReply: accumulated });
        },
      );

      const assistantEntry: ChatEntry = {
        id: `asst-${Date.now()}`,
        role: "assistant",
        content: accumulated,
        timestamp: Date.now(),
      };

      assistantStore.set({
        messages: [...newMessages, assistantEntry],
        loading: false,
        streamingReply: "",
      });

      // 持久化到 KV
      if (ctx.kv) {
        void ctx.kv.set("chat-history", [...newMessages, assistantEntry]);
      }
    } catch (e) {
      const errEntry: ChatEntry = {
        id: `err-${Date.now()}`,
        role: "assistant",
        content: `抱歉，请求出错：${(e as Error).message}`,
        timestamp: Date.now(),
      };
      assistantStore.set({
        messages: [...newMessages, errEntry],
        loading: false,
        streamingReply: "",
      });
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void handleSend();
    }
  };

  const clearHistory = () => {
    const initial: ChatEntry[] = [
      {
        id: "welcome",
        role: "assistant",
        content: "会话已清空。有什么可以帮您的？",
        timestamp: Date.now(),
      },
    ];
    assistantStore.set({ messages: initial });
    if (ctx.kv) {
      void ctx.kv.set("chat-history", initial);
    }
  };

  if (!state.open) return null;

  return (
    <div
      className={`llm-float-panel ${isDragging ? "dragging" : ""}`}
      style={{
        left: `${state.position.x}px`,
        top: `${state.position.y}px`,
        height: state.minimized ? "44px" : "520px",
      }}
    >
      {/* 拖拽顶栏 */}
      <div className="llm-drag-header" onMouseDown={onMouseDownHeader}>
        <div className="flex items-center gap-1.5 min-w-0 flex-1">
          <Sparkle size={15} weight="fill" className="text-accent shrink-0" />
          <span className="text-[13px] font-semibold text-ink truncate">LLM 小助手</span>
        </div>

        {/* 模型选择：value 使用 "providerId:::model" 复合键，彻底杜绝多 Provider 包含同名模型时的串台错配 */}
        {!state.minimized && providers.length > 0 && (
          <select
            className="text-[11px] bg-sunken border border-edge rounded px-1.5 py-0.5 max-w-[130px] truncate text-dim focus:outline-none"
            value={`${state.selectedProviderId}:::${state.selectedModel}`}
            onChange={(e) => {
              const val = e.target.value;
              const sep = val.indexOf(":::");
              if (sep !== -1) {
                const providerId = val.slice(0, sep);
                const model = val.slice(sep + 3);
                assistantStore.set({
                  selectedProviderId: providerId,
                  selectedModel: model,
                });
              }
            }}
          >
            {providers.map((p) => (
              <optgroup key={p.id} label={`${p.name}${p.credential_configured ? "" : " (未配置密钥)"}`}>
                {p.models.map((m) => (
                  <option key={`${p.id}:::${m}`} value={`${p.id}:::${m}`}>
                    {m}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        )}

        <div className="flex items-center gap-1 shrink-0">
          {!state.minimized && (
            <button className="icon-btn" title="清空对话" onClick={clearHistory}>
              <Trash size={13} />
            </button>
          )}
          <button
            className="icon-btn"
            title={state.minimized ? "展开" : "最小化"}
            onClick={() => assistantStore.set({ minimized: !state.minimized })}
          >
            {state.minimized ? <CornersOut size={13} /> : <Minus size={13} />}
          </button>
          <button
            className="icon-btn hover:text-danger"
            title="关闭"
            onClick={() => assistantStore.setOpen(false)}
          >
            <X size={13} />
          </button>
        </div>
      </div>

      {/* 消息体与输入区 */}
      {!state.minimized && (
        <div className="flex-1 flex flex-col min-h-0 bg-panel">
          <div className="flex-1 overflow-y-auto p-3 space-y-3">
            {state.messages.map((m) => (
              <div
                key={m.id}
                className={m.role === "user" ? "llm-msg-user" : "llm-msg-assistant"}
              >
                <div className="whitespace-pre-wrap">{m.content}</div>
              </div>
            ))}
            {state.streamingReply && (
              <div className="llm-msg-assistant">
                <div className="whitespace-pre-wrap">{state.streamingReply}</div>
              </div>
            )}
            {state.loading && !state.streamingReply && (
              <div className="llm-msg-assistant flex items-center gap-2 text-faint">
                <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />
                <span>思考中…</span>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* 输入框 */}
          <div className="p-2.5 border-t border-edge bg-surface/40 flex items-end gap-2">
            <textarea
              ref={inputRef}
              className="textarea flex-1 min-h-[38px] max-h-[120px] text-[12.5px] resize-none !py-2"
              placeholder="输入问题或需求… (Enter 发送, Shift+Enter 换行)"
              rows={1}
              value={state.input}
              onChange={(e) => assistantStore.set({ input: e.target.value })}
              onKeyDown={handleKeyDown}
            />
            <button
              className="btn btn-primary h-[38px] px-3 shrink-0"
              disabled={!state.input.trim() || state.loading}
              onClick={() => void handleSend()}
              title="发送"
            >
              <PaperPlaneTilt size={14} weight="bold" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
