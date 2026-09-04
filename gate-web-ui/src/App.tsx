import { useEffect } from "react";
import { boot } from "./lib/actions";
import { applyThemeFromStorage, useApp } from "./lib/store";
import { TopBar } from "./components/TopBar";
import { Workbench } from "./components/Workbench";
import { KanbanBoard } from "./components/KanbanBoard";
import { ProjectsPage } from "./components/ProjectsPage";
import { RepoViewPage } from "./components/RepoView";
import { TerminalWorkbench } from "./components/ProjectTerminal";
import { AgentsPage } from "./components/AgentsPage";
import { SettingsPage } from "./components/SettingsPage";
import { ConnectionDialog } from "./components/ConnectionDialog";
import { StageChangeConfirmDialog } from "./components/StageChangeConfirmDialog";
import { Toast } from "./components/Toast";

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
      {view === "settings" && <SettingsPage />}
      <ConnectionDialog />
      <StageChangeConfirmDialog />
      <TerminalWorkbench />
      <Toast />
    </div>
  );
}
