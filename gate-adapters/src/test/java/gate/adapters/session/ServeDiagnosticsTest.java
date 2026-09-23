package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 两条诊断链路：serve 遗言尾部（异常里带上真实死因）与端口 bind 预检（内核排除段的唯一识别
 * 手段）。两者都是「排查方向被端口二字带偏」这场事故的直接产物——真实原因在遗言文件里，而
 * 端口看起来既空闲又无辜。
 */
class ServeDiagnosticsTest {

    @Test
    void serve_log_tail_keeps_the_last_lines_and_strips_ansi() throws Exception {
        Path log = Files.createTempFile("serve-tail-", ".log");
        Files.writeString(log, """
                Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured.
                \u001B[91m\u001B[1mError: \u001B[0mConfiguration is invalid at /home/u/.config/opencode/opencode.jsonc
                ↳ Missing key provider.ttapi.models.qwen3.8-max.limit.output
                """, StandardCharsets.UTF_8);

        String tail = OpenCodeServeAdapter.tailOfServeLog(log);

        assertEquals("Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured. | "
                        + "Error: Configuration is invalid at /home/u/.config/opencode/opencode.jsonc | "
                        + "↳ Missing key provider.ttapi.models.qwen3.8-max.limit.output",
                tail);
        assertFalse(tail.contains("\u001B"), "ANSI 色码必须剥掉，否则 UI 里是一串乱码");
    }

    @Test
    void serve_log_tail_is_empty_for_a_missing_file() {
        assertEquals("", OpenCodeServeAdapter.tailOfServeLog(
                Path.of(System.getProperty("java.io.tmpdir"), "no-such-serve-log-" + System.nanoTime())));
    }

    @Test
    void serve_log_tail_survives_a_log_larger_than_the_window() throws Exception {
        Path log = Files.createTempFile("serve-tail-big-", ".log");
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            noise.append("noise line ").append(i).append('\n'); // 远超 8KB 回看窗口
        }
        noise.append("↳ Missing key provider.ttapi.models.qwen3.8-max.limit.output\n");
        Files.writeString(log, noise, StandardCharsets.UTF_8);

        String[] segments = OpenCodeServeAdapter.tailOfServeLog(log).split(" \\| ");

        assertEquals("↳ Missing key provider.ttapi.models.qwen3.8-max.limit.output",
                segments[segments.length - 1], "死因必须是最后一行");
        for (String segment : segments) {
            assertTrue(segment.matches("noise line \\d+") || segment.startsWith("↳ Missing key"),
                    "窗口首行可能是半行，必须整行丢弃——不得把截断的碎片当成证据: " + segment);
        }
    }

    @Test
    void bindable_is_false_while_another_socket_holds_the_port() throws Exception {
        try (ServerSocket holder = new ServerSocket()) {
            holder.setReuseAddress(false);
            holder.bind(new InetSocketAddress("127.0.0.1", 0));

            assertFalse(OpenCodeServeAdapter.bindable(holder.getLocalPort()),
                    "已被监听的端口必须判为不可用——这正是只看 /health 会漏掉的场景");
        }
    }

    @Test
    void bindable_is_true_for_a_released_port() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            port = probe.getLocalPort();
        }
        // socket 关闭到可重新 bind 有毫秒级延迟，给几拍。
        assertTrue(bindableWithinAMoment(port), "端口释放后应当可用");
    }

    private static boolean bindableWithinAMoment(int port) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            if (OpenCodeServeAdapter.bindable(port)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}