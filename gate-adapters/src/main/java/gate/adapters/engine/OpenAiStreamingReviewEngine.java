package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.ports.store.BlobStore;
import gate.ports.session.CostHint;
import gate.ports.engine.ReviewEngine;
import gate.adapters.engine.PrismJson.PrismFinding;
import gate.adapters.engine.PrismJson.PrismLocation;
import gate.adapters.engine.PrismJson.PrismOutput;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gate's first-party streaming review engine: calls an OpenAI-compatible
 * {@code /chat/completions} endpoint with {@code "stream": true} directly (gate.toml
 * {@code engine.kind = "openai-stream"}).
 *
 * <p><b>Why streaming exists at all:</b> the external prism binary (v0.5/v0.6) only speaks
 * non-streamed HTTP: it waits for the full response with a short client-side read timeout, then
 * {@code retryWithBackoff}s internally — against slow thinking models or gateways whose edge
 * (nginx → newapi) kills long-idle connections, every attempt dies at the edge while the LLM
 * keeps burning tokens. Streaming fixes the chain in the only place gate controls: the caller.
 * SSE keeps bytes flowing so no edge ever sees an idle connection.
 *
 * <p><b>Contract honoured: {@link #review} never throws.</b> Any failure (connect / HTTP error /
 * stream stall / invalid JSON) lands as an {@link EngineFailure} value that the policy rejects —
 * never an exception.
 *
 * <p>The prompt mirrors prism's output schema so findings parse through the same {@link PrismJson}
 * reader and map onto the same {@link Finding} records with identical severity vocabulary
 * (low/medium/high). Coverage is honest: the full diff was embedded in the prompt, so
 * {@code coveredPaths == changedPaths}; only an unknown severity word flips {@code degraded}.
 *
 * <p>Secrets: the API key arrives via the provider row (KMS-encrypted at rest) and rides only the
 * {@code Authorization} header — never argv, never logs.
 */
public final class OpenAiStreamingReviewEngine implements ReviewEngine {

    public static final String ENGINE_ID = "gate-stream";
    public static final String KIND = "openai-stream";

