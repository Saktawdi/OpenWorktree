import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { ArrowCircleUp, ArrowClockwise, Check, Clock, DownloadSimple, WarningCircle } from "@phosphor-icons/react";
import { fetchUpdateNotes } from "@/features/settings";
import type { UpdateCheck, UpdateNotes } from "@/shared/types";
import { Spinner } from "@/shared/components/ui";
import { BetaAheadBadge, PioneerBadge, openExternal, releaseDate } from "./badges";
import { UpdateNotesBlock } from "./UpdateNotesBlock";

/** 检查更新三态结果：有更新（跳下载页 + 展示更新日志）/ 已最新 / 先行 beta / 先行者 / 无法比对或失败。 */
export function UpdateStatusArea({
  check,
  checking,
  currentVersion,
  repoUrl,
  onRetry,
}: {
  check: UpdateCheck | null;
  checking: boolean;
  currentVersion: string;
  repoUrl: string;
  onRetry: () => void;
}) {
  const hasUpdate = !!check && check.ok && check.status === "update_available";
  // 发现新版本时自动拉取远程 CHANGELOG.md 的对应版本小节（后端已按版本截取，失败静默）
  const [notes, setNotes] = useState<UpdateNotes | null>(null);
  const [notesLoading, setNotesLoading] = useState(false);
  const latestVersion = check?.latest_version ?? "";
  useEffect(() => {
    if (!hasUpdate || !latestVersion) {
      setNotes(null);
      return;
    }
    let cancelled = false;
    setNotesLoading(true);
    setNotes(null);
    fetchUpdateNotes(latestVersion)
      .then((n) => { if (!cancelled) setNotes(n); })
      .catch(() => { if (!cancelled) setNotes({ ok: false, version: latestVersion }); })
      .finally(() => { if (!cancelled) setNotesLoading(false); });
    return () => { cancelled = true; };
  }, [hasUpdate, latestVersion]);

  if (checking && !check) {
    return <div className="flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> 正在检查更新 …</div>;
  }
  if (!check) {
    return <div className="text-[12.5px] text-faint">尚未检查更新</div>;
  }
  if (!check.ok || (check.status === "unknown" && check.error)) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> 检查失败：{check.error ?? "未知错误"}
        </span>
        <button className="btn btn-sm" onClick={onRetry}><ArrowClockwise size={12} /> 重试</button>
        {check.error?.includes("无法连接") && (
          <div className="w-full text-[11px] text-faint leading-relaxed">
            通常是本机到 GitHub 的网络不通（被墙或代理未开），稍后再试即可，不影响应用本身的使用。
          </div>
        )}
      </div>
    );
  }
  if (check.status === "update_available") {
    const url = check.release_url || null;
    return (
      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
        className="space-y-3"
      >
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="chip border border-info/30 bg-info/10 text-info"><ArrowCircleUp size={12} weight="fill" /> 发现新版本 v{check.latest_version}</span>
          {/* tag 与展示版本仅差 v 前缀时不重复展示 */}
          {check.tag_name && check.tag_name.replace(/^v/i, "") !== check.latest_version && (
            <span className="chip border border-edge-strong bg-raised text-faint font-mono">{check.tag_name}</span>
          )}
          {check.published_at && <span className="text-[11.5px] text-faint">{releaseDate(check.published_at)} 发行</span>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {url && (
            <button className="btn btn-primary btn-sm" onClick={() => openExternal(url)}>
              <DownloadSimple size={13} weight="fill" /> 前往下载页
            </button>
          )}
          <button className="btn btn-sm opacity-60 cursor-not-allowed" disabled title="在线自动更新将在后续版本提供">
            <Clock size={13} /> 在线更新 · 即将上线
          </button>
        </div>
        <div className="text-[11px] text-faint leading-relaxed">在线自动更新已预留接口，当前版本请通过下载页获取安装包。</div>
        <UpdateNotesBlock notes={notes} loading={notesLoading} version={latestVersion} repoUrl={repoUrl} />
      </motion.div>
    );
  }
  if (check.status === "unpublished") {
    return (
      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
        className="space-y-2.5"
      >
        <PioneerBadge />
        <div className="text-[12.5px] text-dim leading-relaxed">
          远程仓库还没有任何已发行版本（仓库未公开，或还没打第一个 Release/tag）——当前构建就是最超前的先行者版本，无可比较、无需更新。
        </div>
      </motion.div>
    );
  }
  if (check.status === "ahead_beta") {
    return (
      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
        className="space-y-2.5"
      >
        <BetaAheadBadge />
        <div className="text-[12.5px] text-dim leading-relaxed">
          当前版本 <span className="font-mono text-ink">v{currentVersion}</span> 领先于最新发行 <span className="font-mono text-ink">v{check.latest_version}</span>
          ——这是先行体验（beta）构建：新功能先于正式版本到达，无需更新，正式版本跟上后徽标会自动消失。
        </div>
      </motion.div>
    );
  }
  if (check.status === "unknown") {
    return (
      <div className="text-[12.5px] text-faint leading-relaxed">
        无法与远程版本比对{check.latest_version ? <>（当前 v{currentVersion} 与远程 v{check.latest_version} 缺少可比的数字版本号）</> : null}。
      </div>
    );
  }
  // up_to_date
  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}>
      <span className="chip border border-accent/30 bg-accent/10 text-accent"><Check size={12} weight="bold" /> 已是最新版本</span>
      {check.latest_version && (
        <span className="ml-2 text-[12px] text-faint">与远程最新发行 v{check.latest_version} 一致</span>
      )}
    </motion.div>
  );
}
