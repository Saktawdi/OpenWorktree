import { useEffect } from "react";
import { boot } from "@/app/boot";
import { applyThemeFromStorage, useApp } from "@/store";
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
import { Toast } from "@/shared/components/Toast";

export default function App() {
  const booted = useApp((s) => s.booted);
  const view = useApp((s) => s.view);

  useEffect(() => {
    applyThemeFromStorage();
    void boot();
  }, []);

  if (!booted) {
    return (
      <div className="h-full grid place-items-center">
        <div className="flex items-center gap-3 text-faint">
          <span className="w-2 h-2 rounded-full bg-accent animate-breathe" />
          <span className="text-[13px]">正在启动本地工作台…</span>
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
      <Toast />
    </div>
  );
}
