/**
 * 空白模板插件入口：演示三类贡献点 + 生命周期清理。
 * 复制本目录后：改 manifest.json 的 id/name → 按需增删贡献点 → npm run deploy。
 */
import "./styles.css";
import type { PluginContext } from "@gate/plugin-sdk";
import { HelloPage, HelloPanel } from "./panel";

export function activate(ctx: PluginContext) {
  ctx.log("模板插件已激活：", ctx.manifest.id, ctx.manifest.version);

  // 贡献点 1：对话输入区上方的快捷 chip。
  // 「插入问候」演示 insertText（插进光标处）；「运行本地单测」演示 sendPrompt（直接发送）
  // ——原宿主原生 chip「运行本地单测」自 round 2 起移出宿主，作为插件示例在此提供。
  const disposeAction = ctx.registerChatInputAction({
    id: "insert-hello",
    label: "插入问候",
    icon: "ChatText",
    run: (api) => api.insertText("请先阅读工单描述与验收标准，再开始动手。"),
  });
  const disposeUnitTestChip = ctx.registerChatInputAction({
    id: "run-unit-tests",
    label: "运行本地单测",
    icon: "TerminalWindow",
    when: (state) => !state.terminal,
    run: (api) => api.sendPrompt("运行本地单元测试并汇总结果"),
  });

  // 贡献点 2：设置中心「插件」分区的管理面板（React 组件，宿主样式类可直接用）
  const disposePanel = ctx.registerPanelWidget({
    id: "hello-panel",
    title: "模板示例面板",
    render: () => <HelloPanel />,
  });

  // 贡献点 3：顶栏导航的整页视图（order 越小越靠前；禁用插件时宿主自动关闭该页）
  const disposePage = ctx.registerPage({
    id: "hello-page",
    title: "示例页",
    icon: "Rocket",
    order: 100,
    render: () => <HelloPage />,
  });

  // 停用回调（宿主 disable/reload 时逆序执行；registerXxx 返回的 Disposable 也会被调用）
  ctx.onDeactivate(() => ctx.log("模板插件已停用"));

  // 贡献点 4（行为型）：订阅宿主事件总线（no replay——订阅晚于 emit 即错过）。
  ctx.on("ticket.stage-changed", (p) =>
    ctx.log(`工单 ${p.ticketNo} 阶段迁移：${p.from} → ${p.to}`),
  );
  ctx.on("session.ended", (p) => ctx.log(`会话回合结束（${p.kind}）：`, p.sessionId));

  // 返回统一清理函数（也可以只依赖 onDeactivate / 各 register 返回值，三者等价）
  return () => {
    disposeAction();
    disposeUnitTestChip();
    disposePanel();
    disposePage();
  };
}
