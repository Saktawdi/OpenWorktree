package gate.adapters.session;

import gate.application.util.MiniJson;
import gate.ports.session.AgentSessionPort.Attachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLAUDE 适配器的 stdin 输入编码（T-118）：一条用户消息 → stream-json 输入格式的一行 JSON。
 *
 * <p>claude 的 {@code --input-format=stream-json} 按行读 JSON 对象，一行一条用户消息：
 * <pre>{"type":"user","message":{"role":"user","content":[块, 块]}}</pre>
 * 内容块支持 {@code text} 与 {@code image}（base64 内联）。图片因此<b>只走 stdin、从不进
 * argv</b>——顺带绕开 claude.cmd 经 {@code cmd.exe /c} 启动时的命令行换行截断与 32767 字符
 * 上限（T-121 的根因）。
 *
 * <p>正文进 text 块（引用标记照旧原样保留）。唯一的例外是后端追加的 {@code [图片引用 #n]} 路径
 * 行：附件已经以 image 块随正文同行送达，Agent 不必再走「拿到路径 → 自己去 Read」那条绕道，
 * 该行对模型只是噪音，会诱导它去读一个已经直接看到的文件——所以只在本回合确实带了附件时剥掉
 * （见 {@link #stripImageRefLines}）。落库与历史视图用的是同一条原文，不受这里编码影响，
 * 路径行仍留在库里给 UI 还原缩略图；opencode 链路走 HTTP parts，压根不经过本类。
 *
 * <p>注意 {@link Attachment#dataBase64()} 是<b>裸 base64</b>（无 data-URL 前缀），这正是
 * claude image 块 {@code source.data} 要的形状；opencode 那条链路要的是 data URL，别混。
 */
public final class ClaudeStreamInput {

    /**
     * 后端追加的图片落盘引用行 token：{@code [图片引用 #n] <工作区相对路径>}。
     *
     * <p>形状与前端的 {@code IMAGE_CITE_TOKEN_RE}（gate-web-ui/shared/attachments.ts）对齐：
     * 标记后紧跟的路径 token 必须含 {@code . / \} 才吃掉，不含路径分隔符的后续文字一律保留——
     * 引用行与 CJK 正文同行时（旧版把引用插在光标处的遗留形态）行级过滤会把整行正文一起吞掉。
     * 这里只管后端自己追加的那种（{@code 图片引用}），Composer 侧的 {@code [图片 #n] <文件名>}
     * 不是文件路径、也不是绕道的一部分，保持原样。
     */
    private static final Pattern IMAGE_REF_RE =
            Pattern.compile("\\[图片引用 #\\d+\\][ \\t]*([A-Za-z0-9._\\-]*[./\\\\][A-Za-z0-9._\\-/]*)?");

    private ClaudeStreamInput() {
    }

    /**
     * 一条用户消息 → stream-json 输入行（含行尾换行）。
     *
     * <p>正文里的换行由 {@link MiniJson} 转义成 {@code \n}，所以整行只有一个物理换行（行尾）
     * ——按行读的 stream-json 不会被正文破帧。
     */
    public static String line(String text, List<Attachment> attachments) {
        List<Object> content = new ArrayList<>();
        String body = stripImageRefLines(text, attachments);
        if (body != null && !body.isEmpty()) {
            content.add(Map.of("type", "text", "text", body));
        }
        if (attachments != null) {
            for (Attachment a : attachments) {
                content.add(Map.of("type", "image", "source", Map.of(
                        "type", "base64", "media_type", a.mime(), "data", a.dataBase64())));
            }
        }
        if (content.isEmpty()) {
            // 空 content 数组不是合法输入：既无正文又无附件时兜一个空 text 块。
            content.add(Map.of("type", "text", "text", ""));
        }
        return MiniJson.write(Map.of("type", "user",
                "message", Map.of("role", "user", "content", content))) + "\n";
    }

    /**
     * 剥掉发往 CLI 的正文里的图片落盘引用行。附件已编成 image 块随行送达，路径行是多余的绕道。
     *
     * <p><b>只在本回合真的带了附件时剥离</b>：引用行是附件流程自己追加的
     * （{@code SessionController.appendChatImageRefs}），没有附件就没有这条行；用户手打的同形
     * 文本不该被吞。落库的原文本不经这里，历史缩略图照旧靠它还原。
     *
     * <p>整行只有引用行 → 连行带换行一起删；引用行与正文同行 → 只删 token，保留正文。空正文
     * 留给调用方兜底（{@code line()} 会退化成空 text 块）。
     */
    private static String stripImageRefLines(String text, List<Attachment> attachments) {
        if (text == null || text.isEmpty() || attachments == null || attachments.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean firstKept = true;
        int from = 0;
        while (from <= text.length()) {
            int nl = text.indexOf('\n', from);
            String raw = nl < 0 ? text.substring(from) : text.substring(from, nl);
            String stripped = IMAGE_REF_RE.matcher(raw).replaceAll("");
            // 长度变短即「本行确有引用 token」。
            boolean hadRef = stripped.length() != raw.length();
            if (hadRef) {
                // 行内引用（旧版遗留）摘掉后收拾留下的空格；整行丢弃的分支用不上。
                stripped = stripped.replaceAll("[ \t]{2,}", " ").stripTrailing();
            }
            boolean pureRefLine = hadRef && stripped.isBlank();
            if (!pureRefLine) {
                if (!firstKept) {
                    out.append('\n');
                }
                firstKept = false;
                out.append(stripped);
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return out.toString();
    }
}
