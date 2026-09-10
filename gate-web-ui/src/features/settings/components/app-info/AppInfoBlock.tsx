import { useCallback, useEffect, useState } from "react";
import { ArrowClockwise, ArrowUpRight, GithubLogo, Link, WarningCircle } from "@phosphor-icons/react";
import { checkAppUpdate, fetchAppInfo } from "@/features/settings";
import { useT } from "@/i18n";
import { useApp } from "@/store";
import type { AppInfo, UpdateCheck } from "@/shared/types";
import { CopyButton, Spinner } from "@/shared/components/ui";
import { openExternal } from "./badges";
import { UpdateStatusArea } from "./UpdateStatusArea";

/** 「关于」区块：版本信息、检查更新与 GitHub 仓库。 */
export function AppInfoBlock() {
  const t = useT();
  const theme = useApp((s) => s.theme);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [infoError, setInfoError] = useState<string | null>(null);
  const [check, setCheck] = useState<UpdateCheck | null>(null);
  const [checking, setChecking] = useState(false);

  const loadInfo = useCallback(async () => {
    setInfoError(null);
    try {
      setInfo(await fetchAppInfo());
    } catch (e) {
      setInfoError((e as Error).message);
    }
  }, []);

  /** force=true 绕过服务端缓存（手动按钮）；false 用于进页自动检查（命中服务端 TTL，不耗 GitHub 匿名配额）。 */
  const runCheck = useCallback(async (force: boolean) => {
    setChecking(true);
    try {
      setCheck(await checkAppUpdate(force));
    } catch (e) {
      setCheck({ ok: false, status: "unknown", current_version: "", error: (e as Error).message });
    } finally {
      setChecking(false);
    }
  }, []);

  useEffect(() => { void loadInfo(); }, [loadInfo]);
  // 进页自动检查一次；结果 5 分钟服务端缓存，反复切页不重复打 GitHub
  useEffect(() => { void runCheck(false); }, [runCheck]);

  if (infoError) {
    return (
      <div className="card p-6">
        <div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> {t("mcp.loadFailed")}：{infoError}</div>
        <button className="btn mt-3" onClick={() => void loadInfo()}>{t("common.retry")}</button>
      </div>
    );
  }
  if (!info) {
    return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> {t("appinfo.loading")}</div>;
  }

  const currentVersion = check?.current_version || info.version;

  return (
    <div className="space-y-4">
      {/* 版本与更新检查 */}
      <div className="card p-5">
        <div className="flex items-start gap-4 flex-wrap">
          <img
            src={theme === "dark" ? "/brand/ow-dark-badge-64.png" : "/brand/ow-light-badge-64.png"}
            alt=""
            width={44}
            height={44}
            draggable={false}
            className="select-none shrink-0 rounded-xl"
          />
          <div className="min-w-0">
            <div className="flex items-center gap-2.5 flex-wrap">
              <span className="text-[16px] font-bold tracking-tight">{info.name}</span>
              <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[11.5px]">v{info.version}</span>
              <CopyButton text={info.version} label={t("appinfo.copyVersion")} />
            </div>
            <div className="mt-1 text-[12px] text-faint">{t("appinfo.tagline")}</div>
          </div>
          <span className="flex-1" />
          <button className="btn btn-sm shrink-0" disabled={checking} onClick={() => void runCheck(true)} title={t("appinfo.checkTip")}>
            {checking ? <><Spinner /> {t("appinfo.checking")}</> : <><ArrowClockwise size={13} /> {t("appinfo.checkUpdate")}</>}
          </button>
        </div>
        <div className="mt-4 pt-4 border-t border-edge">
          <UpdateStatusArea
            check={check}
            checking={checking}
            currentVersion={currentVersion}
            repoUrl={info.repo_url}
            onRetry={() => void runCheck(true)}
          />
        </div>
      </div>

      {/* GitHub 仓库 */}
      <div className="card p-4 flex items-center gap-3.5 flex-wrap">
        <button
          className="w-10 h-10 rounded-lg border border-edge bg-sunken grid place-items-center text-dim hover:text-accent hover:border-accent/40 transition-colors cursor-pointer shrink-0"
          onClick={() => openExternal(info.repo_url)}
          title={t("appinfo.openRepo")}
          aria-label={t("appinfo.openRepo")}
        >
          <GithubLogo size={19} weight="fill" />
        </button>
        <div className="min-w-0 flex-1">
          <div className="text-[12.5px] font-semibold">{t("appinfo.repoTitle")}</div>
          <div className="mt-0.5 flex items-center gap-1.5 font-mono text-[11.5px] text-faint break-all">
            <Link size={12} className="shrink-0" />{info.repo_url}
            <CopyButton text={info.repo_url} label={t("appinfo.copyRepo")} />
          </div>
        </div>
        <button className="btn btn-sm shrink-0" onClick={() => openExternal(info.repo_url)}>
          {t("appinfo.openRepo")} <ArrowUpRight size={12} weight="bold" />
        </button>
      </div>
    </div>
  );
}
