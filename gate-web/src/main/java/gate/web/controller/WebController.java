package gate.web.controller;

import io.javalin.Javalin;

/**
 * Common contract for modular web controllers.
 * Each controller is responsible for declaring and registering its own REST endpoints.
 */
public interface WebController {

    /**
     * Registers routes handled by this controller onto the Javalin application.
     */
    void register(Javalin app);
}
