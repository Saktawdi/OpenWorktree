package gate.adapters.engine;

import gate.adapters.engine.DiffSections.Section;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegate 模式的「审查规格书」构建器（P2-3，反哺 OCR delegate mode）：不消耗门禁的任何
 * LLM 配置，把确定性工程已经算好的东西——切片、每文件 token 估算、跳过判定、按文件解析的
 * 规则——打包成一份规格书，交给编码 Agent 用<b>它自己的模型</b>执行预审。用途：提审前
 * 自查（agent 在 presubmit 之前先自己过一遍并修掉明显问题），以及零 LLM 配置项目的预审路径。
 *
 * <p><b>边界必须说死</b>：规格书产出的是<b>咨询性自查材料</b>，它的结论绝不进入门禁证据、
 * 绝不影响 GatePolicy 判决——否则 Agent 就能自己给自己写 pass。门禁判决只来自
 * {@code gate_review} 的 {@code ReviewEvidence}。
 */
public final class ReviewSpecBuilder {

    private ReviewSpecBuilder() {
    }

    public static final String SPEC_VERSION = "1";

    /**
     * @param repoDir      克隆工作区（用于读取 {@code .gate/rules.json}；null 等价于无规则）
     * @param maxFileTokens 单文件 token 闸门（与引擎同口径；超限文件不跳过而是打标——Agent 的
     *                      上下文是它自己的事，但必须知道这份 diff 超限）
     */
    public static Map<String, Object> build(String ticketNo, int reviewRound, String treeHash,
                                            String baseCommit, String diff, List<String> changedPaths,
                                            Path repoDir, long maxFileTokens) {
        ReviewRules rules = ReviewRules.load(repoDir);
        List<Section> sections = DiffSections.split(diff);
        List<Map<String, Object>> files = new ArrayList<>();
        int skipped = 0;
        long totalTokens = 0;
        for (Section s : sections) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", s.path());
            if (s.oldPath() != null && !s.oldPath().equals(s.path())) {
                f.put("old_path", s.oldPath());
            }
            String status = s.deleted() ? "deleted" : s.newFile() ? "added" : "modified";
            if (s.binary()) {
                status = "binary";
            }
            f.put("status", status);
            long tokens = DiffSections.estimateTokens(s.body());
            f.put("tokens_est", tokens);

            String skipReason = null;
            if (s.deleted()) {
                skipReason = "deleted";
            } else if (s.binary()) {
                skipReason = "binary";
            } else {
                for (String glob : secretGlobs()) {
                    if (ReviewRules.matches(glob, s.path())) {
                        skipReason = "secret_path";
                        break;
                    }
                }
                if (skipReason == null && rules.skip(s.path())) {
                    skipReason = "rule_skip";
                }
            }
            if (skipReason != null) {
                f.put("skip_reason", skipReason);
                skipped++;
            } else {
                if (tokens > maxFileTokens) {
                    f.put("over_token_gate", true);
                }
                f.put("rules", rules.ruleTexts(s.path()));
                f.put("diff", s.body());
                totalTokens += tokens;
            }
            files.add(f);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("spec_version", SPEC_VERSION);
        spec.put("ticket_no", ticketNo);
        spec.put("review_round", reviewRound);
        spec.put("tree_hash", treeHash);
        spec.put("base_commit", baseCommit);
        spec.put("instructions", instructions());
        spec.put("rules_file_present", !rules.isEmpty());
        spec.put("files", files);
        spec.put("totals", Map.of(
                "files", files.size(),
                "skipped", skipped,
                "reviewable_tokens_est", totalTokens,
                "changed_paths", changedPaths == null ? List.of() : changedPaths));
        return spec;
    }

    /**
     * 自查说明：明确三件事——按文件逐个审、发现问题先修再提审、自查是咨询性的，
     * 门禁判决只来自 gate_review 的证据（Agent 不能给自己写 pass）。
     */
    private static String instructions() {
        return "这是一份审查规格书（delegate 模式）：门禁的确定性工程已经完成文件切片、"
                + "token 估算、密钥/二进制/删除文件排除与规则解析，请用你自己的模型执行预审。\n"
                + "1. 逐个审读 files 中未带 skip_reason 的文件的 diff；带 skip_reason 的文件不要审读。\n"
                + "2. 每个文件应用其 rules 字段列出的规则；规则为空则按通用标准（真实 bug/风险优先，不凑数）。\n"
                + "3. 发现问题时先修复代码，再走 gate_presubmit 提审——自查的目的是让提审一次通过。\n"
                + "4. 自查结论是咨询性的：它不进入门禁证据、不影响判决；门禁判决只来自 gate_review。";
    }

    /** 与引擎闸门同一份内建密钥路径名单——单一来源，两处口径永远一致。 */
    private static List<String> secretGlobs() {
        return BuiltinReviewEngine.SECRET_PATH_GLOBS;
    }
}
