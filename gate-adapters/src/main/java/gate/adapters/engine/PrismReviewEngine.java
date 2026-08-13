package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.ports.BlobStore;
import gate.ports.CostHint;
import gate.ports.ProcessRunner;
import gate.ports.ReviewEngine;
import gate.adapters.engine.PrismJson.PrismFinding;
import gate.adapters.engine.PrismJson.PrismLines;
import gate.adapters.engine.PrismJson.PrismLocation;
import gate.adapters.engine.PrismJson.PrismOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2's live review engine: shells out to the {@code prism} binary (架构落地执行文档 §5.3, §10.1.1).
 *
 * <p>This is the second implementation of {@link ReviewEngine} alongside {@link ManualReviewEngine},
 * and the point of the hexagonal design is that it changes <b>no core code</b>: it returns the same
 * {@code ReviewEvidence} values, and {@code GatePolicy} turns them into a verdict through the same
 * {@code decide()} path with no engine-specific branch.
 *
 * <p><b>Contract honoured: {@link #review} never throws.</b> Its body is the single
 * {@code try{...}catch(Throwable)} mandated by §5.3 / §8.4 item 6. Timeout, crash, bad JSON, a missing
 * field and a vanished binary all become an {@link EngineFailure} <em>value</em> that the policy
 * turns into a reject. A caller cannot "forget to catch" its way to a pass.
 *
 * <h3>Exit-code mapping (spike-结论 §1.4, corrected)</h3>
 * <ul>
 *   <li>{@code 0} → {@link EngineReport} (findings present but {@code --fail-on none} keeps the verdict
 *       in GatePolicy's hands — §5.3 constraint 1);</li>
 *   <li>{@code 1} → a {@code --fail-on} threshold was hit. With {@code --fail-on none} this is
 *       <em>configuration drift</em> and is treated as reject;</li>
 *   <li>{@code 2} → usage / flag drift → {@link EngineFailure}({@code CRASH}) → gate 22 (do not retry);</li>
 *   <li>{@code 3} → provider auth/config → {@link EngineFailure}({@code CRASH}) → gate 22 (do not retry);</li>
 *   <li>{@code 4} → runtime mixed bucket (git / provider network / IO / schema) →
 *       {@link EngineFailure}({@code CRASH}) → gate 20 (retryable, still reject). stderr first line is
 *       classified into the detail but no exit-code-level retry split is done (N5).</li>
 * </ul>
 *
 * <h3>coveredPaths (N6)</h3>
 * prism's JSON has <em>no</em> "files actually reviewed" field (verified against real output,
 * doc/p2-schema-核对.md §5). The adapter therefore sets {@code coveredPaths = changedPaths} (the full
 * input set) and {@code degraded = true}, per spike-结论 §4.4 #2 / N6. It never assumes full coverage.
 * {@code degraded=true} forces GatePolicy to reject — which is the correct fail-closed behaviour
 * until prism can prove coverage.
 *
 * <h3>Severity mapping (§5.3 constraint 3)</h3>
 * prism's vocabulary is {@code low/medium/high}; it is mapped through a per-engine table, never
 * assumed shared. An unknown word maps to {@link Severity#BLOCKER} and sets {@code degraded}.
 *
 * <h3>Secrets (ADR-9, §6.1, §10.1.1)</h3>
 * The API key is injected via the {@code OPENAI_API_KEY} environment variable and never appears in
 * argv (which is globally readable via {@code /proc/<pid>/cmdline}). The newapi gateway endpoint is
 * injected via {@code PRISM_OPENAI_BASE_URL} (prism 0.5.0's actual variable — see
 * doc/p2-schema-核对.md §2 deviation 3).
 */
public final class PrismReviewEngine implements ReviewEngine {

    public static final String ENGINE_ID = "prism";

    /** prism's {@code --provider} is a protocol family, not a provider row (§10.1.1). */
    private static final String PROVIDER_FAMILY = "openai";

    private final ProcessRunner processRunner;
    private final BlobStore blobStore;
    private final String prismBinary;
    private final Duration timeout;
    private final String providerId;
    private final String modelName;
    private final String baseUrl;        // provider.base_url, e.g. https://newapi.sakta.top/v1
    private final String apiKey;         // from .env, never logged, never in argv
    private final String engineVersion;  // resolved by the factory via `prism version` (see §2 deviation 2)

    public PrismReviewEngine(ProcessRunner processRunner, BlobStore blobStore,
                             String prismBinary, Duration timeout,
                             String providerId, String modelName,
                             String baseUrl, String apiKey, String engineVersion) {
        this.processRunner = processRunner;
        this.blobStore = blobStore;
        this.prismBinary = prismBinary;
        this.timeout = timeout;
        this.providerId = providerId;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.engineVersion = engineVersion == null ? "unknown" : engineVersion;
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(ENGINE_ID, engineVersion, argvFingerprint(), providerId, modelName);
    }

    /**
     * P4 cost telemetry: extracts {@code timing.totalMs}/{@code llmMs} from prism's JSON output.
     * prism does NOT expose usage/token fields (confirmed in doc/p2-schema-核对.md §3), so the
     * token source is always {@code "unavailable"} and token counts are null. The timing data
     * provides the degraded basis for the H1 verdict (review_round + diff_size + wall-clock).
     *
     * <p>This is bypass data — it never affects the verdict or blocks publish. If parsing fails, it
     * returns {@link CostHint#EMPTY} (no telemetry) rather than throwing.
     */
    @Override
    public java.util.Optional<CostHint> extractCost(ReviewEvidence evidence) {
        return evidence.accept(new EvidenceVisitor<java.util.Optional<CostHint>>() {
            @Override
            public java.util.Optional<CostHint> visit(EngineReport report) {
                try {
                    byte[] raw = blobStore.get(report.rawOutput());
                    String json = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                    PrismOutput out = PrismJson.parse(json);
                    return java.util.Optional.of(CostHint.timingOnly(out.totalMs, out.llmMs));
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

    /**
     * The single mandated {@code try{...}catch(Throwable)}. Every failure mode becomes a value.
     */
    @Override
    public ReviewEvidence review(ReviewRequest request) {
        EngineDescriptor descriptor = describe();
        try {
            return runPrism(request, descriptor);
        } catch (Throwable t) {
            // The port promises not to throw. Timeout / crash / bad JSON / missing field / vanished
            // binary all land here as an EngineFailure value that GatePolicy turns into a reject.
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "prism adapter failed: " + t, -1);
        }
    }

    private ReviewEvidence runPrism(ReviewRequest request, EngineDescriptor descriptor) {
        List<String> argv = buildArgv(request);
        Path cwd = request.cloneRepo().path();
        Map<String, String> env = buildEnv();

        Instant started = Instant.now();
        ProcessRunner.ProcRun run = processRunner.run(argv, cwd, env, timeout);
        Duration duration = Duration.between(started, Instant.now());

        BlobRef rawRef = persistRaw(run.stdout(), request);

        // Timeout: the runner already destroyed the tree; never interpret the OS exit as prism's own.
        if (run.timedOut()) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                    "prism exceeded " + timeout + "; stderr=" + run.stderrFirstLine(), run.exitCode());
        }

        switch (run.exitCode()) {
            case 0 -> {
                return parseReport(run.stdout(), descriptor, request, rawRef, run.exitCode(), duration);
            }
            case 1 -> {
                // --fail-on is fixed to none, so exit 1 is configuration drift (spike §1.4).
                return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                        "prism exit 1 with --fail-on none => configuration drift; stderr="
                                + run.stderrFirstLine(), 1);
            }
            case 2 -> {
                // usage / flag drift — adapter mis-built argv, or flag drifted across versions (N4).
                return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                        "prism usage error (flag drift?); stderr=" + run.stderrFirstLine(), 2);
            }
            case 3 -> {
                // provider auth/config — operational, do not retry (gate 22).
                return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                        "prism provider auth/config error; stderr=" + run.stderrFirstLine(), 3);
            }
            case 4 -> {
                // Mixed runtime bucket (N5): classify stderr first line into detail, no retry split.
                return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                        classifyExit4(run.stderrFirstLine()), 4);
            }
            default -> {
                return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                        "prism unexpected exit " + run.exitCode() + "; stderr=" + run.stderrFirstLine(),
                        run.exitCode());
            }
        }
    }

    private List<String> buildArgv(ReviewRequest request) {
        List<String> argv = new ArrayList<>();
        argv.add(prismBinary);
        argv.add("review");
        argv.add("commit");
        argv.add(request.danglingCommit().hex());
        argv.add("--parent");
        argv.add(request.snapshot().baseCommit().hex());
        argv.add("--provider");
        argv.add(PROVIDER_FAMILY);
        argv.add("--model");
        argv.add(modelName);
        argv.add("--format");
        argv.add("json");
        argv.add("--fail-on");
        argv.add("none");   // verdict authority stays in GatePolicy (§5.3 constraint 1)
        return argv;
    }

    private Map<String, String> buildEnv() {
        // Keys are injected via env, never argv (/proc/<pid>/cmdline is world-readable — §6.1).
        // prism 0.5.0 reads PRISM_OPENAI_BASE_URL (full endpoint) + OPENAI_API_KEY
        // (doc/p2-schema-核对.md §2 deviation 3).
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OPENAI_API_KEY", apiKey);
        env.put("PRISM_OPENAI_BASE_URL", baseUrl + "/chat/completions");
        return env;
    }

    /**
     * Parses prism's JSON into an {@link EngineReport}. Any structural problem is an
     * {@link EngineFailure}({@code UNPARSEABLE}) — the policy rejects, never passes.
     */
    private ReviewEvidence parseReport(String json, EngineDescriptor descriptor, ReviewRequest request,
                                       BlobRef rawRef, int exitCode, Duration duration) {
        PrismOutput out;
        try {
            out = PrismJson.parse(json);
        } catch (RuntimeException e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.UNPARSEABLE,
                    "prism JSON unparseable: " + e.getMessage()
                            + "; first 200 chars=" + truncate(json, 200), exitCode);
        }

        // N6: prism has no "files reviewed" field. Use the full input changedPaths and degrade.
        Set<String> covered = new LinkedHashSet<>(request.snapshot().changedPaths());
        boolean degraded = true;

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
            String message = composeMessage(pf.title, pf.message);
            findings.add(new Finding(sev, pf.severity == null ? "" : pf.severity,
                    path, lineStart, lineEnd, pf.id, message, pf.suggestion));
        }
        if (unknownSeverity) {
            degraded = true;
        }

        return new EngineReport(descriptor, request.snapshot().treeHash().hex(),
                findings, covered, degraded, rawRef, exitCode, duration);
    }

    /** low→INFO, medium→WARNING, high→BLOCKER; anything else→BLOCKER + degraded (§5.3 constraint 3). */
    private static Severity mapSeverity(String raw) {
        if (raw == null) {
            return Severity.BLOCKER;
        }
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> Severity.INFO;
            case "medium" -> Severity.WARNING;
            case "high" -> Severity.BLOCKER;
            default -> Severity.BLOCKER;
        };
    }

    private static String composeMessage(String title, String message) {
        if (title == null || title.isBlank()) {
            return message == null ? "" : message;
        }
        if (message == null || message.isBlank()) {
            return title;
        }
        return title + ": " + message;
    }

    private static String firstLocationPath(PrismFinding pf) {
        if (pf.locations != null && !pf.locations.isEmpty() && pf.locations.get(0).path != null) {
            return pf.locations.get(0).path;
        }
        return ".";
    }

    private static int[] firstLocationLines(PrismFinding pf) {
        if (pf.locations == null || pf.locations.isEmpty()) {
            return null;
        }
        PrismLocation loc = pf.locations.get(0);
        if (loc.lines == null) {
            return null;
        }
        int start = loc.lines.start == null ? 0 : loc.lines.start;
        int end = loc.lines.end == null ? start : loc.lines.end;
        return new int[]{start, end};
    }

    private BlobRef persistRaw(String raw, ReviewRequest request) {
        String safe = raw == null ? "" : raw;
        return blobStore.put(safe.getBytes(StandardCharsets.UTF_8),
                "raw/" + request.ticketNo() + "/" + request.reviewRound() + "/prism.json");
    }

    private String argvFingerprint() {
        // A stable digest of the argv shape (template minus per-ticket values), so historical results
        // stay comparable when flags drift across prism versions (N4).
        String template = prismBinary + "|review|commit|<sha>|--parent|<base>|--provider|"
                + PROVIDER_FAMILY + "|--model|" + modelName + "|--format|json|--fail-on|none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(template.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is mandated by the JLS; this is unreachable.
            return "uncomputed";
        }
    }

    /** Classifies an exit-4 stderr first line into a human-readable detail. Always rejects. */
    private static String classifyExit4(String stderrFirstLine) {
        String s = stderrFirstLine == null ? "" : stderrFirstLine.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("timeout") || s.contains("deadline")) {
            return "prism exit 4 (timeout): " + stderrFirstLine;
        }
        if (s.contains("auth") || s.contains("unauthor") || s.contains("401") || s.contains("403")) {
            return "prism exit 4 (provider auth): " + stderrFirstLine;
        }
        if (s.contains("network") || s.contains("dial") || s.contains("connect") || s.contains("dns")) {
            return "prism exit 4 (provider network): " + stderrFirstLine;
        }
        // Classification failure is still a reject (N5: fail-closed over retry precision).
        return "prism exit 4 (unclassified runtime): " + stderrFirstLine;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
