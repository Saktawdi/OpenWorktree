package gate.adapters.engine;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;
import gate.ports.store.BlobStore;
import gate.ports.store.ProviderRepository;
import gate.ports.engine.ReviewEngine;
import gate.ports.engine.ReviewEngineFactory;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the built-in {@link ReviewEngine} ({@code engine.kind="gate-engine"} — gate 内建引擎，
 * 进程内直连 OpenAI 兼容 /chat/completions，无外部二进制)。prism 双轨已按执行文档 v2 移除，
 * 这是唯一知道如何构建引擎适配器的落点。装配时解析三样东西：
 * <ol>
 *   <li><b>base_url</b> — 从 {@code engine.provider_id} 指向的 {@code provider} 表行；</li>
 *   <li><b>api key</b> — 同一行 {@code api_key_ref}（设置中心 LLM 凭据，{@code kms:} 密文），
 *       只进 Authorization 头，绝不进 argv / DB 明文 / 日志；</li>
 *   <li><b>超时窗口</b> — 总墙钟 {@code timeout_seconds} 与空闲失效窗口
 *       {@code idle_timeout_seconds}（引擎看门狗强制生效）。</li>
 * </ol>
 *
 * <p>人工判定适配器仍可用于人工驱动的轮次（A6 走 GatePolicy blocker 分支的方式）。
 */
public final class GateReviewEngineFactory implements ReviewEngineFactory {

    private static final Logger LOG = LoggerFactory.getLogger(GateReviewEngineFactory.class);

    private final BlobStore blobStore;
    private final GateConfig config;
    private final ProviderRepository providers;
    private final KmsService kms;

    public GateReviewEngineFactory(BlobStore blobStore, GateConfig config,
                                   ProviderRepository providers, KmsService kms) {
        this.blobStore = blobStore;
        this.config = config;
        this.providers = providers;
        this.kms = kms;
        // 单引擎化后 cmd/args 仅是遗留兼容字段——出现即提示运维从配置中清理。
        if (config.engineConfigured() && config.engine().cmd() != null && !config.engine().cmd().isBlank()) {
            LOG.warn("gate.toml 的 engine.cmd/engine.args 已废弃（prism 已移除），配置值被忽略: {}",
                    config.engine().cmd());
        }
    }

    @Override
    public ReviewEngine forManualVerdict(Boolean pass, String note) {
        return new ManualReviewEngine(blobStore, pass, note);
    }

    @Override
    public ReviewEngine builtin() {
        if (!config.engineConfigured()) {
            return null;
        }
        GateConfig.EngineConfig engine = config.engine();
        if (!BuiltinReviewEngine.KIND.equals(engine.kind())) {
            // 配置层已拒绝未知 kind；这里是组装侧的兜底白名单（fail-closed）。
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "unsupported engine.kind \"" + engine.kind() + "\"（唯一合法值 gate-engine）");
        }
        ProviderRepository.ProviderRow provider = providers.find(engine.providerId())
                .orElseThrow(() -> new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "engine.provider_id=" + engine.providerId()
                                + " has no matching provider row (在设置中心新建该 Provider)"));
        String apiKey = resolveApiKey(provider);

        return new BuiltinReviewEngine(
                blobStore,
                Duration.ofSeconds(engine.timeoutSeconds()),
                Duration.ofSeconds(engine.idleTimeoutSeconds()),
                engine.providerId(), engine.model(),
                provider.baseUrl(), apiKey, engine.maxTokens(),
                engine.filterEnabled(), engine.rounds(), engine.concurrency(), engine.fileTokenGate());
    }

    /**
     * 凭据来自设置中心管理的 provider 行（{@code kms:} 密文）。注入只走 Authorization 头。
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
}
