import type { PendingAttachment } from "./types";

/* ─── 会话输入附件（参考 OpenChamber composer 的粘贴语义） ───
 * 图片：读为 data URL 随消息发送（模型需支持图片输入）；
 * 非图片文件：浏览器拿不到真实路径时尽力从剪贴板文本载荷里解析绝对路径。 */

export const ATTACHABLE_IMAGE_MIMES = new Set([
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/webp",
]);

let attachmentSeq = 0;

function normalizeImageMime(file: File): string | null {
  const raw = (file.type || "").toLowerCase().split(";")[0].trim();
  if (ATTACHABLE_IMAGE_MIMES.has(raw)) return raw;
  // 截图/拖入的图片常带空 MIME，按扩展名兜底识别。
  const ext = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
  const byExt: Record<string, string> = {
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    gif: "image/gif",
    webp: "image/webp",
  };
  const guess = byExt[ext];
  return guess && ATTACHABLE_IMAGE_MIMES.has(guess) ? guess : null;
}

export function isAttachableImage(file: File): boolean {
  return normalizeImageMime(file) !== null;
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : "");
    reader.onerror = () => reject(reader.error ?? new Error("读取附件失败"));
    reader.readAsDataURL(file);
  });
}

export async function toPendingAttachment(file: File): Promise<PendingAttachment | null> {
  const mime = normalizeImageMime(file);
  if (!mime) return null;
  const dataUrl = await readAsDataUrl(file);
  const comma = dataUrl.indexOf(",");
  if (comma < 0) return null;
  attachmentSeq += 1;
  return {
    id: `att-${Date.now()}-${attachmentSeq}`,
    filename: file.name || `image.${mime.slice("image/".length)}`,
    mime,
    dataBase64: dataUrl.slice(comma + 1),
    dataUrl,
  };
}

/** file:// URI → 普通绝对路径（Windows 盘符去前导斜杠）。 */
export function normalizeFileUri(value: string): string {
  try {
    let pathname = decodeURIComponent(new URL(value).pathname || "");
    if (/^\/[A-Za-z]:\//.test(pathname)) pathname = pathname.slice(1);
    return pathname || value;
  } catch {
    const stripped = value.replace(/^file:\/\//i, "");
    try {
      return decodeURIComponent(stripped);
    } catch {
      return stripped;
    }
  }
}

/**
 * 从剪贴板/拖拽的文本载荷中提取一个绝对路径（file:// URI、Windows 盘符路径、
 * UNC 或 POSIX 绝对路径）。多行内容视为文档而非路径。
 */
export function extractAbsolutePath(payloads: string[]): string | null {
  for (const payload of payloads) {
    if (!payload) continue;
    for (const line of payload.split(/\r?\n/)) {
      const candidate = line.trim().replace(/^['"]+|['"]+$/g, "");
      if (!candidate) continue;
      if (/^file:\/\//i.test(candidate)) return normalizeFileUri(candidate);
      if (/^[A-Za-z]:[\\/]/.test(candidate) || candidate.startsWith("\\\\")) {
        return candidate.replace(/\\/g, "/");
      }
      if (candidate.startsWith("/") && !candidate.includes("://")) return candidate;
    }
  }
  return null;
}

/** 与后端一致的 [图片 #n] 引用文本；无附件时原样返回。 */
export function withImageCitations(text: string, attachments: PendingAttachment[]): string {
  if (attachments.length === 0) return text;
  let out = text;
  for (let i = 0; i < attachments.length; i++) {
    if (out.length > 0) out += "\n\n";
    out += `[图片 #${i + 1}] ${attachments[i].filename}`;
  }
  return out;
}
