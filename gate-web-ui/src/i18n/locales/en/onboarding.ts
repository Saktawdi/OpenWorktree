/**
 * 英文域十：新手引导（首次启动的六步配置向导）。
 */
export const enOnboarding = {
  "onboarding.title": "Welcome to OpenWorktree",
  "onboarding.subtitle": "Six steps to get set up — skip anytime",
  "onboarding.skip": "Skip tour",
  "onboarding.skipTip": "Skip the whole tour (reopen it from Settings → Preferences)",
  "onboarding.prev": "Back",
  "onboarding.next": "Next",
  "onboarding.finish": "Finish and start",
  "onboarding.progress": "Step {n} / {total}",
  "onboarding.resume": "Resume tour ({n}/{total})",
  "onboarding.resumeTip": "Return to the tour at the step you left",
  "onboarding.resumeDismiss": "Don't show again",

  /* ─── Step 1: language ─── */
  "onboarding.step.lang.title": "Interface language",
  "onboarding.step.lang.desc":
    "Pick your interface language first — it applies immediately and the whole UI follows. Every later step is explained in it; change it anytime under Settings → Preferences.",

  /* ─── Step 2: connect a project ─── */
  "onboarding.step.project.title": "Connect a project",
  "onboarding.step.project.desc":
    "A project is a local Git repository workspace. Once connected you can file tickets against it: each ticket is developed in its own clone sandbox, isolated and fully traceable before release.",
  "onboarding.step.project.action": "Connect a project…",
  "onboarding.step.project.empty": "No project connected yet",
  "onboarding.step.project.connected": "{n} project(s) connected",

  /* ─── Step 3: agents ─── */
  "onboarding.step.agent.title": "Agent settings",
  "onboarding.step.agent.desc":
    "Agents are the coding collaborators: configure the CLI runtime they use (opencode / Claude Code), the model and the system prompt, and keep several profiles to switch per ticket.",
  "onboarding.step.agent.action": "Open agents",

  /* ─── Step 4: LLM settings ─── */
  "onboarding.step.llm.title": "LLM settings",
  "onboarding.step.llm.desc":
    "LLM settings hold the upstream model service that AI review calls: configure providers (gateway URL + credential) and their models — the gate's automated review grades with them.",
  "onboarding.step.llm.note": "Which CLI an agent runs is configured on the Agents page; this page is about the model service behind the review engine.",
  "onboarding.step.llm.action": "Open LLM settings",

  /* ─── Step 5: kanban ─── */
  "onboarding.step.kanban.title": "Kanban board",
  "onboarding.step.kanban.desc":
    "The board lays tickets across six lanes by stage: drag to move a ticket, and see at a glance who is coding, what is under review and what is ready to publish.",
  "onboarding.step.kanban.action": "View the board",

  /* ─── Step 6: workbench ─── */
  "onboarding.step.workbench.title": "Workbench",
  "onboarding.step.workbench.desc":
    "The workbench is your daily home: the ticket list on the left, the conversation with the agent in the middle, and the gate panel on the right for presubmit, review findings and publish authorization. You are ready to go.",
  "onboarding.step.workbench.action": "Open the workbench",

  /* ─── Replay entry under Settings → Preferences ─── */
  "prefs.onboarding.title": "Getting-started tour",
  "prefs.onboarding.desc": "Replay the six-step setup tour: language, projects, agents, LLM, board and workbench.",
  "prefs.onboarding.replay": "Replay",
} as const;
