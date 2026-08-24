package gate.web.controller;

import gate.application.GateService;
import gate.application.StatusQuery;
import gate.application.StatusResult;
import gate.domain.config.GateConfig;
import gate.web.service.RuntimeInfoService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status and Health Controller.
 * Owns /api/health, /api/status, /api/runtime, /api/agent-runtimes, /api/config routes.
 */
public final class StatusController implements WebController {

    private final GateService gateService;
    private final GateConfig config;
    private final RuntimeInfoService runtimeInfo;

    public StatusController(GateService gateService, GateConfig config, RuntimeInfoService runtimeInfo) {
        this.gateService = gateService;
        this.config = config;
        this.runtimeInfo = runtimeInfo;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/health", this::health);
        app.get("/api/status", this::status);
        app.get("/api/runtime", this::runtime);
        app.get("/api/agent-runtimes", this::agentRuntimes);
        app.get("/api/config", this::config);
    }

    public void health(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "gate-web");
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void status(Context ctx) {
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
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void runtime(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(runtimeInfo.snapshot());
    }

    public void agentRuntimes(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(runtimeInfo.agentRuntimes());
    }

    public void config(Context ctx) {
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
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }
}
