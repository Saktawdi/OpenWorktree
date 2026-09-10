import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { ArrowCircleUp, ArrowClockwise, Check, Clock, DownloadSimple, WarningCircle } from "@phosphor-icons/react";
import { fetchUpdateNotes } from "@/features/settings";
import { useT } from "@/i18n";
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
  const t = useT();
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
    return <div className="flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> {t("appinfo.checkingLong")}</div>;
  }
  if (!check) {
    return <div className="text-[12.5px] text-faint">{t("appinfo.notChecked")}</div>;
  }
  if (!check.ok || (check.status === "unknown" && check.error)) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {t("appinfo.checkFailed")}：{check.error ?? t("common.unknown")}
        </span>
        <button className="btn btn-sm" onClick={onRetry}><ArrowClockwise size={12} /> {t("common.retry")}</button>
        {check.error?.includes("无法连接") && (
          <div className="w-full text-[11px] text-faint leading-relaxed">
            {t("appinfo.networkHint")}
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
          <span className="chip border border-info/30 bg-info/10 text-info"><ArrowCircleUp size={12} weight="fill" /> {t("appinfo.updateAvailable", { v: check.latest_version ?? "" })}</span>
          {/* tag 与展示版本仅差 v 前缀时不重复展示 */}
          {check.tag_name && check.tag_name.replace(/^v/i, "") !== check.latest_version && (
            <span className="chip border border-edge-strong bg-raised text-faint font-mono">{check.tag_name}</span>
          )}
          {check.published_at && <span className="text-[11.5px] text-faint">{t("appinfo.releasedAt", { d: releaseDate(check.published_at) })}</span>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {url && (
            <button className="btn btn-primary btn-sm" onClick={() => openExternal(url)}>
              <DownloadSimple size={13} weight="fill" /> {t("appinfo.gotoDownload")}
            </button>
          )}
          <button className="btn btn-sm opacity-60 cursor-not-allowed" disabled title={t("appinfo.autoUpdateLaterTip")}>
            <Clock size={13} /> {t("appinfo.autoUpdateLater")}
          </button>
        </div>
        <div className="text-[11px] text-faint leading-relaxed">{t("appinfo.autoUpdateNote")}</div>
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
          {t("appinfo.unreleasedNote")}
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
          {t("appinfo.betaAheadNote", { cur: currentVersion, latest: check.latest_version ?? "" })}
        </div>
      </motion.div>
    );
  }
  if (check.status === "unknown") {
    return (
      <div className="text-[12.5px] text-faint leading-relaxed">
        {t("appinfo.incomparable")}{check.latest_version ? t("appinfo.incomparableDetail", { cur: currentVersion, latest: check.latest_version }) : null}。
      </div>
    );
  }
  // up_to_date
  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}>
      <span className="chip border border-accent/30 bg-accent/10 text-accent"><Check size={12} weight="bold" /> {t("appinfo.upToDate")}</span>
      {check.latest_version && (
        <span className="ml-2 text-[12px] text-faint">{t("appinfo.upToDateDetail", { v: check.latest_version })}</span>
      )}
    </motion.div>
  );
}
