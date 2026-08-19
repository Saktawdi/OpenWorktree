package gate.web;

import gate.application.GateService;
import gate.application.StatusQuery;
import gate.application.StatusResult;
import gate.domain.config.GateConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status capability handler (EX-001, capability-registry: status).
 * Owns /api/status, /api/runtime, /api/agent-runtimes, /api/config, /api/health.
 * Read-only projection; must not own business facts (ownership-catalog.md).
 * L2 behavior-preserving extraction from ApiRoutes (1538 lines) — facade delegates here.
 */
public final class StatusRoutes {
    private final GateService gateService;
    private final GateConfig config;
    private final RuntimeInfoService runtimeInfo;

    public StatusRoutes(GateService gateService, GateConfig config, RuntimeInfoService runtimeInfo) {
        this.gateService = gateService;
        this.config = config;
        this.runtimeInfo = runtimeInfo;
    }

    public ApiRoutes.Response status() {
        StatusResult r = gateService.status(new StatusQuery(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target_ref", r.targetRef());
        body.put("auth_tip", r.authTip());
        body.put("auth_commit_count", r.authCommitCount());
        List<Map<String, Object>> ts = new ArrayList<>();
        for (StatusResult.TicketStatus t : r.tickets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket_no", t.ticketNo());
            m.put("stage", t.stage());
            m.put("latest_round", t.latestRound());
            m.put("latest_tree_hash", t.latestTreeHash());
            m.put("latest_intent_status", t.latestIntentStatus());
            m.put("latest_commit_sha", t.latestCommitSha());
            m.put("published_in_auth", t.publishedInAuth());
            ts.add(m);
        }
        body.put("tickets", ts);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response runtime() {
        return new ApiRoutes.Response(200, runtimeInfo.snapshot());
    }

    public ApiRoutes.Response agentRuntimes() {
        return new ApiRoutes.Response(200, runtimeInfo.agentRuntimes());
    }

    public ApiRoutes.Response configView() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", config.project());
        body.put("auth_repo", config.authRepo().toString());
        body.put("clones_root", config.clonesRoot().toString());
        body.put("target_ref_whitelist", config.targetRefWhitelist());
        body.put("gate_home", config.gateHome().toString());
        body.put("engine_configured", config.engineConfigured());
        if (config.webConfigured()) {
            Map<String, Object> web = new LinkedHashMap<>();
            web.put("bind", config.web().bind());
            web.put("port", config.web().port());
            web.put("allowed_origins", config.web().allowedOrigins());
            body.put("web", web);
        }
        return new ApiRoutes.Response(200, body);
    }
}
