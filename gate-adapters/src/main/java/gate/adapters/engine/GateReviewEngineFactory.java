package gate.adapters.engine;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;
import gate.ports.store.BlobStore;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProviderRepository;
import gate.ports.engine.ReviewEngine;
import gate.ports.engine.ReviewEngineFactory;
import java.time.Duration;

/**
 * Assembles the right {@link ReviewEngine} for a review round (架构落地执行文档 §5.3, §10.1.1).
 *
 * <p>This is the single place that knows how to build the prism adapter. It resolves, at assembly
 * time, the three things prism needs at runtime:
 * <ol>
 *   <li><b>base_url</b> — from the {@code provider} table row referenced by
 *       {@link GateConfig.EngineConfig#providerId()};</li>
 *   <li><b>api key</b> — from the same provider row's {@code api_key_ref} (设置中心 LLM 凭据，
 *       {@code kms:} 密文)，injected via environment — never argv;</li>
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
    private final KmsService kms;

    public GateReviewEngineFactory(BlobStore blobStore, GateConfig config, ProcessRunner processRunner,
                                   ProviderRepository providers, KmsService kms) {
        this.blobStore = blobStore;
        this.manual = new ManualReviewEngineFactory(blobStore);
        this.config = config;
        this.processRunner = processRunner;
        this.providers = providers;
        this.kms = kms;
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
                                + " has no matching provider row (在设置中心新建该 Provider)"));
        String apiKey = resolveApiKey(provider);
        String version = resolvePrismVersion(engine.cmd());

        return new PrismReviewEngine(
                processRunner, blobStore,
                engine.cmd(), Duration.ofSeconds(engine.timeoutSeconds()),
                engine.providerId(), engine.model(),
                provider.baseUrl(), apiKey, version,
                config.policy().engineAcceptDegraded());
    }

    /**
     * The key comes from the provider row managed by the settings center (设置中心 → LLM Providers)：
     * {@code kms:} ciphertext written by the console. The key is injected into prism's environment
     * and never reaches argv, the DB in plaintext, or a log.
     */
    private String resolveApiKey(ProviderRepository.ProviderRow provider) {
        String ref = provider.apiKeyRef();
        String key = ApiKeyResolver.resolve(ref, kms);
        if (key == null && ApiKeyResolver.isLegacyPlaintext(ref) && kms != null) {
            // 一次性自愈：KMS 凭据流之前直接存进 api_key_ref 的裸明文 key → 现场加密迁移为
            // kms: 密文并照常使用（无需等重启迁移，也绝不把明文带进错误信息）。
            key = ref.trim();
            providers.upsert(new ProviderRepository.ProviderRow(provider.id(), provider.name(),
                    provider.baseUrl(), "kms:" + kms.encrypt(key), provider.type(), provider.createdAt()),
                    java.time.Instant.now());
        }
        if (key == null || key.isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "provider \"" + provider.id() + "\" has no usable API credential"
                            + " (credential=" + ApiKeyResolver.describe(ref) + ")"
                            + " — 在 设置中心 → LLM Providers 填入 API Key，或修正 engine.provider_id");
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
