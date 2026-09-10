import { useEffect } from "react";
import { boot } from "@/app/boot";
import { applyThemeFromStorage, useApp } from "@/store";
import { applyLocaleFromStorage, useT } from "@/i18n";
import { TopBar } from "@/app/components/TopBar";
import { Workbench } from "@/app/components/Workbench";
import { KanbanBoard } from "@/features/ticket/components/KanbanBoard";
import { ProjectsPage } from "@/features/project/components/ProjectsPage";
import { RepoViewPage } from "@/features/project/components/RepoView";
import { TerminalWorkbench } from "@/features/project/components/ProjectTerminal";
import { AgentsPage } from "@/features/agent/components/AgentsPage";
import { SettingsPage } from "@/features/settings/components/SettingsPage";
import { PluginsPage } from "@/app/plugins/components/PluginsPage";
import { PluginPageHost } from "@/app/plugins/components/PluginPageHost";
import { ConnectionDialog } from "@/app/components/ConnectionDialog";
import { StageChangeConfirmDialog } from "@/features/ticket/components/StageChangeConfirmDialog";
import { SelectionQuoteLayer } from "@/features/session";
import { AssistantPanel } from "@/features/assistant";
import { OnboardingResume, OnboardingWizard } from "@/features/onboarding";
import { Toast } from "@/shared/components/Toast";
import { PluginSlot } from "@/app/plugins/components/PluginSlot";

export default function App() {
  const t = useT();
  const booted = useApp((s) => s.booted);
  const view = useApp((s) => s.view);

  useEffect(() => {
    applyThemeFromStorage();
    applyLocaleFromStorage();
    void boot();
  }, []);

  if (!booted) {
    return (
      <div className="h-full grid place-items-center">
        <div className="flex items-center gap-3 text-faint">
          <span className="w-2 h-2 rounded-full bg-accent animate-breathe" />
          <span className="text-[13px]">{t("app.booting")}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col">
      <TopBar />
      {view === "workbench" && <Workbench />}
      {view === "kanban" && <KanbanBoard />}
      {view === "projects" && <ProjectsPage />}
      {view === "repo" && <RepoViewPage />}
      {view === "agents" && <AgentsPage />}
      {view === "plugins" && <PluginsPage />}
      {view === "settings" && <SettingsPage />}
      {view === "plugin-page" && <PluginPageHost />}
      <ConnectionDialog />
      <StageChangeConfirmDialog />
      <TerminalWorkbench />
      <SelectionQuoteLayer />
      {/* 首次启动新手引导（七步配置向导 + 中途收起的「继续引导」浮标） */}
      <OnboardingWizard />
      <OnboardingResume />
      {/* LLM 小助手悬浮面板（T-109 原生内置：跨视图常驻、可拖拽/缩放） */}
      <AssistantPanel />
      {/* 全局悬浮挂件区（floating.widgets：插件跨页面常驻挂件，如未来第三方浮窗） */}
      <PluginSlot name="floating.widgets" />
      <Toast />
    </div>
  );
}
