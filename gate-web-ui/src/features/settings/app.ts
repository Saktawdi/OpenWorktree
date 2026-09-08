/**
 * 设置域应用信息（settings app）：版本、更新检查与更新日志。
 */
import { api } from "@/net";
import type { AppInfo, UpdateCheck, UpdateNotes } from "@/shared/types";

export async function fetchAppInfo(): Promise<AppInfo> {
  return api<AppInfo>("/api/app/info");
}

/**
 * 检查更新：后端读 GitHub 最新发行版本并与本地版本比对。force=1 绕过服务端 5 分钟缓存
 * （手动点「检查更新」时用；GitHub 匿名配额 60 次/时/IP，页面自动加载走缓存）。
 */
export async function checkAppUpdate(force?: boolean): Promise<UpdateCheck> {
  return api<UpdateCheck>(`/api/app/update-check${force ? "?force=1" : ""}`);
}

/**
 * 拉取更新日志：后端从仓库默认分支读 CHANGELOG.md，截取 version 对应的小节
 * （发现新版本时在「关于」页展示该版本的更新内容）。失败降级为 ok:false 数据。
 */
export async function fetchUpdateNotes(version: string, force?: boolean): Promise<UpdateNotes> {
  const q = new URLSearchParams({ version });
  if (force) q.set("force", "1");
  return api<UpdateNotes>(`/api/app/changelog?${q.toString()}`);
}
