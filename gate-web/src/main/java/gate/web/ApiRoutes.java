package gate.web;

import gate.web.controller.AppInfoController;
import gate.web.controller.AuthController;
import gate.web.controller.LlmController;
import gate.web.controller.MetricsController;
import gate.web.controller.OpenCodeProviderController;
import gate.web.controller.PluginController;
import gate.web.controller.PresubmitController;
import gate.web.controller.ProjectController;
import gate.web.controller.ProviderController;
import gate.web.controller.RepoViewController;
import gate.web.controller.SessionController;
import gate.web.controller.SettingsController;
import gate.web.controller.StatusController;
import gate.web.controller.StorageController;
import gate.web.controller.TerminalController;
import gate.web.controller.TaskController;
import gate.web.controller.TicketController;
import gate.web.controller.TicketRepoController;
import gate.web.controller.WebController;
import gate.web.plugin.PluginCatalog;
import gate.web.plugin.PluginDataStore;
import gate.web.controller.EvidenceController;
import gate.web.service.AuditReader;
import gate.web.service.ModelCatalogService;
import gate.web.service.RepoViewReader;
import gate.web.service.SessionModelCatalog;
import gate.web.service.OpenCodeConfigService;
import gate.web.service.OpenCodeModelsApi;
import io.javalin.Javalin;
import java.util.List;

/**
 * Root API Route Aggregator (MVC facade).
 * Wires all modular web controllers into the Javalin application.
 */
public final class ApiRoutes implements WebController {

    private final List<WebController> controllers;

    public ApiRoutes(WebComponents c) {
        // 仓库视图（分支图/提交历史/提交详情）的只读 git 读取：项目工作区与工单克隆共用一份实现
        RepoViewReader repoViewReader = new RepoViewReader(c.git());
        this.controllers = List.of(
                new AuthController(c.credentials()),
                new StatusController(c.gateService(), c.config(), c.runtimeInfo()),
                new ProjectController(c.projectRepository(), c.ticketRepository(), c.topologyInitializer(),
                        c.config(), c.workspaceSyncer(), c.cloneBaseSyncer(), c.git(), c.clock()),
                // 仓库视图（分支图/提交历史/提交详情）只读 git 读取：项目工作区与工单克隆共用一份实现
                new RepoViewController(c.projectRepository(), repoViewReader),
                new TicketRepoController(c.ticketRepository(), repoViewReader),
                new TerminalController(c.projectRepository(), c.ticketRepository(), c.credentials()),
                new TicketController(c.ticketRepository(), c.projectRepository(), c.agentConfigRepository(),
                        c.topologyInitializer(), c.config(), c.clock(), c.presubmitRepository(),
                        c.ticketStageChangeRepository(), c.auditLog(), c.gateService(), c.ticketLockManager()),
                new PresubmitController(c.gateService(), c.ticketRepository(), c.presubmitRepository(),
                        c.reviewResultRepository(), c.blobStore(), c.ticketLockManager(), c.git(), c.config(),
                        c.auditLog()),
                new EvidenceController(c.ticketRepository(), c.presubmitRepository(),
                        c.reviewResultRepository(), c.publishIntentRepository(),
                        c.ticketStageChangeRepository(), c.approvalStore(), c.blobStore(),
                        (gate.adapters.audit.HashChainAuditLog) c.auditLog()),
                new TaskController(c.taskRegistry(), c.taskRunner()),
                new ProviderController(c.providerRepository(), c.modelFetcher(), c.kmsService(), c.clock()),
                new LlmController(c.providerRepository(), c.kmsService()),
                new OpenCodeProviderController(new OpenCodeConfigService(), new OpenCodeModelsApi(),
                        new ModelCatalogService()),
                new MetricsController(c.metricsService(), c.gateService()),
                new SessionController(c.agentConfigRepository(), c.sessionRepository(), c.agentSessionPort(),
                        c.ticketRepository(), c.clock(), new SessionModelCatalog(), c.credentials(),
                        c.providerRepository()),
                new SettingsController(c.gateToml()),
                // 存储设置（T-116）：数据目录/占用/缓存清理/工作区存储管理/在系统中打开
                // （工作区清理带会话运行边界：有会话在跑就拒绝清理可再生文件）
                new StorageController(
                        new gate.web.service.StorageInfoService(c.config(), c.gateToml(),
                                c.ticketRepository(), c.agentSessionPort(), c.sessionRepository())),
                // 插件系统：manifest 扫描/启停/资产下发/KV 数据（目录都在 gateHome 下）
                new PluginController(
                        new PluginCatalog(c.config().gateHome().resolve("plugins")),
                        new PluginDataStore(c.config().gateHome().resolve("plugins-data"))),
                new AppInfoController()
        );
    }

    @Override
    public void register(Javalin app) {
        for (WebController controller : controllers) {
            controller.register(app);
        }
    }
}
