package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.web.sse.SseResponseHeaders;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SSE 响应头统一口径（P0-2 回归钉）：三处流式端点共用同一组头，绝不能出现
 * {@code Connection: close}——Jetty 会按该头立即关连接，浏览器 EventSource 的断流
 * 重连永远追不上（多会话后台失活的直接诱因）；keep-alive 由 Jetty 自管，不显式设头。
 */
class SseResponseHeadersTest {

    @Test
    void sse_headers_pin_keepalive_semantics() {
        Map<String, Object> calls = new LinkedHashMap<>();
        List<String[]> headers = new ArrayList<>();
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setStatus" -> calls.put("status", args[0]);
                    case "setCharacterEncoding" -> calls.put("encoding", args[0]);
                    case "setContentType" -> calls.put("contentType", args[0]);
                    case "addHeader" -> {
                        headers.add(new String[]{(String) args[0], (String) args[1]});
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });

        SseResponseHeaders.configure(res);

        assertEquals(200, calls.get("status"));
        assertEquals("UTF-8", calls.get("encoding"));
        assertEquals("text/event-stream", calls.get("contentType"));
        assertTrue(headers.stream().anyMatch(h ->
                        "Cache-Control".equals(h[0]) && "no-cache".equals(h[1])),
                "Cache-Control: no-cache 必须保留");
        assertTrue(headers.stream().anyMatch(h ->
                        "X-Accel-Buffering".equals(h[0]) && "no".equals(h[1])),
                "X-Accel-Buffering: no 必须保留");
        assertTrue(headers.stream().noneMatch(h -> "Connection".equalsIgnoreCase(h[0])),
                "SSE 响应不得携带 Connection 头：close 让 Jetty 立即断流（EventSource 重连永远追不上），"
                        + "keep-alive 交给 Jetty 自管");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
