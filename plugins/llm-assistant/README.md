# LLM 助手插件 (gate-plugin-llm-assistant)

OpenWorktree 的通用 LLM 助手插件：
- **胶囊入口**：放置在顶栏右上角，胶囊状入口；
- **悬浮 Mini 对话框**：支持全视口自由拖拽移动、吸附限制、最小化折叠与关闭，支持流式对话；
- **LLM 配置联动**：直接复用设置中心 Providers 中的模型与密钥配置（后端 KMS 安全代理）；
- **划选文字交互**：在代码或会话区域划选文本后在浮动快捷气泡中点击「询问小助手」，自动唤起并填入问答；
- **设置面板**：在设置中心「插件」分区提供小助手配置项（划选提问开关、前置提示词、采样温度等）。

## 构建与部署

```bash
cd plugins/llm-assistant
npm install
npm run build
npm run deploy    # 构建并安装到 ~/.gate/plugins/llm-assistant/
```
