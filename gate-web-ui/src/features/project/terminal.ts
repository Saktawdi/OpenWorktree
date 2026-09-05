/**
 * 项目域终端接入（project）：WebSocket shell 连接。
 * 按服务端 JSON 信封收发（TerminalController）；心跳防 Jetty idle timeout。
 */
import { appStore } from "@/store";

/** 打开项目终端 WebSocket；返回的连接按 JSON 信封收发（服务端 TerminalController）。 */
export function openTerminalSocket(
  projectId: string,
  dir: string,
  handlers: {
    onData: (text: string) => void;
    onExit: (code: number) => void;
    onError: (message: string) => void;
    onStarted: () => void;
  },
): WebSocket {
  const token = appStore.getState().token;
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${window.location.host}/ws/terminal`);
  ws.onopen = () => {
    // 心跳：终端静置时无流量，Jetty 的 WebSocket idle timeout（30s）会掐掉连接
    const beat = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ op: "ping" }));
      } else {
        clearInterval(beat);
      }
    }, 15000);
    ws.addEventListener("close", () => clearInterval(beat));
    try {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ op: "start", token, project: projectId, dir }));
      }
    } catch {
      /* 连接在启动帧前即被关闭 */
    }
  };
  ws.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data as string) as {
        op: string;
        data?: string;
        code?: number;
        message?: string;
      };
      if (msg.op === "data") handlers.onData(msg.data ?? "");
      else if (msg.op === "exit") handlers.onExit(msg.code ?? -1);
      else if (msg.op === "error") handlers.onError(msg.message ?? "未知错误");
      else if (msg.op === "started") handlers.onStarted();
    } catch {
      /* 忽略无法解析的帧 */
    }
  };
  return ws;
}
