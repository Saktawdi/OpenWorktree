import { useSyncExternalStore } from "react";
import { Sparkle } from "@phosphor-icons/react";
import { assistantStore } from "./state";

export function TopBarCapsule() {
  const state = useSyncExternalStore(assistantStore.subscribe, assistantStore.get);

  return (
    <button
      className={`llm-capsule-btn ${state.open ? "active" : ""}`}
      onClick={() => assistantStore.toggleOpen()}
      title="LLM 小助手 (点击唤起/隐藏)"
    >
      <Sparkle
        size={14}
        weight="fill"
        className={state.open ? "text-accent" : "text-faint"}
      />
      <span>LLM 助手</span>
    </button>
  );
}
