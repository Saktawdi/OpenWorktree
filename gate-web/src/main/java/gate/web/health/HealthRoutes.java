package gate.web.health;

import gate.adapters.health.HealthService;
import gate.web.ApiRoutes;
import java.util.Map;

/**
 * Health capability (Phase4, production-architecture §13.3).
 * Owns /livez, /readyz, /status/dependencies.
 * L1 implementation: no business logic, just probes.
 */
public final class HealthRoutes {

    private final HealthService health;

    public HealthRoutes(HealthService health) { this.health = health; }

    public ApiRoutes.Response livez() {
        return new ApiRoutes.Response(200, health.livez());
    }

    public ApiRoutes.Response readyz() {
        Map<String,Object> m = health.readyz();
        boolean ok = "ok".equals(m.get("status"));
        return new ApiRoutes.Response(ok ? 200 : 503, m);
    }

    public ApiRoutes.Response dependencies() {
        return new ApiRoutes.Response(200, health.dependencies());
    }
}
