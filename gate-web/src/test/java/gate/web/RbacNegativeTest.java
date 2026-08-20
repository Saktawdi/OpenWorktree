package gate.web;

import gate.domain.security.RbacRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase4 P2 A: RBAC deny-path must be covered in web contract tests.
 * A token with only DEVELOPER must be denied for PUBLISH_RUN (403).
 */
class RbacNegativeTest {

    private WebHarness harness;
    private Path gateHome;

    @BeforeEach
    void setUp() throws Exception {
        harness = new WebHarness();
        gateHome = harness.root().resolve("gate-home");
        // Create a limited token: only DEVELOPER in default tenant
        String limitedToken = harness.components().credentials().issueHumanToken(harness.components().clock().now());
        String limitedUser = "human:" + limitedToken.substring(0, Math.min(8, limitedToken.length()));
        // Ensure user has only DEVELOPER, not PUBLISHER
        // First clear any existing roles for this user (in case of re-run)
        var rbac = harness.components().rbacPort();
        // Assign only DEVELOPER
        rbac.assignRole(limitedUser, "default", null, RbacRole.DEVELOPER, "test");
        // Ensure no PUBLISHER
        try { rbac.revokeRole(limitedUser, "default", null, RbacRole.PUBLISHER, "test"); } catch (Exception ignored) {}
        // Store token for test
        this.limitedToken = limitedToken;
        this.limitedUser = limitedUser;
    }

    private String limitedToken;
    private String limitedUser;

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void developer_cannot_publish_403() throws Exception {
        var limitedCtx = new gate.domain.security.SecurityContext(limitedUser, "default", null, java.util.Set.of(RbacRole.DEVELOPER), limitedToken.substring(0,16));
        gate.ports.security.SecurityContextHolder.set(limitedCtx);
        try {
            assertThrows(gate.domain.error.GateException.class, () -> {
                new gate.application.security.RbacService(harness.components().rbacPort()).require(limitedCtx, gate.domain.security.Permission.PUBLISH_RUN, "default", null);
            });
            // Also test via ApiRoutes: publish should be denied
            var api = new ApiRoutes(harness.components());
            assertThrows(gate.domain.error.GateException.class, () -> api.route("POST", "/api/tickets/T-UNKNOWN/publish", "{}"));
        } finally {
            gate.ports.security.SecurityContextHolder.clear();
        }
    }

    @Test
    void cross_tenant_denied() {
        var ctx = new gate.domain.security.SecurityContext("alice", "tenant-a", null, java.util.Set.of(RbacRole.DEVELOPER), "hash");
        gate.ports.security.SecurityContextHolder.set(ctx);
        try {
            assertThrows(gate.domain.error.GateException.class, () -> {
                new gate.application.security.RbacService(harness.components().rbacPort()).require(ctx, gate.domain.security.Permission.TICKET_CREATE, "tenant-b", null);
            });
            // Also test TenantPort
            assertThrows(gate.domain.error.GateException.class, () -> harness.components().tenantPort().requireTenantAccess(ctx, "tenant-b"));
        } finally {
            gate.ports.security.SecurityContextHolder.clear();
        }
    }
}
