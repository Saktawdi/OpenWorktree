/**
 * 空白模板插件入口：演示两类贡献点 + 生命周期清理。
 * 复制本目录后：改 manifest.json 的 id/name → 按需增删贡献点 → npm run deploy。
 */
import "./styles.css";
import type { PluginContext } from "./host-types";
import { HelloPanel } from "./panel";

export function activate(ctx: PluginContext) {
  ctx.log("模板插件已激活：", ctx.manifest.id, ctx.manifest.version);

  // 贡献点 1：对话输入区上方的快捷 chip（点击把文本插进输入框光标处）
  const disposeAction = ctx.registerChatInputAction({
    id: "insert-hello",
    label: "插入问候",
    icon: "ChatText",
    run: (api) => api.insertText("请先阅读工单描述与验收标准，再开始动手。"),
  });

  // 贡献点 2：设置中心「插件」分区的管理面板（React 组件，宿主样式类可直接用）
  const disposePanel = ctx.registerPanelWidget({
    id: "hello-panel",
    title: "模板示例面板",
    render: () => <HelloPanel />,
  });

  // 停用回调（宿主 disable/reload 时逆序执行；registerXxx 返回的 Disposable 也会被调用）
  ctx.onDeactivate(() => ctx.log("模板插件已停用"));

  // 返回统一清理函数（也可以只依赖 onDeactivate / 各 register 返回值，三者等价）
  return () => {
    disposeAction();
    disposePanel();
  };
}
