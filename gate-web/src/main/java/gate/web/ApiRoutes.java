package gate.web;

import gate.web.controller.AuthController;
import gate.web.controller.MetricsController;
import gate.web.controller.PresubmitController;
import gate.web.controller.ProjectController;
import gate.web.controller.ProviderController;
import gate.web.controller.RepoViewController;
import gate.web.controller.SessionController;
import gate.web.controller.SettingsController;
import gate.web.controller.StatusController;
import gate.web.controller.TaskController;
import gate.web.controller.TicketController;
import gate.web.controller.WebController;
import gate.web.service.SessionModelCatalog;
import io.javalin.Javalin;
import java.util.List;

/**
 * Root API Route Aggregator (MVC facade).
 * Wires all modular web controllers into the Javalin application.
 */
public final class ApiRoutes implements WebController {

    private final List<WebController> controllers;

    public ApiRoutes(WebComponents c) {
        this.controllers = List.of(
                new AuthController(c.credentials()),
                new StatusController(c.gateService(), c.config(), c.runtimeInfo()),
                new ProjectController(c.projectRepository(), c.ticketRepository(), c.topologyInitializer(),
                        c.config(), c.workspaceSyncer(), c.git(), c.clock()),
                new RepoViewController(c.projectRepository(), c.git()),
                new TicketController(c.ticketRepository(), c.projectRepository(), c.agentConfigRepository(),
                        c.topologyInitializer(), c.config(), c.clock()),
                new PresubmitController(c.gateService(), c.ticketRepository(), c.presubmitRepository(),
                        c.reviewResultRepository(), c.blobStore(), c.ticketLockManager(), c.git(), c.config()),
                new TaskController(c.taskRegistry(), c.taskRunner()),
                new ProviderController(c.providerRepository(), c.modelFetcher(), c.kmsService(), c.clock()),
                new MetricsController(c.metricsService(), c.gateService()),
                new SessionController(c.agentConfigRepository(), c.sessionRepository(), c.agentSessionPort(),
                        c.ticketRepository(), c.clock(), new SessionModelCatalog(), c.credentials()),
                new SettingsController(c.gateToml())
        );
    }

    @Override
    public void register(Javalin app) {
        for (WebController controller : controllers) {
            controller.register(app);
        }
    }
}
