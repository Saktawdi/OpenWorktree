package gate.ports.store;

import java.time.Instant;

/**
 * Two-domain credential store for the MCP stdio server (架构落地执行文档 §5.4, §11.3).
 *
 * <p>The gate exposes its use cases as MCP tools in two permission domains with independent
 * credentials:
 * <ul>
 *   <li><b>agent domain</b> (low privilege, bound to one ticket): {@code presubmit_create},
 *       {@code presubmit_get_diff}, {@code review_result_get}, {@code sync_base},
 *       {@code session_read}.</li>
 *   <li><b>human/orchestrator domain</b> (high privilege): {@code review_run},
 *       {@code commit_and_publish}, {@code config_*}, {@code provider_*}.</li>
 * </ul>
 *
 * <p>{@code review_run} and {@code commit_and_publish} are <b>never</b> exposed to the agent domain —
 * otherwise an agent could repeatedly run reviews until it lucks into a pass (执行文档 §4 P3, §5.4).
 *
 * <p>The domain check is authoritative on the server side (§1.3: CLI flags like
 * {@code --allowedTools} are agent-controllable config and cannot be the sole defence). Tokens are
 * injected via environment variables, never argv (§6.1 — {@code /proc/<pid>/cmdline} is
 * world-readable). Only the SHA-256 hash of a token is stored; the plaintext is returned once at
 * issuance and never persisted, mirroring the api_key handling (ADR-9).
 */
public interface CredentialRepository {

    /** A validated domain: AGENT (bound to a ticket), HUMAN, or INVALID (no match / revoked). */
    record Domain(String name, String ticketNo) {
        public static final String AGENT = "AGENT";
        public static final String HUMAN = "HUMAN";
        public static final String INVALID = "INVALID";

        public boolean isValid() {
            return !INVALID.equals(name);
        }

        public boolean isAgent() {
            return AGENT.equals(name);
        }

        public boolean isHuman() {
            return HUMAN.equals(name);
        }

        public static Domain agent(String ticketNo) {
            return new Domain(AGENT, ticketNo);
        }

        public static Domain human() {
            return new Domain(HUMAN, null);
        }

        public static Domain invalid() {
            return new Domain(INVALID, null);
        }
    }

    /**
     * Validates a plaintext token against the stored hashes.
     *
     * @return the domain the token grants; {@link Domain#invalid()} if the token is unknown or revoked.
     */
    Domain validate(String token);

    /**
     * Issues an agent-domain token bound to a ticket. The plaintext token is returned once; only its
     * hash is persisted. An agent token authorises only operations on the bound ticket.
     */
    String issueAgentToken(String ticketNo, Instant now);

    /**
     * Issues a human-domain token. The plaintext token is returned once; only its hash is persisted.
     */
    String issueHumanToken(Instant now);

    /** Revokes a token (by plaintext — the hash is computed internally). */
    void revoke(String token);
}
