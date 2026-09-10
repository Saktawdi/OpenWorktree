import { Faders, Globe, ListChecks, Lightning, Info, GraduationCap, ArrowRight } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { setFollowUpBehavior } from "@/features/session";
import { openOnboarding } from "@/features/onboarding";
import type { FollowUpBehavior } from "@/shared/types";
import { LOCALES, setLocale, useLocale, useT, type LocaleId } from "@/i18n";

/** 输入排队行为的两个可选项（与 Composer 底部开关同一偏好，见 store.followUpBehavior）。 */
const FOLLOW_UP_OPTIONS: Array<{
  value: FollowUpBehavior;
  labelKey: "prefs.followUp.queue" | "prefs.followUp.steer";
  hintKey: "prefs.followUp.queueHint" | "prefs.followUp.steerHint";
  Icon: typeof ListChecks;
}> = [
  {
    value: "queue",
    labelKey: "prefs.followUp.queue",
    hintKey: "prefs.followUp.queueHint",
    Icon: ListChecks,
  },
  {
    value: "steer",
    labelKey: "prefs.followUp.steer",
    hintKey: "prefs.followUp.steerHint",
    Icon: Lightning,
  },
];

/** 语言选项的说明文案键：按语言 id 取对应提示。 */
const LANG_HINT_KEYS: Record<LocaleId, "prefs.lang.zhHint" | "prefs.lang.enHint"> = {
  "zh-CN": "prefs.lang.zhHint",
  en: "prefs.lang.enHint",
};

/** 偏好设置：输入行为（排队/插队）、语言等本地偏好。 */
export function PrefsBlock() {
  const t = useT();
  const currentLocale = useLocale();
  const followUpBehavior = useApp((s) => s.followUpBehavior);

  return (
    <div className="space-y-4">
      {/* 输入排队设置（自输入框工具行迁入；与 Composer 开关读写同一偏好） */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-accent-dim border-accent/30 text-accent">
            <Faders size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("prefs.title")}</span>
          <span className="flex-1" />
          <span className="text-[11px] text-faint">{t("prefs.titleHint")}</span>
        </div>
        <div className="mt-3 grid gap-2">
          {FOLLOW_UP_OPTIONS.map(({ value, labelKey, hintKey, Icon }) => {
            const active = followUpBehavior === value;
            return (
              <button
                key={value}
                onClick={() => setFollowUpBehavior(value)}
                aria-pressed={active}
                className={`flex items-start gap-3 text-left rounded-lg border px-3 py-2.5 transition-colors cursor-pointer ${
                  active
                    ? "border-accent/40 bg-accent/10"
                    : "border-edge bg-sunken hover:border-edge-strong hover:bg-raised/40"
                }`}
              >
                <span
                  className={`mt-0.5 grid place-items-center w-6 h-6 rounded-md border shrink-0 ${
                    active ? "bg-accent-dim border-accent/30 text-accent" : "bg-raised border-edge text-faint"
                  }`}
                >
                  <Icon size={13} weight={active ? "fill" : "regular"} />
                </span>
                <span className="min-w-0">
                  <span className={`block text-[12.5px] font-medium ${active ? "text-accent" : "text-ink"}`}>
                    {t(labelKey)}
                    {active && <span className="ml-2 text-[10.5px] text-accent/80 font-normal">{t("common.active")}</span>}
                  </span>
                  <span className="block mt-0.5 text-[11.5px] text-faint leading-relaxed">{t(hintKey)}</span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="mt-3 flex items-start gap-1.5 text-[11px] text-faint leading-relaxed">
          <Info size={12} className="shrink-0 mt-px" />
          {t("prefs.followUp.note")}
        </div>
      </div>

      {/* 语言：点击即时切换界面语言，选择结果本地持久化（gate-locale） */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <Globe size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("prefs.lang.title")}</span>
          <span className="flex-1" />
        </div>
        <div className="mt-3 grid gap-2">
          {LOCALES.map((lang) => {
            const active = lang.id === currentLocale;
            return (
              <button
                key={lang.id}
                onClick={() => setLocale(lang.id)}
                aria-pressed={active}
                className={`flex items-center gap-3 text-left rounded-lg border px-3 py-2.5 transition-colors cursor-pointer ${
                  active
                    ? "border-accent/40 bg-accent/10"
                    : "border-edge bg-sunken hover:border-edge-strong hover:bg-raised/40"
                }`}
              >
                <span
                  className={`grid place-items-center w-6 h-6 rounded-md border shrink-0 ${
                    active ? "bg-accent-dim border-accent/30 text-accent" : "bg-raised border-edge text-faint"
                  }`}
                >
                  <Globe size={13} />
                </span>
                <span className="min-w-0">
                  <span className={`block text-[12.5px] font-medium ${active ? "text-accent" : "text-ink"}`}>
                    {lang.label}
                    {active && <span className="ml-2 text-[10.5px] text-accent/80 font-normal">{t("common.active")}</span>}
                  </span>
                  <span className="block mt-0.5 text-[11.5px] text-faint leading-relaxed">
                    {t(LANG_HINT_KEYS[lang.id])}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 新手引导：首次启动自动弹出过一次后，这里可随时重看（不重置完成标记） */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <GraduationCap size={13} />
          </span>
          <span className="text-[13px] font-semibold">{t("prefs.onboarding.title")}</span>
          <span className="flex-1" />
          <button className="btn btn-sm" onClick={() => openOnboarding(0)}>
            {t("prefs.onboarding.replay")}
            <ArrowRight size={11} />
          </button>
        </div>
        <div className="mt-2 text-[11.5px] text-faint leading-relaxed">{t("prefs.onboarding.desc")}</div>
      </div>
    </div>
  );
}
