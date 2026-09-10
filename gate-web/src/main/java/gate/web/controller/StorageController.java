package gate.web.controller;

import gate.web.service.StorageInfoService;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.Map;

/**
 * 存储设置 Controller（设置中心「存储设置」页，T-116）。
 * Owns /api/storage/overview, /api/storage/caches, /api/storage/caches/{id}/clean,
 * /api/storage/workspaces, /api/storage/workspaces/{id}/prune and /api/storage/open routes.
 */
public final class StorageController implements WebController {

    private final StorageInfoService storage;

    public StorageController(StorageInfoService storage) {
        this.storage = storage;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/storage/overview", this::overview);
        app.get("/api/storage/caches", this::caches);
        app.post("/api/storage/caches/{id}/clean", this::clean);
        app.get("/api/storage/workspaces", this::workspaces);
        app.post("/api/storage/workspaces/{id}/prune", this::prune);
        app.post("/api/storage/open", this::open);
    }

    public void overview(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(storage.overview());
    }

    public void caches(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(storage.caches());
    }

    public void clean(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(storage.clean(ctx.pathParam("id")));
    }

    public void workspaces(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(storage.workspaces());
    }

    public void prune(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(storage.pruneWorkspace(ctx.pathParam("id")));
    }

    public void open(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String target = req.get("target") == null ? "" : req.get("target").toString();
        storage.open(target);
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true, "target", target));
    }
}
