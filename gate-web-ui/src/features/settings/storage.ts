/**
 * 设置域存储设置（settings storage，T-116）：数据目录概览、可清理缓存、工作区
 * 存储管理与「在系统中打开」的 HTTP 接入。
 */
import { api } from "@/net";
import type {
  StorageCachesResponse,
  StorageCleanResult,
  StorageOverview,
  StoragePruneAllResult,
  StoragePruneResult,
  StorageWorkspacesResponse,
} from "@/shared/types";

export async function fetchStorageOverview(): Promise<StorageOverview> {
  return api<StorageOverview>("/api/storage/overview");
}

export async function fetchStorageCaches(): Promise<StorageCachesResponse> {
  return api<StorageCachesResponse>("/api/storage/caches");
}

/** 按类别清理缓存（破坏性操作，调用方负责二次确认与结果提示）。 */
export async function cleanStorageCache(id: string): Promise<StorageCleanResult> {
  return api<StorageCleanResult>(`/api/storage/caches/${encodeURIComponent(id)}/clean`, {
    method: "POST",
    body: "{}",
  });
}

/** 工作区存储清单（克隆根下各工作区的占用/最后改动/可再生目录）。 */
export async function fetchStorageWorkspaces(): Promise<StorageWorkspacesResponse> {
  return api<StorageWorkspacesResponse>("/api/storage/workspaces");
}

/** 清理一个工作区的全部可再生目录（node_modules/构建产物等；破坏性操作，调用方负责二次确认）。 */
export async function pruneStorageWorkspace(id: string): Promise<StoragePruneResult> {
  return api<StoragePruneResult>(`/api/storage/workspaces/${encodeURIComponent(id)}/prune`, {
    method: "POST",
    body: "{}",
  });
}

/**
 * 一键清理：后端自己遍历全部工作区并清理可再生目录。
 * 边界由后端兜底——任一会话有进行中回合时以 400 拒绝（错误信息列出运行中的会话 id）；
 * 前端另用既有的 /api/agents/busy 轮询结果提前禁用按钮并给出原因。
 */
export async function pruneAllStorageWorkspaces(): Promise<StoragePruneAllResult> {
  return api<StoragePruneAllResult>("/api/storage/workspaces/prune", {
    method: "POST",
    body: "{}",
  });
}

/** 在系统文件管理器中打开数据目录（target = overview 下发的 openable key）。 */
export async function openStorageDir(target: string): Promise<void> {
  await api<{ ok: boolean }>("/api/storage/open", {
    method: "POST",
    body: JSON.stringify({ target }),
  });
}
