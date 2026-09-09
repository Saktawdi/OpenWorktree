/**
 * 设置域存储设置（settings storage，T-116）：数据目录概览、可清理缓存与
 * 「在系统中打开」的 HTTP 接入。本地偏好数据（localStorage）的管理不走后端，
 * 见 components/storage/localData.ts。
 */
import { api } from "@/net";
import type {
  StorageCachesResponse,
  StorageCleanResult,
  StorageOverview,
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

/** 在系统文件管理器中打开数据目录（target = overview 下发的 openable key）。 */
export async function openStorageDir(target: string): Promise<void> {
  await api<{ ok: boolean }>("/api/storage/open", {
    method: "POST",
    body: JSON.stringify({ target }),
  });
}
