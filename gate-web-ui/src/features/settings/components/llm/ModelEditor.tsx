import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  ArrowClockwise,
  Check,
  Funnel,
  MagnifyingGlass,
  PencilSimple,
  Plus,
  Robot,
  Trash,
  WarningCircle,
  X,
} from "@phosphor-icons/react";
import { probeUpstreamModels, updateProviderModels } from "@/features/settings";
import { showToast, useApp } from "@/store";
import type { LlmProvider } from "@/shared/types";
import { Spinner } from "@/shared/components/ui";

interface ProbeState {
  kind: "idle" | "fetching" | "done" | "error";
  ok?: boolean;
  text?: string;
}

/**
 * Provider 模型列表编辑器：
 * 参考「智能体 -> 供应商管理」中的模型交互体验：
 * 1. 上游探测与候选列表（全选、清空上游项、关键字过滤搜索、复选框多选）；
 * 2. 手动单个添加（回车即添，或支持批量逗号分隔添加）；
 * 3. 结构化标签管理（单项删除、一键清空）；
 * 4. 文本模式（高级用户可直接编辑原始逗号/换行文本）；
 * 5. 未保存差异高亮提示，明确的「保存修改」操作。
 */
export function ModelEditor({
  provider,
  onUpdated,
}: {
  provider: LlmProvider;
  onUpdated: () => void;
}) {
  const mode = useApp((s) => s.mode);

  // 本地选中的模型列表
  const [selectedModels, setSelectedModels] = useState<string[]>(provider.models);
  // 上游拉取到的候选模型列表
  const [upstreamCandidates, setUpstreamCandidates] = useState<string[]>([]);
  // 上游搜索过滤词
  const [upstreamSearch, setUpstreamSearch] = useState("");
  // 探测状态（空闲 / 拉取中 / 成功 / 失败）
  const [probe, setProbe] = useState<ProbeState>({ kind: "idle" });
  // 单个手动添加输入框
  const [customInput, setCustomInput] = useState("");
  // 批量文本模式开关
  const [batchMode, setBatchMode] = useState(false);
  const [batchText, setBatchText] = useState(provider.models.join(", "));
  // 保存状态
  const [saving, setSaving] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // 当外部 provider 变动时同步
  useEffect(() => {
    setSelectedModels(provider.models);
    setBatchText(provider.models.join(", "));
  }, [provider.models]);

  // 判断是否有未保存的变更
  const isDirty =
    selectedModels.length !== provider.models.length ||
    selectedModels.some((m, idx) => m !== provider.models[idx]);

  // 拉取上游候选模型
  const handleFetchUpstream = async () => {
    if (probe.kind === "fetching") return;
    setProbe({ kind: "fetching" });
    setErrorMsg(null);
    try {
      const list = await probeUpstreamModels(provider.id);
      setUpstreamCandidates(list);
      setProbe({
        kind: "done",
        ok: true,
        text: `拉取到 ${list.length} 个可用模型，勾选要加入配置的模型`,
      });
    } catch (e) {
      const err = (e as Error).message;
      setProbe({ kind: "error", ok: false, text: err });
      setErrorMsg(`拉取失败：${err}`);
    }
  };

  // 勾选 / 取消勾选某个上游模型
  const toggleModel = (id: string) => {
    setSelectedModels((prev) =>
      prev.includes(id) ? prev.filter((m) => m !== id) : [...prev, id],
    );
  };

  // 单个手动添加（也兼容用户一次性粘贴逗号/换行分隔的多个）
  const handleAddCustom = () => {
    const raw = customInput.trim();
    if (!raw) return;
    const tokens = raw.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean);
    if (tokens.length === 0) return;
    setSelectedModels((prev) => {
      const have = new Set(prev);
      const next = [...prev];
      for (const t of tokens) {
        if (!have.has(t)) {
          have.add(t);
          next.push(t);
        }
      }
      return next;
    });
    setCustomInput("");
  };

  // 移除单个模型
  const handleRemoveModel = (id: string) => {
    setSelectedModels((prev) => prev.filter((m) => m !== id));
  };

  // 清空所有已选
  const handleClearAll = () => {
    setSelectedModels([]);
  };

  // 批量文本应用
  const handleApplyBatchText = () => {
    const tokens = batchText.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean);
    const unique = Array.from(new Set(tokens));
    setSelectedModels(unique);
    setBatchMode(false);
  };

  // 保存落盘
  const handleSave = async () => {
    setSaving(true);
    setErrorMsg(null);
    try {
      await updateProviderModels(provider.id, selectedModels);
      showToast("模型列表已保存");
      onUpdated();
    } catch (e) {
      setErrorMsg((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  // 放弃修改，重置为已保存状态
  const handleReset = () => {
    setSelectedModels(provider.models);
    setBatchText(provider.models.join(", "));
    setBatchMode(false);
    setErrorMsg(null);
  };

  // 过滤上游候选
  const filteredCandidates = upstreamCandidates.filter((c) =>
    c.toLowerCase().includes(upstreamSearch.trim().toLowerCase()),
  );

  return (
    <div className="mt-3.5 pt-3.5 border-t border-edge space-y-3">
      {/* 头部控制栏 */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="field-label !mb-0">模型</span>
        <span
          className={`chip border text-[10.5px] font-mono ${
            selectedModels.length
              ? "border-info/25 bg-info/10 text-info"
              : "border-edge-strong bg-raised text-faint"
          }`}
        >
          {selectedModels.length} 个
        </span>

        {isDirty && (
          <span className="chip border border-warn/30 bg-warn/10 text-warn text-[10.5px]">
            未保存修改
          </span>
        )}

        <span className="flex-1" />

        {/* 批量文本编辑切换 */}
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => {
            if (!batchMode) setBatchText(selectedModels.join(", "));
            setBatchMode(!batchMode);
          }}
          title={batchMode ? "返回标签视图" : "切换为文本编辑（支持批量粘贴）"}
        >
          <PencilSimple size={12} />
          {batchMode ? "标签模式" : "文本模式"}
        </button>

        {/* 拉取上游模型按钮 */}
        <button
          type="button"
          className="btn btn-sm"
          disabled={probe.kind === "fetching"}
          onClick={() => void handleFetchUpstream()}
          title="从 Provider 接口探测可用模型列表，供勾选加入"
        >
          {probe.kind === "fetching" ? (
            <>
              <Spinner />
              拉取中…
            </>
          ) : (
            <>
              <ArrowClockwise size={12} />
              拉取上游模型
            </>
          )}
        </button>

        {/* 保存修改按钮（有改动时高亮） */}
        {isDirty && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              className="btn btn-sm"
              onClick={handleReset}
              disabled={saving}
            >
              放弃
            </button>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={saving}
              onClick={() => void handleSave()}
            >
              {saving ? "保存中…" : "保存修改"}
            </button>
          </div>
        )}
      </div>

      {/* 探测反馈提示 */}
      {probe.kind !== "idle" && probe.kind !== "fetching" && (
        <div
          className={`flex items-center gap-1 text-[12px] ${
            probe.ok ? "text-accent" : "text-danger"
          }`}
        >
          {probe.ok ? (
            <Check size={13} weight="bold" />
          ) : (
            <WarningCircle size={13} weight="fill" />
          )}
          {probe.text}
        </div>
      )}

      {/* 上游候选池面板（拉取到模型时展开） */}
      {upstreamCandidates.length > 0 && (
        <div className="rounded-lg border border-edge bg-canvas/60 p-3 space-y-2.5">
          <div className="flex items-center gap-2 flex-wrap text-[11.5px] text-faint">
            <span className="font-semibold text-ink">上游返回 {upstreamCandidates.length} 个模型</span>
            <span>· 勾选直接加入配置</span>
            <span className="flex-1" />
            <button
              type="button"
              className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
              onClick={() => {
                const have = new Set(selectedModels);
                setSelectedModels([...selectedModels, ...upstreamCandidates.filter((m) => !have.has(m))]);
              }}
            >
              全选
            </button>
            <span>·</span>
            <button
              type="button"
              className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
              onClick={() => {
                const upstreamSet = new Set(upstreamCandidates);
                setSelectedModels(selectedModels.filter((m) => !upstreamSet.has(m)));
              }}
            >
              清空上游项
            </button>
            <span>·</span>
            <button
              type="button"
              className="icon-btn !w-5 !h-5 text-faint hover:text-ink"
              title="收起候选列表"
              onClick={() => setUpstreamCandidates([])}
            >
              <X size={12} />
            </button>
          </div>

          {/* 候选过滤搜索框 */}
          {upstreamCandidates.length > 6 && (
            <div className="relative">
              <input
                className="text-input !h-7 text-[11.5px] pl-7"
                placeholder="搜索上游模型…"
                value={upstreamSearch}
                onChange={(e) => setUpstreamSearch(e.target.value)}
              />
              <MagnifyingGlass size={12} className="absolute left-2.5 top-2 text-faint pointer-events-none" />
              {upstreamSearch && (
                <button
                  type="button"
                  className="absolute right-2 top-1.5 text-faint hover:text-ink cursor-pointer"
                  onClick={() => setUpstreamSearch("")}
                >
                  <X size={11} />
                </button>
              )}
            </div>
          )}

          {/* 候选复选框列表 */}
          <div className="max-h-[160px] overflow-y-auto space-y-0.5 pr-1 border border-edge/60 rounded-md p-1.5 bg-sunken/40">
            {filteredCandidates.length === 0 ? (
              <div className="py-2 text-center text-[11px] text-faint">未找到匹配模型</div>
            ) : (
              filteredCandidates.map((m) => {
                const checked = selectedModels.includes(m);
                return (
                  <label
                    key={m}
                    className={`flex items-center gap-2 px-2 py-1 rounded-md hover:bg-raised/70 cursor-pointer text-[12px] font-mono transition-colors ${
                      checked ? "text-accent font-medium bg-accent/5" : "text-dim"
                    }`}
                  >
                    <input
                      type="checkbox"
                      className="accent-accent cursor-pointer"
                      checked={checked}
                      onChange={() => toggleModel(m)}
                    />
                    <span className="truncate">{m}</span>
                  </label>
                );
              })
            )}
          </div>
        </div>
      )}

      {/* 文本模式 vs 标签模式 */}
      <AnimatePresence mode="wait" initial={false}>
        {batchMode ? (
          <motion.div
            key="batch"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.15 }}
            className="space-y-2"
          >
            <textarea
              className="textarea font-mono text-[12px]"
              rows={4}
              placeholder="逗号或换行分隔模型名称，例如：gpt-4o, claude-3-5-sonnet"
              value={batchText}
              onChange={(e) => setBatchText(e.target.value)}
              autoFocus
            />
            <div className="flex items-center justify-between text-[11px] text-faint">
              <span>支持逗号或换行分隔，点击「应用到列表」即可解析</span>
              <button
                type="button"
                className="btn btn-sm btn-primary"
                onClick={handleApplyBatchText}
              >
                应用到列表
              </button>
            </div>
          </motion.div>
        ) : (
          <motion.div
            key="chips"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.15 }}
            className="space-y-2.5"
          >
            {/* 手动单个添加输入框 */}
            <div className="flex items-center gap-2">
              <input
                className="text-input font-mono text-[12px] !h-8"
                placeholder="手动输入模型 ID，回车或点击右侧添加"
                value={customInput}
                onChange={(e) => setCustomInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    handleAddCustom();
                  }
                }}
              />
              <button
                type="button"
                className="btn btn-sm !h-8 shrink-0"
                disabled={!customInput.trim()}
                onClick={handleAddCustom}
              >
                <Plus size={12} weight="bold" /> 添加
              </button>
            </div>

            {/* 已选模型标签展示区 */}
            {selectedModels.length === 0 ? (
              <div className="flex items-center gap-2 rounded-lg border border-dashed border-edge-strong bg-sunken/40 px-3.5 py-3 text-[12px] text-faint">
                <Robot size={14} className="shrink-0" />
                <span>
                  暂未配置模型 — 点击上方「拉取上游模型」自动获取，或在上方输入框回车手动添加
                </span>
              </div>
            ) : (
              <div className="flex flex-wrap gap-1.5 p-2 rounded-lg border border-edge/60 bg-sunken/30 min-h-[42px] items-center">
                {selectedModels.map((m) => (
                  <span
                    key={m}
                    className="inline-flex items-center gap-1.5 pl-2.5 pr-1 py-1 rounded-md border border-edge-strong bg-raised text-dim font-mono text-[11.5px] hover:border-accent/40 transition-colors group"
                  >
                    <span className="truncate max-w-[280px]">{m}</span>
                    <button
                      type="button"
                      className="w-4 h-4 rounded-full flex items-center justify-center text-faint hover:text-danger hover:bg-danger/10 transition-colors cursor-pointer"
                      title={`移除 ${m}`}
                      aria-label={`移除 ${m}`}
                      onClick={() => handleRemoveModel(m)}
                    >
                      <X size={10} weight="bold" />
                    </button>
                  </span>
                ))}
                <span className="flex-1" />
                <button
                  type="button"
                  className="text-[11px] text-faint hover:text-danger cursor-pointer ml-1"
                  onClick={handleClearAll}
                  title="清空全部已选模型"
                >
                  清空全部
                </button>
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* 错误提示 */}
      {errorMsg && (
        <div className="text-[12px] text-danger flex items-center gap-1">
          <WarningCircle size={13} weight="fill" />
          {errorMsg}
        </div>
      )}
    </div>
  );
}
