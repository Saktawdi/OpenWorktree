package gate.web.sse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE 响应头统一口径（会话 / 任务 / LLM 三处流式端点共用）。
 *
 * <p><b>禁设 {@code Connection: close}</b>——Jetty 会按 Connection 响应头立刻关连接，
 * 浏览器 EventSource 的断流重连永远追不上一条已经 closed 的连接（多会话后台失活的
 * 直接诱因，P0-2）；连接生命周期交还给 Jetty 的 keep-alive 管理。头集合由
 * {@code SseResponseHeadersTest} 钉死，回归即红。
 */
public final class SseResponseHeaders {

    private SseResponseHeaders() {
    }

    /** 置状态与流式头集合；不含 flushBuffer（各端点的缓冲语义不同）。 */
    public static void configure(HttpServletResponse res) {
        res.setStatus(200);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("text/event-stream");
        res.addHeader("Cache-Control", "no-cache");
        res.addHeader("X-Accel-Buffering", "no");
    }
}
