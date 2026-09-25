package gate.adapters.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 unified diff 切成按文件的 section——审前闸门（确定性工程）的地基。
 *
 * <p>引擎过去把整包 diff 原样塞进一个 prompt：单个超大文件足以打爆上下文窗口，二进制/密钥文件
 * 也一并烧 token。切片之后，闸门可以按文件做确定性决策（跳过/限量/分组），LLM 只见到被准许的
 * 内容。这里只做解析，不做任何决策——决策在引擎与规则层，解析失败宁可整轮失败也不猜测。
 *
 * <p>估算口径：token ≈ chars/4（{@link #estimateTokens}）。这是量级估计而非计费口径，用途只有
 * 一个——在发送前拒绝明显超限的内容，真实 usage 仍以 SSE 尾帧为准。
 */
final class DiffSections {

    private DiffSections() {
    }

    /** diff 中一个按文件隔离的 section（含 {@code diff --git} 头到下一个文件头之间的全部行）。 */
    record Section(String path, String oldPath, String body, boolean binary, boolean deleted, boolean newFile) {

        /** 该文件在本侧仍有内容（非删除）才值得送审。 */
        boolean reviewable() {
            return !deleted && !binary;
        }
    }

    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** 按 {@code diff --git} 行切分；空 diff 返回空列表。路径取 {@code b/} 侧（重命名时为 rename to）。 */
    static List<Section> split(String diff) {
        List<Section> sections = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return sections;
        }
        String[] lines = diff.split("\n", -1);
        int start = -1;
        String aPath = null, bPath = null;
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : null;
            Matcher m = line == null ? null : DIFF_GIT.matcher(line);
            if (m != null && m.matches()) {
                if (start >= 0) {
                    sections.add(build(lines, start, i, aPath, bPath));
                }
                start = i;
                aPath = unquote(m.group(1));
                bPath = unquote(m.group(2));
            }
        }
        if (start >= 0) {
            sections.add(build(lines, start, lines.length, aPath, bPath));
        }
        return sections;
    }

    private static Section build(String[] lines, int from, int to, String aPath, String bPath) {
        boolean binary = false;
        boolean deleted = false;
        boolean newFile = false;
        for (int i = from; i < to; i++) {
            String l = lines[i];
            if (l.startsWith("Binary files ") || l.equals("GIT binary patch")) {
                binary = true;
            } else if (l.startsWith("deleted file mode ")) {
                deleted = true;
            } else if (l.startsWith("new file mode ")) {
                newFile = true;
            } else if (l.startsWith("rename to ")) {
                bPath = unquote(l.substring("rename to ".length()));
            } else if (l.startsWith("rename from ")) {
                aPath = unquote(l.substring("rename from ".length()));
            }
        }
        // 删除文件的 +++ 侧是 /dev/null，真实路径在 --- 侧；其余以 b/ 侧为准。
        StringBuilder body = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        if (deleted) {
            return new Section(aPath == null ? bPath : aPath, aPath, body.toString(), binary, true, newFile);
        }
        return new Section(bPath == null ? aPath : bPath, aPath, body.toString(), binary, deleted, newFile);
    }

    /** 与 {@code git diff} 的引号路径约定最小兼容：带引号则剥引号并还原反斜杠转义。 */
    private static String unquote(String p) {
        if (p == null) {
            return null;
        }
        p = p.trim();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            String inner = p.substring(1, p.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return p;
    }

    /** 量级估算：token ≈ chars/4（向上取整）。只用于发送前的闸门判断，不是计费口径。 */
    static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }

    /**
     * 从一个 section 的 body 中解析 hunk（供回锚使用）：按顺序给出每行及其在新/旧两侧的行号。
     * 无法解析的行（文件头等）被跳过——回锚只消费 hunk 行。
     */
    record HunkLine(int oldLine, int newLine, char marker, String content) {
    }

    static List<HunkLine> hunkLines(Section section) {
        List<HunkLine> out = new ArrayList<>();
        int oldLine = -1, newLine = -1;
        for (String raw : section.body.split("\n", -1)) {
            Matcher m = HUNK.matcher(raw);
            if (m.matches()) {
                oldLine = Integer.parseInt(m.group(1));
                newLine = Integer.parseInt(m.group(3));
                continue;
            }
            if (oldLine < 0 || raw.isEmpty() || raw.length() < 1) {
                continue;
            }
            char marker = raw.charAt(0);
            String content = raw.substring(1);
            switch (marker) {
                case ' ' -> {
                    out.add(new HunkLine(oldLine++, newLine++, marker, content));
                }
                case '+' -> {
                    out.add(new HunkLine(-1, newLine++, marker, content));
                }
                case '-' -> {
                    out.add(new HunkLine(oldLine++, -1, marker, content));
                }
                default -> {
                    // '\ No newline at end of file' 等标记行跳过
                }
            }
        }
        return out;
    }
}
