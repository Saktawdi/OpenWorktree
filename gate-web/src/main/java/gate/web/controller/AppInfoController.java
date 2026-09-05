package gate.web.controller;

import gate.web.service.AppInfoService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * 应用设置 Controller（设置中心「应用设置」页）。
 * Owns /api/app/info and /api/app/update-check routes.
 */
public final class AppInfoController implements WebController {

    private final AppInfoService appInfo;

    public AppInfoController() {
        this(new AppInfoService());
    }

    /** 测试缝：注入版本号与假 GitHub 传输，不真的出网。 */
    AppInfoController(AppInfoService appInfo) {
        this.appInfo = appInfo;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/app/info", this::info);
        app.get("/api/app/update-check", this::updateCheck);
        app.get("/api/app/changelog", this::changelog);
    }

    public void info(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(appInfo.infoJson());
    }

    public void updateCheck(Context ctx) {
        boolean force = "1".equals(ctx.queryParam("force"));
        ctx.status(HttpStatus.OK);
        ctx.json(appInfo.checkUpdate(force));
    }

    /** 发现新版本时应用内展示的更新日志（version 取自 update-check 的 latest_version）。 */
    public void changelog(Context ctx) {
        String version = ctx.queryParam("version");
        boolean force = "1".equals(ctx.queryParam("force"));
        ctx.status(HttpStatus.OK);
        ctx.json(appInfo.fetchChangelog(version, force));
    }
}
