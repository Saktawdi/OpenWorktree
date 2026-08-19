package gate.web.status;

import gate.application.GateService;
import gate.application.StatusQuery;

/**
 * Status capability handler (EX-001, capability-registry: status).
 * Owns /api/status, /api/runtime, /api/agent-runtimes, /api/config, /api/health.
 * Read-only projection; must not own business facts (ownership-catalog.md).
 */
public final class StatusRoutes {
    private final GateService gateService;

    public StatusRoutes(GateService gateService) {
        this.gateService = gateService;
    }

    public Object status() {
        return gateService.status(new StatusQuery(null));
    }
}
