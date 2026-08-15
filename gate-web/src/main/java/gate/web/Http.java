package gate.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small helpers for reading a request body and writing a JSON response, plus the token-masking
 * access log (执行文档-后端-web §3.4.1: the {@code ?token=} query value must never be logged in the
 * clear).
 */
final class Http {

    private Http() {
    }

    static byte[] readBody(HttpExchange exchange) throws IOException {
        return exchange.getRequestBody().readAllBytes();
    }

    static String readBodyString(HttpExchange exchange) throws IOException {
        return new String(readBody(exchange), StandardCharsets.UTF_8);
    }

    static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Masks the {@code token} query value in a raw query string for access logging (§3.4.1). E.g.
     * {@code a=1&token=deadbeef&b=2} → {@code a=1&token=***&b=2}.
     */
    static String maskToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] pairs = rawQuery.split("&");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals("token")) {
                sb.append("token=***");
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    /** Builds the access-log line for a request, with the token query value masked (§3.4.1). */
    static String accessLine(HttpExchange exchange, int status) {
        String path = exchange.getRequestURI().getPath();
        String query = maskToken(exchange.getRequestURI().getRawQuery());
        String suffix = query.isEmpty() ? "" : "?" + query;
        return exchange.getRequestMethod() + " " + path + suffix + " -> " + status;
    }
}
