import { useEffect, useState } from "react";
import {
  ArrowUp,
  CaretRight,
  FolderIcon,
  FolderOpen,
  FolderPlus,
  GitBranch,
  X,
} from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import type { WorkspaceListing } from "@/shared/types";
import { Spinner, useBackdropClose } from "@/shared/components/ui";

/**
 * 本地目录浏览器（后端 /api/workspaces 驱动）— 为「接入新项目」表单选择工作区绝对路径。
 * 单击选中子目录，双击进入；顶部地址栏可直接粘贴/编辑绝对路径回车跳转；
 * 支持在当前目录下新建文件夹（后端 /api/workspaces/mkdir，单层创建）。
 */
export function WorkspaceBrowserDialog({
  initialPath,
  onPick,
  onClose,
}: {
  initialPath?: string;
  onPick: (path: string) => void;
  onClose: () => void;
}) {
  const [listing, setListing] = useState<WorkspaceListing | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [address, setAddress] = useState(initialPath ?? "");
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [creatingDir, setCreatingDir] = useState(false);
  const backdrop = useBackdropClose(onClose);

  const load = async (path: string) => {
    setLoading(true);
    setSelected(null);
    const data = await actions.browseWorkspace(path);
    if (data) {
      setListing(data);
      setAddress(data.path);
    }
    setLoading(false);
  };

  useEffect(() => {
    void load(initialPath ?? "");
    // 仅在挂载时按初始路径加载一次；后续导航由 load() 直接触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const confirm = () => {
    const pick = selected ?? listing?.path;
    if (pick) onPick(pick);
  };

  const startCreate = () => {
    if (!listing?.exists || creating) return;
    setCreating(true);
    setNewName("");
  };

  const submitCreate = async () => {
    const name = newName.trim();
    if (!listing || !name) return;
    setCreatingDir(true);
    const res = await actions.createWorkspaceDir(listing.path, name);
    setCreatingDir(false);
    if (res) {
      setCreating(false);
      setNewName("");
      await load(res.path);
      setSelected(res.path);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[60] grid place-items-center bg-black/60 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[540px] max-h-[72vh] card shadow-2xl shadow-black/60 animate-rise flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 px-4 h-11 border-b border-edge shrink-0">
          <FolderOpen size={15} className="text-accent" />
          <span className="text-[13px] font-semibold">选择工作区目录</span>
          <span className="flex-1" />
          <button className="icon-btn" aria-label="关闭" title="关闭" onClick={onClose}>
            <X size={14} />
          </button>
        </div>

        <div className="px-4 pt-3 shrink-0">
          <div className="flex items-center gap-2">
            <input
              autoFocus
              className="text-input h-8 font-mono text-[12px] flex-1"
              placeholder="D:\path\to\workspace 或 /home/you/workspace"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && address.trim()) void load(address.trim());
              }}
            />
            <button
              className="btn h-8 px-2.5"
              disabled={!address.trim()}
              onClick={() => void load(address.trim())}
            >
              跳转
            </button>
            <button
              className="icon-btn h-8 w-8"
              title="上一级目录"
              aria-label="上一级目录"
              disabled={!listing?.parent}
              onClick={() => listing?.parent && void load(listing.parent)}
            >
              <ArrowUp size={14} />
            </button>
            <button
              className="icon-btn h-8 w-8"
              title="在此目录下新建文件夹"
              aria-label="新建文件夹"
              disabled={!listing?.exists || creating}
              onClick={startCreate}
            >
              <FolderPlus size={15} />
            </button>
          </div>
          {creating && (
            <div className="mt-2 flex items-center gap-2">
              <input
                autoFocus
                className="text-input h-8 font-mono text-[12px] flex-1"
                placeholder="新文件夹名称"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && newName.trim()) void submitCreate();
                  if (e.key === "Escape") setCreating(false);
                }}
              />
              <button
                className="btn btn-primary h-8 px-2.5"
                disabled={!newName.trim() || creatingDir}
                onClick={() => void submitCreate()}
              >
                {creatingDir ? "创建中…" : "创建"}
              </button>
              <button className="btn h-8 px-2.5" onClick={() => setCreating(false)}>
                取消
              </button>
            </div>
          )}
          {listing && listing.roots.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1">
              {listing.roots.map((r) => (
                <button
                  key={r.path}
                  className={`chip border cursor-pointer font-mono transition-colors ${
                    listing.path === r.path
                      ? "border-accent/50 bg-accent/10 text-accent"
                      : "border-edge text-faint hover:text-dim hover:border-edge-strong"
                  }`}
                  onClick={() => void load(r.path)}
                >
                  {r.name}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto px-2 py-2 mt-1">
          {loading ? (
            <div className="h-40 grid place-items-center text-faint">
              <Spinner />
            </div>
          ) : !listing ? (
            <div className="h-40 grid place-items-center text-[12.5px] text-faint">
              目录浏览需要连接本地后端（live 模式）
            </div>
          ) : (
            <>
              {!listing.exists && (
                <div className="mx-2 mb-2 rounded-lg border border-warn/30 bg-warn/10 px-3 py-2 text-[12px] text-warn">
                  目录不存在：选择后将按此路径自动创建并接入
                </div>
              )}
              {listing.directories.length === 0 ? (
                <div className="h-36 grid place-items-center text-[12.5px] text-faint">
                  {listing.exists ? "此目录下没有子目录" : ""}
                </div>
              ) : (
                <ul className="space-y-0.5">
                  {listing.directories.map((d) => (
                    <li key={d.path}>
                      <button
                        className={`w-full flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-left transition-colors cursor-pointer ${
                          selected === d.path
                            ? "bg-accent/10 outline outline-1 outline-accent/40"
                            : "hover:bg-raised"
                        }`}
                        onClick={() => setSelected(d.path)}
                        onDoubleClick={() => void load(d.path)}
                        title={`${d.path}（双击进入）`}
                      >
                        <FolderIcon
                          size={15}
                          weight={selected === d.path ? "fill" : "regular"}
                          className={`shrink-0 ${selected === d.path ? "text-accent" : "text-faint"}`}
                        />
                        <span className="text-[12.5px] text-ink truncate">{d.name}</span>
                        {d.isGitRepo && (
                          <span className="chip border border-info/25 bg-info/10 text-info">
                            <GitBranch size={10} />
                            git
                          </span>
                        )}
                        {d.isRegisteredProject && (
                          <span className="chip border border-edge-strong bg-raised text-faint">
                            已接入
                          </span>
                        )}
                        <span className="flex-1" />
                        <span
                          role="button"
                          tabIndex={-1}
                          className="grid place-items-center w-6 h-6 rounded-md text-faint hover:text-ink hover:bg-overlay"
                          title="进入此目录"
                          onClick={(e) => {
                            e.stopPropagation();
                            void load(d.path);
                          }}
                        >
                          <CaretRight size={12} />
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>

        <div className="flex items-center gap-3 px-4 py-3 border-t border-edge shrink-0">
          <div className="min-w-0 flex-1 font-mono text-[11px] text-faint truncate" title={selected ?? listing?.path}>
            {selected ?? listing?.path ?? ""}
          </div>
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button
            className="btn btn-primary"
            disabled={loading || !(selected ?? listing?.path)}
            onClick={confirm}
          >
            选择此目录
          </button>
        </div>
      </div>
    </div>
  );
}
