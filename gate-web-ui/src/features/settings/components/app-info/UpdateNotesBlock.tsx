import { ArrowUpRight } from "@phosphor-icons/react";
import type { UpdateNotes } from "@/shared/types";
import { Spinner } from "@/shared/components/ui";
import { Markdown } from "@/shared/components/Markdown";
import { useT } from "@/i18n";

/** 更新日志卡片：后端已从远程 CHANGELOG.md 截取对应版本小节；拉取失败静默（不打扰用户）。 */
export function UpdateNotesBlock({ notes, loading, version, repoUrl }: {
  notes: UpdateNotes | null;
  loading: boolean;
  version: string;
  repoUrl: string;
}) {
  const t = useT();
  if (loading) {
    return <div className="flex items-center gap-2 text-[12px] text-faint"><Spinner /> {t("appinfo.notesLoading")}</div>;
  }
  if (!notes || !notes.ok || !notes.content) {
    return null;
  }
  return (
    <div className="rounded-lg border border-edge bg-sunken/50 px-4 py-3">
      <div className="flex items-center gap-2">
        <span className="text-[12px] font-semibold text-dim">{t("appinfo.notesTitle")}</span>
        <span className="flex-1" />
        <a
          className="text-[11px] text-accent hover:brightness-110"
          href={`${repoUrl}/blob/HEAD/CHANGELOG.md`}
          target="_blank"
          rel="noopener noreferrer"
        >
          {t("appinfo.fullChangelog")} <ArrowUpRight size={10} className="inline" />
        </a>
      </div>
      <Markdown className="mt-1 text-[12.5px] text-dim max-h-[280px] overflow-y-auto">{notes.content}</Markdown>
    </div>
  );
}
