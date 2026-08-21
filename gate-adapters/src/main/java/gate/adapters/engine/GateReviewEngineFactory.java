package gate.adapters.engine;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.BlobStore;
import gate.ports.ProcessRunner;
import gate.ports.ProviderRepository;
import gate.ports.ReviewEngine;
import gate.ports.ReviewEngineFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Assembles the right {@link ReviewEngine} for a review round (架构落地执行文档 §5.3, §10.1.1).
 *
 * <p>This is the single place that knows how to build the prism adapter. It resolves, at assembly
 * time, the three things prism needs at runtime:
 * <ol>
 *   <li><b>base_url</b> — from the {@code provider} table row referenced by
 *       {@link GateConfig.EngineConfig#providerId()};</li>
 *   <li><b>api key</b> — from the project-root {@code .env} (never the DB, never argv);</li>
 *   <li><b>engine version</b> — by probing {@code prism version} once, so every
 *       {@link ReviewEngine#describe()} call is cheap and side-effect-free.</li>
 * </ol>
 *
 * <p>The manual verdict adapter is still available for rounds driven by a human decision, which is
 * how A6 exercises the GatePolicy blocker branch without depending on prism's coverage behaviour.
 */
public final class GateReviewEngineFactory implements ReviewEngineFactory {

    private final BlobStore blobStore;
    private final ManualReviewEngineFactory manual;
    private final GateConfig config;
    private final ProcessRunner processRunner;
    private final ProviderRepository providers;
    private final Path envFile;

    public GateReviewEngineFactory(BlobStore blobStore, GateConfig config, ProcessRunner processRunner,
                                   ProviderRepository providers, Path envFile) {
        this.blobStore = blobStore;
        this.manual = new ManualReviewEngineFactory(blobStore);
        this.config = config;
        this.processRunner = processRunner;
        this.providers = providers;
        this.envFile = envFile;
    }

    @Override
    public ReviewEngine forManualVerdict(Boolean pass, String note) {
        return manual.forManualVerdict(pass, note);
    }

    @Override
    public ReviewEngine forPrism() {
        if (!config.engineConfigured()) {
            return null;
        }
        GateConfig.EngineConfig engine = config.engine();
        ProviderRepository.ProviderRow provider = providers.find(engine.providerId())
                .orElseThrow(() -> new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "engine.provider_id=" + engine.providerId()
                                + " has no matching provider row (run `gate provider add`)"));
        String apiKey = resolveApiKey();
        String version = resolvePrismVersion(engine.cmd());

        return new PrismReviewEngine(
                processRunner, blobStore,
                engine.cmd(), Duration.ofSeconds(engine.timeoutSeconds()),
                engine.providerId(), engine.model(),
                provider.baseUrl(), apiKey, version,
                config.policy().engineAcceptDegraded());
    }

    /**
     * The API key lives in {@code .env} (git-ignored), never in the DB. The provider row holds only a
     * reference ({@code api_key_ref}) declaring which env var carries it. For the newapi provider the
     * reference is {@code NEWAPI_API_KEY}; this reader returns its value.
     */
    private String resolveApiKey() {
        Map<String, String> env = EnvFile.load(envFile);
        String key = env.get("NEWAPI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "NEWAPI_API_KEY is missing or blank in " + envFile
                            + " (key is read from .env and injected via env, never argv)");
        }
        return key;
    }

    private String resolvePrismVersion(String prismBinary) {
        try {
            ProcessRunner.ProcRun run = processRunner.run(
                    java.util.List.of(prismBinary, "version"), null, java.util.Map.of(),
                    Duration.ofSeconds(10));
            if (run.ok()) {
                String v = run.stdout().trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
            // version probe failure is non-fatal; describe() falls back to "unknown".
        }
        return "unknown";
    }
}
