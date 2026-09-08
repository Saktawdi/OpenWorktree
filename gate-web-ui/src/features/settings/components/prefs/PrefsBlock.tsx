import { Faders, Globe, ListChecks, Lightning, Info } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { setFollowUpBehavior } from "@/features/session";
import type { FollowUpBehavior } from "@/shared/types";

/** 输入排队行为的两个可选项（与 Composer 底部开关同一偏好，见 store.followUpBehavior）。 */
const FOLLOW_UP_OPTIONS: Array<{
  value: FollowUpBehavior;
  label: string;
  hint: string;
  Icon: typeof ListChecks;
}> = [
  {
    value: "queue",
    label: "排队",
    hint: "Agent 工作时回车将消息加入队列，空闲后自动发送；Ctrl+Enter 插队",
    Icon: ListChecks,
  },
  {
    value: "steer",
    label: "插队",
    hint: "Agent 工作时回车将直接插队到当前回合（仅 opencode 运行时）；Ctrl+Enter 排队",
    Icon: Lightning,
  },
];

/** 语言切换入口（预留）：仅展示当前语言，实际多语言能力待接入。 */
const APP_LANGUAGES = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en", label: "English" },
] as const;

/** 偏好设置：输入行为（排队/插队）、语言等本地偏好。 */
export function PrefsBlock() {
  const followUpBehavior = useApp((s) => s.followUpBehavior);

  return (
    <div className="space-y-4">
      {/* 输入排队设置（自输入框工具行迁入；与 Composer 开关读写同一偏好） */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-accent-dim border-accent/30 text-accent">
            <Faders size={13} />
          </span>
          <span className="text-[13px] font-semibold">输入排队</span>
          <span className="flex-1" />
          <span className="text-[11px] text-faint">Agent 输出时的回车行为</span>
        </div>
        <div className="mt-3 grid gap-2">
          {FOLLOW_UP_OPTIONS.map(({ value, label, hint, Icon }) => {
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
                    {label}
                    {active && <span className="ml-2 text-[10.5px] text-accent/80 font-normal">当前生效</span>}
                  </span>
                  <span className="block mt-0.5 text-[11.5px] text-faint leading-relaxed">{hint}</span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="mt-3 flex items-start gap-1.5 text-[11px] text-faint leading-relaxed">
          <Info size={12} className="shrink-0 mt-px" />
          实时插队仅 opencode 运行时支持；claude 等其他 Agent 会自动降级为排队并提示。
        </div>
      </div>

      {/* 语言（预留入口）：多语言尚未接入，先提供展示与禁用的选择控件 */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5">
          <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-raised border-edge text-dim">
            <Globe size={13} />
          </span>
          <span className="text-[13px] font-semibold">语言</span>
          <span className="flex-1" />
          <span className="chip border border-edge-strong bg-raised text-faint text-[10.5px]">即将支持</span>
        </div>
        <div className="mt-3 grid gap-2 opacity-60">
          {APP_LANGUAGES.map((lang) => (
            <div
              key={lang.value}
              aria-disabled="true"
              className="flex items-center gap-3 rounded-lg border border-edge bg-sunken px-3 py-2.5 select-none"
            >
              <span className="w-6 h-6 grid place-items-center rounded-md border bg-raised border-edge text-faint shrink-0">
                <Globe size={13} />
              </span>
              <span className="min-w-0">
                <span className="block text-[12.5px] font-medium text-ink">{lang.label}</span>
                <span className="block mt-0.5 text-[11.5px] text-faint leading-relaxed">
                  {lang.value === "zh-CN" ? "界面当前语言" : "待多语言框架接入后可用"}
                </span>
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