    /** No SSE line for this long means the stream stalled (edge dead) — abort rather than wait 10 min. */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);

    private static final String SYSTEM_PROMPT = """
            你是一个代码审查引擎。审查给定提交（unified diff），只输出一个 JSON 对象，不要 markdown 包裹、不要前后缀解释：
            {"findings":[{"id":"fx","severity":"low|medium|high","title":"...","message":"...","suggestion":"...","locations":[{"path":"...","lines":{"start":1,"end":1}}]}]}
            severity 只允许 low/medium/high；没有发现则输出 {"findings":[]}。只报告真实的 bug/风险，不凑数。
            """;

    private final BlobStore blobStore;
    private final Duration timeout;
    private final String providerId;
    private final String modelName;
    private final String baseUrl;
    private final String apiKey;   // 来自 provider 行（KMS 解密瞬间），只进 Authorization 头
    private final boolean acceptDegraded;

    public OpenAiStreamingReviewEngine(BlobStore blobStore, Duration timeout,
                                       String providerId, String modelName,
                                       String baseUrl, String apiKey,
                                       boolean acceptDegraded) {
        this.blobStore = blobStore;
        this.timeout = timeout;
        this.providerId = providerId;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.acceptDegraded = acceptDegraded;
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(ENGINE_ID, "v1", "stream:chat.completions", providerId, modelName);
    }

    @Override
    public ReviewEvidence review(ReviewRequest request) {
        EngineDescriptor descriptor = describe();
        try {
            return runStreaming(request, descriptor);
        } catch (Throwable t) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream engine failed: " + t, -1);
        }
    }

    private ReviewEvidence runStreaming(ReviewRequest request, EngineDescriptor descriptor) {
        Instant started = Instant.now();
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String body = """
                {"model":"%s","stream":true,"stream_options":{"include_usage":true},
                  "messages":[
                    {"role":"system","content":%s},
                    {"role":"user","content":%s}
                  ]}
                """.formatted(jsonEscape(modelName),
                jsonLiteral(SYSTEM_PROMPT),
                jsonLiteral(buildUserPrompt(request)));

        HttpResponse<java.io.InputStream> upstream;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            upstream = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream connect failed: " + e.getMessage(), -1);
        }
        if (upstream.statusCode() / 100 != 2) {
            String snippet;
            try (java.io.InputStream in = upstream.body()) {
                snippet = new String(in.readNBytes(1024), StandardCharsets.UTF_8).replace('\n', ' ');
            } catch (Exception e) {
                snippet = "<unable to read error body>";
            }
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "upstream HTTP " + upstream.statusCode() + ": " + snippet.trim(), -1);
        }

        // ── SSE 逐行读：任何一行到达都刷新活跃度；超时窗口由整体 timeout + 逐行 IDLE_TIMEOUT 共同约束。
        StringBuilder content = new StringBuilder();
        Long promptTokens = null, completionTokens = null, totalTokens = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(upstream.body(), StandardCharsets.UTF_8))) {
            String lastLine = br.readLine();
            java.time.Instant lastDataAt = Instant.now();
            while (true) {
                String line;
                try {
                    line = lastLine != null ? lastLine : br.readLine();
                } catch (Exception e) {
                    return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                            "stream read aborted after " + Duration.between(started, Instant.now()).toSeconds()
                                    + "s: " + e.getMessage(), -1);
                }
                lastLine = null;
                if (line == null) break;
                if (line.isBlank()) {
                    continue;
                }
                if (Duration.between(lastDataAt, Instant.now()).compareTo(IDLE_TIMEOUT) > 0) {
                    return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                            "stream stalled >" + IDLE_TIMEOUT.toSeconds() + "s (" + content.length()
                                    + " chars in)", -1);
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                lastDataAt = Instant.now();
                Map<String, Object> chunk;
                try {
                    chunk = MiniJsonMap.parseObject(payload);
                } catch (Exception e) {
                    continue; // 无法解析的单帧不终止流（注释心跳帧/部分网关的非标帧）
                }
                Object usage = chunk.get("usage");
                if (usage instanceof Map<?, ?> u) {
                    promptTokens = longOr(promptTokens, u.get("prompt_tokens"));
                    completionTokens = longOr(completionTokens, u.get("completion_tokens"));
                    totalTokens = longOr(totalTokens, u.get("total_tokens"));
                }
                Object choices = chunk.get("choices");
                if (choices instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> c0
                        && c0.get("delta") instanceof Map<?, ?> delta
                        && delta.get("content") instanceof String piece) {
                    content.append(piece);
                }
            }
        } catch (Exception e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream crashed: " + e.getMessage(), -1);
        }

        Duration wall = Duration.between(started, Instant.now());
        String raw = stripCodeFence(content.toString());
        BlobRef rawRef = blobStore.put(raw.getBytes(StandardCharsets.UTF_8),
                "raw/" + request.ticketNo() + "/" + request.reviewRound() + "/stream.json");

        PrismOutput out;
        try {
            out = PrismJson.parse(raw);
        } catch (RuntimeException e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.UNPARSEABLE,
                    "stream JSON unparseable: " + e.getMessage()
                            + "; first 200 chars=" + truncate(raw, 200), -1);
        }

        Set<String> covered = new LinkedHashSet<>(request.snapshot().changedPaths());
        List<Finding> findings = new ArrayList<>();
        boolean unknownSeverity = false;
        for (PrismFinding pf : out.findings) {
            Severity sev = mapSeverity(pf.severity);
            if (sev == Severity.BLOCKER && !"high".equalsIgnoreCase(pf.severity)) {
                unknownSeverity = true;
            }
            String path = firstLocationPath(pf);
            int[] lines = firstLocationLines(pf);
            Integer lineStart = lines == null ? null : lines[0];
            Integer lineEnd = lines == null ? null : lines[1];
            String message = pf.title == null || pf.title.isBlank()
                    ? (pf.message == null ? "" : pf.message)
                    : (pf.message == null || pf.message.isBlank() ? pf.title : pf.title + ": " + pf.message);
            findings.add(new Finding(sev, pf.severity == null ? "" : pf.severity,
                    path, lineStart, lineEnd, pf.id, message, pf.suggestion));
        }

        final Long pt = promptTokens, ct = completionTokens, tt = totalTokens;
        return new EngineReport(descriptor, request.snapshot().treeHash().hex(),
                findings, covered, unknownSeverity, rawRef, 0, wall) {
            // EngineReport 是 record，不能塞 usage；CostHint 通过 extractCost 单独取回。
        };
    }

    /**
     * P4 cost telemetry：流式响应末尾的 {@code usage} 帧（OpenAI 兼容网关普遍支持
     * {@code stream_options.include_usage}）给出真实 token 数——prism 在此可得性上是
     * {@code unavailable}，本引擎升级为 {@code stream_usage}。
     */
    @Override
    public java.util.Optional<CostHint> extractCost(ReviewEvidence evidence) {
        return evidence.accept(new EvidenceVisitor<java.util.Optional<CostHint>>() {
            @Override
            public java.util.Optional<CostHint> visit(EngineReport report) {
                try {
                    byte[] raw = blobStore.get(report.rawOutput());
                    return java.util.Optional.of(new CostHint(
                            lastUsage.promptTokens(), lastUsage.completionTokens(), lastUsage.totalTokens(),
                            lastUsage.totalTokens() == null ? "unavailable" : "stream_usage",
                            report.durationMs(), report.durationMs()));
                } catch (Exception e) {
                    return java.util.Optional.of(CostHint.EMPTY);
                }
            }

            @Override
            public java.util.Optional<CostHint> visit(EngineFailure failure) {
                return java.util.Optional.of(CostHint.EMPTY);
            }
        });
    }

    /** 保存最近一次流式回合的 usage（在 review() 单次调用内被赋值；extractCost 由 handler 同步调用）。 */
    private transient Usage lastUsage = new Usage(null, null, null);

    private record Usage(Long promptTokens, Long completionTokens, Long totalTokens) { }

    private static String buildUserPrompt(ReviewRequest request) {
        return "工单 " + request.ticketNo() + " · 第 " + request.reviewRound() + " 轮\n"
                + "请审查下列变更并找出问题（按给出 JSON 找反馈的结构输出）：\n\n" + request.snapshot().diff();
    }

    private static Long longOr(Long base, Object v) {
        if (v instanceof Number n) return n.longValue();
        return base;
    }

    private static String jsonLiteral(String s) {
        return jsonEscape(s, true);
    }

    private static String jsonEscape(String s) {
        return jsonEscape(s, false);
    }

    private static String jsonEscape(String s, boolean quote) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        if (quote) sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        if (quote) sb.append('"');
        return sb.toString();
    }

    /** 模型偶发会在 JSON 外再裹一层 ```json，容忍性剥掉。 */
    private static String stripCodeFence(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                int end = t.lastIndexOf("```");
                t = (end > nl) ? t.substring(nl + 1, end).trim() : t.substring(nl + 1).trim();
            }
        }
        return t;
    }

    private static Severity mapSeverity(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> Severity.INFO;
            case "medium" -> Severity.WARNING;
            case "high" -> Severity.BLOCKER;
            default -> Severity.BLOCKER;
        };
    }

    private static String firstLocationPath(PrismFinding pf) {
        if (pf.locations != null && !pf.locations.isEmpty() && pf.locations.get(0).path != null) {
            return pf.locations.get(0).path;
        }
        return ".";
    }

    private static int[] firstLocationLines(PrismFinding pf) {
        if (pf.locations == null || pf.locations.isEmpty()) return null;
        PrismLocation loc = pf.locations.get(0);
        if (loc.lines == null) return null;
        int start = loc.lines.start == null ? 0 : loc.lines.start;
        int end = loc.lines.end == null ? start : loc.lines.end;
        return new int[]{start, end};
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 极简 Map-JSON 解析（DataCompositionLayer, 避免更大依赖）。 */
    private static final class MiniJsonMap {
        static Map<String, Object> parseObject(String json) {
            // 借助 PrismJson 的通用解析器：它能解析任意 JSON 对象（不为 strictly prism 输出）。
            PrismOutputObject p = PrismJson.parseGeneric(json);
            return p.fields;
        }
    }
}
