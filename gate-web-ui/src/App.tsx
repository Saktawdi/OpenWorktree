import { useEffect } from "react";
import { boot } from "./lib/actions";
import { useApp } from "./lib/store";
import { TopBar } from "./components/TopBar";
import { Workbench } from "./components/Workbench";
import { KanbanBoard } from "./components/KanbanBoard";
import { ConnectionDialog } from "./components/ConnectionDialog";
import { Toast } from "./components/Toast";

export default function App() {
  const booted = useApp((s) => s.booted);
  const view = useApp((s) => s.view);

  useEffect(() => {
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
      {view === "workbench" ? <Workbench /> : <KanbanBoard />}
      <ConnectionDialog />
      <Toast />
    </div>
  );
}
