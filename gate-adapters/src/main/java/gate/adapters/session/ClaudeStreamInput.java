package gate.adapters.session;

import gate.application.util.MiniJson;
import gate.ports.session.AgentSessionPort.Attachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CLAUDE 适配器的 stdin 输入编码（T-118）：一条用户消息 → stream-json 输入格式的一行 JSON。
 *
 * <p>claude 的 {@code --input-format=stream-json} 按行读 JSON 对象，一行一条用户消息：
 * <pre>{"type":"user","message":{"role":"user","content":[块, 块]}}</pre>
 * 内容块支持 {@code text} 与 {@code image}（base64 内联）。图片因此<b>只走 stdin、从不进
 * argv</b>——顺带绕开 claude.cmd 经 {@code cmd.exe /c} 启动时的命令行换行截断与 32767 字符
 * 上限（T-121 的根因）。
 *
 * <p>正文原样进 text 块（引用标记、后端追加的 {@code [图片引用 #n]} 路径行都在里面）：落库与
 * 历史视图用的是同一条原文，不受这里编码影响；图片路径行与 image 块并存，前者给 UI 还原
 * 缩略图、也给 Agent 一个原图路径。
 *
 * <p>注意 {@link Attachment#dataBase64()} 是<b>裸 base64</b>（无 data-URL 前缀），这正是
 * claude image 块 {@code source.data} 要的形状；opencode 那条链路要的是 data URL，别混。
 */
public final class ClaudeStreamInput {

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
        if (text != null && !text.isEmpty()) {
            content.add(Map.of("type", "text", "text", text));
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
}
