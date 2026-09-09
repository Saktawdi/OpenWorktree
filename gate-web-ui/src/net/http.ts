/**
 * 后端接入层（net）：HTTP 客户端、鉴权与连通性探测。
 * 任何 feature 包访问后端都必须经由这里的 api() 封装（统一 JSON 头、鉴权与错误展开）。
 */
import { appStore } from "@/store";

/** 注入 Web Token 的鉴权头：net 层内所有裸 fetch（SSE 流、二进制）统一走它。 */
export function authHeaders(): Record<string, string> {
  const token = appStore.getState().token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...authHeaders(), ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try {
      const body = await res.json();
      msg = body?.error?.message ?? body?.message ?? msg;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  return (await res.json()) as T;
}

export { api };

/**
 * 拉取二进制资源（如会话图片缩略图）并转为 object URL 供 <img> 使用：
 * <img src> 无法携带 Authorization 头，统一走这里。调用方负责 URL.revokeObjectURL。
 */
export async function fetchBlobUrl(path: string): Promise<string> {
  const res = await fetch(path, { headers: authHeaders() });
  if (!res.ok) {
    throw new Error(`fetch ${path}: ${res.status}`);
  }
  return URL.createObjectURL(await res.blob());
}

export async function detectBackend(): Promise<"ok" | "unauth" | "error"> {
  try {
    const res = await fetch("/api/status", { headers: authHeaders() });
    if (res.ok) return "ok";
    if (res.status === 401) return "unauth";
    return "error";
  } catch {
    return "error";
  }
}

export async function verifyToken(token: string): Promise<boolean> {
  try {
    const res = await fetch("/api/auth/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
    });
    return res.ok;
  } catch {
    return false;
  }
}
