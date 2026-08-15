package gate.domain.config;

import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import java.nio.file.Path;
import java.util.List;

/**
 * Effective gate configuration (架构落地执行文档 §10.1).
 *
 * <p>Loaded from a single {@code gate.toml} carrying a {@code schema_version}; unknown keys refuse
 * startup, because fail-closed applies to configuration too.
 *
 * @param targetRefWhitelist the only refs the gate will ever authorise; also baked into the hook
 * @param engine             empty in P1. Engine-related preflight checks are skipped while absent
 *                           and become mandatory in P2 when prism is wired in.
 * @param web                Web operations console binding + auth (执行文档-后端-web §3, §8). Nullable
 *                           for the CLI/MCP paths that never start the HTTP server; only gate-web
 *                           requires it non-null.
 * @param session            Agent session orchestration defaults (执行文档-后端-web §5, §8). Nullable
 *                           on the CLI/MCP paths.
 * @param agent              Agent config defaults (执行文档-后端-web §8). Nullable on the CLI/MCP paths.
 */
public record GateConfig(
        int schemaVersion,
        String project,
        Path authRepo,
        Path clonesRoot,
        List<String> targetRefWhitelist,
        Path gateHome,
        Path approvalsDir,
        Path dbPath,
        Path blobRoot,
        Path auditPath,
        Path locksDir,
        Path indexDir,
        CommitIdentity gateIdentity,
        Policy policy,
        EngineConfig engine,
        WebConfig web,
        SessionConfig session,
        AgentConfigDefaults agent) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * Back-compatible 15-arg constructor for the existing CLI/MCP call sites (P1-P4) that carry no
     * web/session/agent configuration. Passes null for the three web-iteration blocks.
     */
    public GateConfig(int schemaVersion, String project, Path authRepo, Path clonesRoot,
                      List<String> targetRefWhitelist, Path gateHome, Path approvalsDir, Path dbPath,
                      Path blobRoot, Path auditPath, Path locksDir, Path indexDir,
                      CommitIdentity gateIdentity, Policy policy, EngineConfig engine) {
        this(schemaVersion, project, authRepo, clonesRoot, targetRefWhitelist, gateHome, approvalsDir,
                dbPath, blobRoot, auditPath, locksDir, indexDir, gateIdentity, policy, engine,
                null, null, null);
    }

    public GateConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported gate.toml schema_version=" + schemaVersion
                    + ", expected " + CURRENT_SCHEMA_VERSION);
        }
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must not be blank");
        }
        if (targetRefWhitelist == null || targetRefWhitelist.isEmpty()) {
            throw new IllegalArgumentException("target_ref whitelist must not be empty");
        }
        for (String ref : targetRefWhitelist) {
            if (!ref.startsWith("refs/heads/")) {
                throw new IllegalArgumentException("target ref must be a full refs/heads/... name: " + ref);
            }
        }
        targetRefWhitelist = List.copyOf(targetRefWhitelist);
        if (gateIdentity == null) {
            throw new IllegalArgumentException("gate_identity must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
    }

    public boolean webConfigured() {
        return web != null;
    }

    public String primaryTargetRef() {
        return targetRefWhitelist.get(0);
    }

    public boolean isWhitelisted(String ref) {
        return targetRefWhitelist.contains(ref);
    }

    public boolean engineConfigured() {
        return engine != null;
    }

    /** Present only once an external review engine is configured (P2). */
    public record EngineConfig(String cmd, List<String> args, long timeoutSeconds, String providerId, String model) {

        public EngineConfig {
            if (cmd == null || cmd.isBlank()) {
                throw new IllegalArgumentException("engine.cmd must not be blank");
            }
            args = List.copyOf(args);
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("engine.timeout must be positive");
            }
        }
    }

    /**
     * Web operations console binding + auth (执行文档-后端-web §3.3, §8).
     *
     * <p>{@code bind} MUST be a loopback address; {@code 0.0.0.0} is rejected at startup (fail-closed
     * exit 22) — the console is a local driver over the gate, never network-exposed. {@code
     * allowedOrigins} is the Host/Origin whitelist that blocks DNS-rebinding. {@code humanTokenFile}
     * is where the bootstrap HUMAN-domain token is written (relative to gate_home unless absolute).
     */
    public record WebConfig(String bind, int port, List<String> allowedOrigins, Path humanTokenFile) {

        public WebConfig {
            if (bind == null || bind.isBlank()) {
                throw new IllegalArgumentException("web.bind must not be blank");
            }
            if ("0.0.0.0".equals(bind.trim()) || "::".equals(bind.trim())) {
                throw new IllegalArgumentException(
                        "web.bind must be a loopback address (127.0.0.1 / localhost / ::1); "
                                + "binding to " + bind + " is refused (执行文档-后端-web §3.3)");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("web.port out of range: " + port);
            }
            allowedOrigins = List.copyOf(allowedOrigins);
            if (allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("web.allowed_origins must not be empty");
            }
            if (humanTokenFile == null) {
                throw new IllegalArgumentException("web.human_token_file must not be null");
            }
        }
    }

    /** Agent session orchestration defaults (执行文档-后端-web §5, §8). */
    public record SessionConfig(int portRangeMin, int portRangeMax, String defaultCli, String defaultAgentConfig) {

        public SessionConfig {
            if (portRangeMin <= 0 || portRangeMax > 65535 || portRangeMin > portRangeMax) {
                throw new IllegalArgumentException(
                        "session port range invalid: [" + portRangeMin + ", " + portRangeMax + "]");
            }
        }
    }

    /** Agent config defaults (执行文档-后端-web §8). */
    public record AgentConfigDefaults(String defaultModel, String defaultProvider, Path contextTemplate) {
    }
}
