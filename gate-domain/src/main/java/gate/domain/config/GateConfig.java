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
        EngineConfig engine) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

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
}
