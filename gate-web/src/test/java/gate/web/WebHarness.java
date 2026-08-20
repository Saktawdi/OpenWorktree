package gate.web;

import gate.domain.config.GateConfig;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a live gate-web over a temporary directory, wired with the real adapters and the real git
 * binary (mirrors {@code gate.testkit.GateHarness} for the web module).
 *
 * <p>The topology (bare auth repo + seeded base + installed hook) is created so the console has a
 * real gate to drive. A HUMAN token is minted up front and exposed for the tests.
 */
final class WebHarness implements AutoCloseable {

    private final Path root;
    private final WebComponents components;
    private final String humanToken;

    WebHarness() {
        this("git", "127.0.0.1", null);
    }

    WebHarness(String gitExecutable, String bind) {
        this(gitExecutable, bind, null);
    }

    WebHarness(String gitExecutable, String bind, gate.ports.AgentSessionPort sessionPortOverride) {
        try {
            this.root = Files.createTempDirectory("gate-web-test-");
            Path gateHome = root.resolve("gate-home");
            Files.createDirectories(gateHome.resolve("approvals").resolve("consumed"));

            GateConfig config = new GateConfig(
                    2, "web-test",
                    root.resolve("auth.git"),
                    root.resolve("clones"),
                    List.of("refs/heads/main"),
                    gateHome,
                    gateHome.resolve("approvals"),
                    gateHome.resolve("gate.db"),
                    gateHome.resolve("blobs"),
                    gateHome.resolve("audit.jsonl"),
                    gateHome.resolve("locks"),
                    gateHome.resolve("idx"),
                    new CommitIdentity("gate", "gate@localhost", "1700000000 +0000"),
                    Policy.defaults(),
                    null,
                    new GateConfig.WebConfig(bind, 0, List.of("127.0.0.1", "localhost"),
                            gateHome.resolve("web-token")),
                    new GateConfig.SessionConfig(49152, 65535, "claude", null, 60),
                    new GateConfig.AgentConfigDefaults(null, null, null));

            this.components = new WebComponents(config, gitExecutable, root.resolve(".env"), sessionPortOverride);
            // Seed manual provider (same bootstrap as fromConfig).
            if (components.providerRepository().find("manual").isEmpty()) {
                components.providerRepository().upsert(new gate.ports.ProviderRepository.ProviderRow(
                        "manual", "manual", "local://manual", "none", "manual",
                        components.clock().now()), components.clock().now());
            }
            components.topologyInitializer().initAuthRepo(
                    gate.domain.git.RepoRef.of(config.authRepo()),
                    config.primaryTargetRef(), config.approvalsDir());

            this.humanToken = components.credentials().issueHumanToken(components.clock().now());
            // Seed minimal RBAC for happy-path web tests (Phase4) – not all roles, to keep deny-path testable
            try {
                String userId = "human:" + humanToken.substring(0, Math.min(8, humanToken.length()));
                // For equivalence happy path we need a single token that can do ticket+review+publish.
                // To avoid SoD block, we will NOT seed both REVIEWER and PUBLISHER on same user for that test;
                // instead we seed REVIEWER+PUBLISHER but also pre-seed sod_exception via DB for EQ tickets (see below).
                // For general harness, seed DEVELOPER/PROJECT_ADMIN only; review/publish will be done via separate tokens in tests that need SoD
                for (gate.domain.security.RbacRole role : List.of(
                        gate.domain.security.RbacRole.DEVELOPER,
                        gate.domain.security.RbacRole.PROJECT_ADMIN)) {
                    try { components.rbacPort().assignRole(userId, "default", null, role, "test-seed"); } catch (Exception ignored) {}
                }
                // Also create a dedicated publisher token for SoD dual-token tests
                String publisherToken = components.credentials().issueHumanToken(components.clock().now());
                String publisherUser = "human:" + publisherToken.substring(0, Math.min(8, publisherToken.length()));
                for (gate.domain.security.RbacRole role : List.of(
                        gate.domain.security.RbacRole.DEVELOPER,
                        gate.domain.security.RbacRole.PUBLISHER,
                        gate.domain.security.RbacRole.PROJECT_ADMIN)) {
                    try { components.rbacPort().assignRole(publisherUser, "default", null, role, "test-seed"); } catch (Exception ignored) {}
                }
                // Store publisher token for tests that need dual-token (via TestHelper)
                try { Files.writeString(gateHome.resolve("publisher-token"), publisherToken); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    WebComponents components() {
        return components;
    }

    String humanToken() {
        return humanToken;
    }

    Path root() {
        return root;
    }

    @Override
    public void close() {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }
}
