/**
 * 域十：新手引导（首次启动的七步配置向导）。
 */
export const zhOnboarding = {
  "onboarding.title": "欢迎使用 OpenWorktree",
  "onboarding.subtitle": "七步完成初始配置，随时可跳过",
  "onboarding.skip": "跳过引导",
  "onboarding.skipTip": "一键跳过（可从 设置 → 偏好 重新查看）",
  "onboarding.prev": "上一步",
  "onboarding.next": "下一步",
  "onboarding.finish": "完成，开始使用",
  "onboarding.progress": "第 {n} / {total} 步",
  "onboarding.resume": "继续引导 ({n}/{total})",
  "onboarding.resumeTip": "回到新手引导，从上次的步骤继续",
  "onboarding.resumeDismiss": "不再提示",

  /* ─── 第 2 步：连接后端 ─── */
  "onboarding.step.connect.title": "连接后端",
  "onboarding.step.connect.desc":
    "OpenWorktree 是一个本地服务，浏览器访问需要登录令牌：令牌在启动日志的 GATE_WEB_TOKEN= 行（Docker 部署：docker compose logs openworktree | grep GATE_WEB_TOKEN；本地开发：local-run/gate-home/web-token 文件）。Windows 桌面版自动登录，本步直接跳过即可。若提示「后端拒绝访问」，是服务器部署的 Host 白名单问题：在 compose 环境变量加 OW_ALLOWED_ORIGINS=你的域名或 IP 后重启（详见 README 方式二）。",
  "onboarding.step.connect.tokenHint":
    "从启动日志的 GATE_WEB_TOKEN= 行复制整串，或打开数据目录下的 gate-home/web-token 文件",
  "onboarding.step.connect.connect": "连接",
  "onboarding.step.connect.connecting": "连接中…",
  "onboarding.step.connect.connected": "已连接后端",
  "onboarding.step.connect.notConnected": "未连接后端",
  "onboarding.step.connect.success": "连接成功，数据已加载",
  "onboarding.step.connect.error": "连接失败：令牌无效或后端不可达，请从日志重新复制后重试",

  /* ─── 第 1 步：语言偏好 ─── */
  "onboarding.step.lang.title": "界面语言",
  "onboarding.step.lang.desc":
    "先选好界面语言（点击即生效，全站文案随之切换）。后续每一步都用它展示说明；之后可在 设置 → 偏好 随时更改。",

  /* ─── 第 3 步：接入项目 ─── */
  "onboarding.step.project.title": "接入项目",
  "onboarding.step.project.desc":
    "项目 = 一个本地 Git 仓库工作区。接入后即可为它创建工单：每个工单在独立的克隆沙箱里开发，互不干扰，发布前全程可追溯。",
  "onboarding.step.project.action": "接入项目…",
  "onboarding.step.project.empty": "尚未接入项目",
  "onboarding.step.project.connected": "已接入 {n} 个项目",

  /* ─── 第 4 步：智能体设置 ─── */
  "onboarding.step.agent.title": "智能体设置",
  "onboarding.step.agent.desc":
    "智能体是替你写代码的协作方：配置它使用的 CLI 运行时（opencode / Claude Code）、模型与系统提示词，并可设置多个「员工」按工单切换。",
  "onboarding.step.agent.action": "前往智能体",

  /* ─── 第 5 步：LLM 设置 ─── */
  "onboarding.step.llm.title": "LLM 设置",
  "onboarding.step.llm.desc":
    "LLM 设置是 AI 审查所调用的上游模型服务：配置 Provider（网关地址 + 密钥）与模型清单，门禁的自动审查就用它判卷。",
  "onboarding.step.llm.note": "智能体用哪个 CLI 在「智能体」页配置；这里管的是审查引擎背后的模型服务。",
  "onboarding.step.llm.action": "前往 LLM 设置",

  /* ─── 第 6 步：看板 ─── */
  "onboarding.step.kanban.title": "看板",
  "onboarding.step.kanban.desc":
    "看板把工单按状态铺在六条泳道上：拖拽流转状态，一眼看清谁在开发、谁在审查、哪些已可发布。",
  "onboarding.step.kanban.action": "查看看板",

  /* ─── 第 7 步：工作台 ─── */
  "onboarding.step.workbench.title": "工作台",
  "onboarding.step.workbench.desc":
    "工作台是日常主战场：左侧工单列表，中间与智能体对话，右侧门禁面板查看预提审、审查发现与发布授权。到这里就可以开工了。",
  "onboarding.step.workbench.action": "进入工作台",

  /* ─── 设置 → 偏好里的重看入口 ─── */
  "prefs.onboarding.title": "新手引导",
  "prefs.onboarding.desc": "重看七步配置向导：连接后端、语言偏好、接入项目、智能体、LLM、看板与工作台。",
  "prefs.onboarding.replay": "重新查看",
} as const;
