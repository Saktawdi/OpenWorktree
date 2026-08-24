package gate.web;

import io.javalin.http.Context;

/**
 * Small helpers for request body, responses and access logging.
 */
public final class Http {

    private Http() {
    }

    public static String maskToken(String rawQuery) {
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

    public static String accessLine(Context ctx, int status) {
        String path = ctx.path();
        String query = maskToken(ctx.queryString());
        String suffix = query.isEmpty() ? "" : "?" + query;
        return ctx.method() + " " + path + suffix + " -> " + status;
    }
}
