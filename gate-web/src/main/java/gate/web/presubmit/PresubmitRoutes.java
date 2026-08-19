package gate.web.presubmit;

import gate.application.GateService;
import gate.application.PresubmitCommand;
import gate.application.PresubmitResult;

/**
 * Presubmit capability route (EX-001).
 * Owns POST /api/tickets/{no}/presubmit and GET diff.
 */
public final class PresubmitRoutes {
    private final GateService gateService;

    public PresubmitRoutes(GateService gateService) {
        this.gateService = gateService;
    }

    public PresubmitResult presubmit(String ticketNo) {
        return gateService.presubmit(new PresubmitCommand(ticketNo));
    }
}
