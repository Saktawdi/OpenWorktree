import type { PendingAttachment } from "@/shared/types";

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

/** 缩略图最长边（px）：后端按 ≤1024px 原样落盘，放大不再二次处理。 */
const THUMB_MAX_EDGE = 1024;

/**
 * 最长边超限的 png/jpeg 由浏览器 canvas 等比预缩（桌面 WebView2/浏览器均可用）：
 * 后端 native-image 二进制里 java.desktop/ImageIO 不可用（T-112），缩放只能
 * 前移到这一侧。解码失败/未超限/其他类型保持原样返回 null。
 */
async function downscaleImage(dataUrl: string, mime: string): Promise<string | null> {
  if (mime !== "image/png" && mime !== "image/jpeg") return null;
  if (typeof createImageBitmap !== "function") return null;
  try {
    const blob = await (await fetch(dataUrl)).blob();
    const bitmap = await createImageBitmap(blob);
    const edge = Math.max(bitmap.width, bitmap.height);
    if (edge <= THUMB_MAX_EDGE) return null;
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round((bitmap.width * THUMB_MAX_EDGE) / edge));
    canvas.height = Math.max(1, Math.round((bitmap.height * THUMB_MAX_EDGE) / edge));
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const out = canvas.toDataURL(mime);
    bitmap.close();
    return out || null;
  } catch {
    return null;
  }
}

export async function toPendingAttachment(file: File): Promise<PendingAttachment | null> {
  const mime = normalizeImageMime(file);
  if (!mime) return null;
  let dataUrl = await readAsDataUrl(file);
  // 长边超限的大图先在浏览器端缩小，后端原样落盘即可保持 ≤1024px 的历史约定。
  dataUrl = (await downscaleImage(dataUrl, mime)) ?? dataUrl;
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

/** 文件 → 纯 base64（去 data URL 前缀）：粘贴/拖入的非图片文件上传落盘用。 */
export async function fileToBase64(file: File): Promise<string> {
  const dataUrl = await readAsDataUrl(file);
  const comma = dataUrl.indexOf(",");
  if (comma < 0) throw new Error("读取文件失败");
  return dataUrl.slice(comma + 1);
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

/** 与后端一致的 [图片 #n] 引用文本；无附件时原样返回。发送时统一追加在消息尾部，
 * 保证引用行独占一行——绝不与正文同行（同行引用会被误当正文的一部分，见下）。 */
export function withImageCitations(text: string, attachments: PendingAttachment[]): string {
  if (attachments.length === 0) return text;
  let out = text;
  for (let i = 0; i < attachments.length; i++) {
    if (out.length > 0) out += "\n\n";
    out += `[图片 #${i + 1}] ${attachments[i].filename}`;
  }
  return out;
}

/* 图片引用痕迹的匹配与剥离：
 * · [图片引用 #n] <工作区相对路径>：后端发送时追加的落盘引用行（独占一行）；
 * · [图片 #n] <文件名>：Composer 侧引用（现于发送时统一追加；历史消息里可能
 *   与正文混在同一行——旧版直接插在光标处）。
 * 剥离必须按 token 而不是按行丢弃：引用与正文同行时，行级过滤会把整行正文
 * 一起吞掉，表现为“只发了图却没有文字”（正文其实已送达 Agent）。 */
const IMAGE_CITE_TOKEN_RE =
  /\[图片(?:引用)? #\d+\][ \t]*([A-Za-z0-9._\-]*[./\\][A-Za-z0-9._\-/]*)?/g;

/**
 * 剥离消息文本中的图片引用痕迹，返回 { 正文, 图片工作区路径 }：
 * · 引用行/标记 → 从正文中移除（标记 + 紧随的路径/文件名 token；不含 . / \ 的
 *   后续文字一律保留，CJK 正文不会被误吞）；
 * · 含路径分隔符的 token 视为落盘引用（.gate/chat-images/…），收集进 images 供
 *   气泡还原缩略图；裸文件名（[图片 #1] image.png）不是工作区路径，不收集。
 * 历史回放（mapHistoryMessage）与实时气泡（UserMessageBody）共用，保证两个视图
 * 对同一消息的渲染口径一致。
 */
export function stripImageCitations(text: string): { body: string; images: string[] } {
  const images: string[] = [];
  const body = text
    .replace(IMAGE_CITE_TOKEN_RE, (_all, token?: string) => {
      if (token && /[\\/]/.test(token) && !images.includes(token)) images.push(token);
      return "";
    })
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  return { body, images };
}
