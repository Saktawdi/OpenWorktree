package gate.web.controller;

import gate.application.GateService;
import gate.application.metrics.MetricsService;
import gate.application.status.ReconcileCommand;
import gate.application.status.ReconcileResult;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics & Reconciliation Controller.
 * Owns /api/metrics, /api/metrics/h1, /api/reconcile routes.
 */
public final class MetricsController implements WebController {

    private final MetricsService metricsService;
    private final GateService gateService;

    public MetricsController(MetricsService metricsService, GateService gateService) {
        this.metricsService = metricsService;
        this.gateService = gateService;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/metrics", this::metrics);
        app.get("/api/metrics/h1", this::metricsH1);
        app.post("/api/reconcile", this::reconcile);
    }

    public void metrics(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricsService.MetricRecord r : metricsService.export()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket_no", r.ticketNo());
            m.put("review_round", r.reviewRound());
            m.put("verdict", r.verdict());
            m.put("diff_bytes", r.diffBytes());
            m.put("diff_lines", r.diffLines());
            m.put("prompt_tokens", r.promptTokens());
            m.put("completion_tokens", r.completionTokens());
            m.put("total_tokens", r.totalTokens());
            m.put("token_source", r.tokenSource());
            m.put("review_wall_ms", r.reviewWallMs());
            m.put("llm_wall_ms", r.llmWallMs());
            m.put("exec_token_total", r.execTokenTotal());
            m.put("exec_token_source", r.execTokenSource());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void metricsH1(Context ctx) {
        var v = metricsService.verdict();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("classification", v.classification());
        body.put("cost_ratio_median", Double.isNaN(v.costRatioMedian()) ? null : v.costRatioMedian());
        body.put("first_pass_rate", Double.isNaN(v.firstPassRate()) ? null : v.firstPassRate());
        body.put("sample_count", v.sampleCount());
        body.put("metric_basis", v.metricBasis());
        body.put("degradation_note", v.degradationNote());
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void reconcile(Context ctx) {
        String ticketNo = null;
        String requestBody = ctx.body();
        if (requestBody != null && !requestBody.isBlank()) {
            Map<String, Object> req = Json.parseObject(requestBody);
            ticketNo = req.get("ticket_no") == null ? null : req.get("ticket_no").toString();
        }
        ReconcileResult r = gateService.reconcile(new ReconcileCommand(ticketNo));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (ReconcileResult.IntentOutcome o : r.outcomes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent_id", o.intentId());
            m.put("ticket_no", o.ticketNo());
            m.put("review_round", o.reviewRound());
            m.put("tree_hash", o.treeHash());
            m.put("commit_sha", o.commitSha());
            m.put("from", o.from());
            m.put("to", o.to());
            m.put("reason", o.reason());
            outcomes.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcomes", outcomes);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }
}