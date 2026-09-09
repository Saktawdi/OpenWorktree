/**
 * 共享 React hooks（shared）：跨 feature 复用的行为原语。
 */
import { useEffect, useRef } from "react";

/**
 * 方向感知贴底滚动（本体会话流与小助手面板共用，语义与 ChatStream 原实现一致）：
 * 用户向上滚立即解除吸附（哪怕只滚出一格，流式更新不再把视口拽回底部），
 * 向下滚回贴底范围（距底 <120px）才恢复；流式期间高频更新用瞬时贴底
 * （smooth 动画被反复打断重启会表现为"滚不动"），空闲期平滑回底。
 *
 * streaming=true 的调用方传"生成中"标记；attachKey 供滚动容器随开合销毁/重建的
 * 调用方（如悬浮面板）在重新挂载后恢复监听与贴底。
 */
export function useStickyScroll(
  deps: readonly unknown[],
  streaming: boolean,
  attachKey?: unknown,
): { scrollRef: React.RefObject<HTMLDivElement | null>; bottomRef: React.RefObject<HTMLDivElement | null> } {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const lastTop = useRef(0);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    lastTop.current = el.scrollTop;
    const onScroll = () => {
      const goingUp = el.scrollTop < lastTop.current - 1;
      lastTop.current = el.scrollTop;
      if (goingUp) stick.current = false;
      else if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) stick.current = true;
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [attachKey]);

  useEffect(() => {
    if (!stick.current) return;
    if (streaming) {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    } else {
      bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
    }
    // 依赖数组由调用方声明（消息数组/生成中标记等），此处按约定透传
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, streaming, attachKey]);

  return { scrollRef, bottomRef };
}
