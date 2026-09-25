package gate.adapters.engine;

import gate.adapters.engine.DiffSections.HunkLine;
import gate.adapters.engine.DiffSections.Section;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性回锚器：用 finding 携带的 {@code existing_code}（逐字摘录）在 diff 中重新推导行号。
 *
 * <p>LLM 报告的行号会漂移——这是通用 Agent 审查的三大顽疾之一（OCR 工程总结）。治法不是更好的
 * prompt，而是不信任行号：模型只被要求逐字摘录它指认的代码，行号由这里用代码从 diff 里算出来。
 * 回锚只做字符串精确匹配（滑窗、忽略空行、剥离 +/- 前缀与首尾空白），零 LLM 参与，因此结果可
 * 复现、可审计。
 *
 * <p>匹配次序（OCR 同款）：先新侧（context+added → 新文件行号，审查重点），再旧侧（context+deleted
 * → 旧文件行号，覆盖"删掉的代码不该回来"类发现）。全部不中时保留模型给的行号并返回 false——
 * 摘录可能来自 diff 之外的上下文，此时模型行号仍是唯一的证据，不能丢弃。
 */
final class FindingAnchor {

    private FindingAnchor() {
    }

    /** 单条 existing_code 的回锚上限：超长摘录通常意味着模型抄了整个函数而非指认点。 */
    private static final int MAX_EXCERPT_CHARS = 2000;

    /**
     * @return {@code int[]{start, end}}（回锚成功）或 {@code null}（无法回锚，调用方保留原行号）
     */
    static int[] anchor(String existingCode, String path, List<Section> sections) {
        if (existingCode == null || existingCode.isBlank()
                || existingCode.length() > MAX_EXCERPT_CHARS) {
            return null;
        }
        List<String> target = normalize(existingCode);
        if (target.isEmpty()) {
            return null;
        }
        Section section = null;
        for (Section s : sections) {
            if (path.equals(s.path()) || path.equals(s.oldPath())) {
                section = s;
                break;
            }
        }
        if (section == null) {
            return null;
        }
        List<HunkLine> lines = DiffSections.hunkLines(section);
        int[] hit = matchSide(lines, target, true);
        if (hit != null) {
            return hit;
        }
        return matchSide(lines, target, false);
    }

    /** 在一侧上滑窗匹配。newSide=true 取 context+added（新文件行号），否则取 context+deleted。 */
    private static int[] matchSide(List<HunkLine> lines, List<String> target, boolean newSide) {
        List<String> texts = new ArrayList<>();
        List<Integer> lineNos = new ArrayList<>();
        for (HunkLine l : lines) {
            Integer lineNo = newSide ? (l.newLine() > 0 ? l.newLine() : null)
                    : (l.oldLine() > 0 ? l.oldLine() : null);
            if (lineNo == null) {
                continue;
            }
            String normalized = normalizeLine(l.content());
            if (normalized.isEmpty()) {
                continue;   // 空行不参与匹配（与摘录侧同口径）
            }
            texts.add(normalized);
            lineNos.add(lineNo);
        }
        if (texts.size() < target.size()) {
            return null;
        }
        for (int i = 0; i <= texts.size() - target.size(); i++) {
            boolean all = true;
            for (int j = 0; j < target.size(); j++) {
                if (!texts.get(i + j).equals(target.get(j))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return new int[]{lineNos.get(i), lineNos.get(i + target.size() - 1)};
            }
        }
        return null;
    }

    /** 摘录 → 归一化行序列：剥 CRLF、去空行、去首尾空白与 +/- diff 前缀。 */
    private static List<String> normalize(String code) {
        List<String> out = new ArrayList<>();
        for (String raw : code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String n = normalizeLine(raw);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    private static String normalizeLine(String s) {
        String t = s.trim();
        if (t.startsWith("+") || t.startsWith("-")) {
            t = t.substring(1).trim();
        }
        return t;
    }
}
