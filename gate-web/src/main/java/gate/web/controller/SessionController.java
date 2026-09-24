package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.PermissionRequest;
import gate.domain.session.Session;
import gate.domain.ticket.Ticket;
import gate.ports.store.AgentConfigRepository;
import gate.ports.session.AgentSessionPort;
import gate.ports.infra.Clock;
import gate.ports.store.CredentialRepository;
import gate.ports.store.ProviderRepository;
import gate.ports.store.SessionRepository;
import gate.ports.store.TicketRepository;
import gate.web.security.AuthFilter;
import gate.web.service.SessionModelCatalog;
import gate.web.sse.SessionSseHandler;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agent Session & Configuration Controller.
 * Owns /api/agent-configs/*, /api/sessions/*, /api/tickets/{no}/sessions, and /api/agents/busy routes.
 */
public final class SessionController implements WebController {

    private static final int MAX_ATTACHMENTS = 10;
    private static final java.util.Set<String> IMAGE_MIMES =
            java.util.Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    /**
     * 会话图片缩略图的克隆工作区落点（Ticket.clonePath 下）：发送时把缩略图写到
     * .gate/chat-images/，用户消息文本追加 [图片引用 #n] 引用行——不落库、不改
     * schema，历史重载经 messageJson 解析引用行还原缩略图；.gate/ 追加进克隆本地
     * 的 .git/info/exclude（不动工作区文件，不污染 git status / 变更对比）。
     */
    private static final String CHAT_IMAGE_DIR = ".gate/chat-images";
    /**
     * 会话粘贴/拖入的非图片文件落点（Ticket.clonePath 下）：浏览器读不到被复制文件
     * 的真实路径，前端把文件内容 base64 传到 /chat-files 端点落盘，把返回的克隆内
     * 相对路径插进消息正文——会话 cwd 即克隆根，Agent 直接可读。与 chat-images
     * 共用 .git/info/exclude 的 .gate/ 收敛（不污染 git status / 变更对比）。
     */
    private static final String CHAT_FILE_DIR = ".gate/chat-files";
    /** 粘贴文件大小上限（落盘字节）：har/日志类文件可到几十 MB，超出直接拒绝。 */
    private static final long MAX_CHAT_FILE_BYTES = 50L * 1024 * 1024;
    private static final Map<String, String> CHAT_IMAGE_EXT_BY_MIME = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");
    private static final Map<String, String> CHAT_IMAGE_MIME_BY_EXT = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");
    /**
     * 推理强度档位（claude 会话目录随每个模型下发）：claude --effort 接受
     */
    private static final List<String> CLAUDE_EFFORT_VARIANTS =
            List.of("low", "medium", "high", "max", "xhigh");

    private final AgentConfigRepository agentConfigs;
    private final ProviderRepository providers;
    private final SessionRepository sessionRepository;
    private final AgentSessionPort agentSessionPort;
    private final TicketRepository tickets;
    private final Clock clock;
    private final SessionModelCatalog modelCatalog;
    private final CredentialRepository credentials;
    private final SessionSseHandler sessionSseHandler;

    public SessionController(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                             AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock,
                             SessionModelCatalog modelCatalog, CredentialRepository credentials,
                             ProviderRepository providers) {
        this.agentConfigs = agentConfigs;
        this.sessionRepository = sessionRepository;
        this.agentSessionPort = agentSessionPort;
        this.tickets = tickets;
        this.clock = clock;
        this.modelCatalog = modelCatalog;
        this.credentials = credentials;
        this.providers = providers;
        this.sessionSseHandler = new SessionSseHandler(agentSessionPort, sessionRepository);
    }

    @Override
    public void register(Javalin app) {
        // Agent Configs
        app.get("/api/agent-configs", this::listAgentConfigs);
        app.post("/api/agent-configs", this::createAgentConfig);
        app.get("/api/agent-configs/{id}", this::getAgentConfig);
        app.put("/api/agent-configs/{id}", this::updateAgentConfig);
        app.delete("/api/agent-configs/{id}", this::deleteAgentConfig);
        app.get("/api/agent-configs/{id}/sessions", this::listAgentConfigSessions);

        // Ticket-bound sessions
        app.get("/api/tickets/{ticketNo}/sessions", this::listTicketSessions);
        app.post("/api/tickets/{ticketNo}/sessions", this::createTicketSession);
        app.get("/api/tickets/{ticketNo}/chat-images/{file}", this::chatImage);
        app.post("/api/tickets/{ticketNo}/chat-files", this::uploadChatFile);

        // Sessions (Global)
        app.get("/api/sessions/{id}", this::getSession);
        app.patch("/api/sessions/{id}", this::patchSession);
        app.delete("/api/sessions/{id}", this::deleteSession);
        app.get("/api/sessions/{id}/messages", this::listMessages);
        app.get("/api/sessions/{id}/todos", this::getTodos);
        app.get("/api/sessions/{id}/tasks", this::getTasks);
        app.post("/api/sessions/{id}/messages", this::sendMessage);
        app.post("/api/sessions/{id}/abort", this::abortSession);
        app.post("/api/sessions/{id}/model", this::setModel);
        app.get("/api/sessions/{id}/models", this::listModels);
        app.get("/api/sessions/{id}/permissions", this::listPermissions);
        app.post("/api/sessions/{id}/permissions/{permissionId}", this::respondPermission);
        app.get("/api/sessions/{id}/questions", this::listQuestions);
        app.post("/api/sessions/{id}/questions/{requestId}/reply", this::answerQuestion);
        app.post("/api/sessions/{id}/questions/{requestId}/reject", this::rejectQuestion);
        app.get("/api/sessions/{id}/events", this::sessionEvents);

        // Agents Busy
        app.get("/api/agents/busy", this::agentsBusy);
    }

    public void listAgentConfigs(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentConfig c : agentConfigs.findAll()) {
            out.add(agentConfigJson(c));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_configs", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void getAgentConfig(Context ctx) {
        String id = ctx.pathParam("id");
        AgentConfig c = agentConfigs.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such agent config: " + id));
        ctx.status(HttpStatus.OK);
        ctx.json(agentConfigJson(c));
    }

    public void createAgentConfig(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String id = required(req, "id");
        if (agentConfigs.find(id).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "agent config already exists: " + id);
        }
        AgentConfig config = parseAgentConfig(req, id, null);
        agentConfigs.insert(config, clock.now());
        ctx.status(HttpStatus.CREATED);
        ctx.json(agentConfigJson(config));
    }

    public void updateAgentConfig(Context ctx) {
        String id = ctx.pathParam("id");
        AgentConfig existing = agentConfigs.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such agent config: " + id));
        AgentConfig config = parseAgentConfig(Json.parseObject(ctx.body()), id, existing);
        agentConfigs.update(config, clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(agentConfigJson(config));
    }

    public void deleteAgentConfig(Context ctx) {
        String id = ctx.pathParam("id");
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        agentConfigs.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void listAgentConfigSessions(Context ctx) {
        String id = ctx.pathParam("id");
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByAgentConfig(id)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void listTicketSessions(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        if (tickets.find(ticketNo).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByTicket(ticketNo)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void createTicketSession(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        gate.domain.ticket.Ticket ticket = tickets.find(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Map<String, Object> req = Json.parseObject(ctx.body());
        String agentConfigId = str(req, "agent_config_id");
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent_config_id is required");
        }
        String initialPrompt = str(req, "initial_prompt");
        if (initialPrompt == null) {
            initialPrompt = "";
        }
        // Every session gets a freshly minted agent-domain token bound to this ticket (§5.4):
        // the plaintext rides only inside StartRequest.env → the CLI process tree / per-session
        // MCP config under the clone's .git/, and only its hash is persisted.
        Map<String, String> startEnv = credentials == null
                ? Map.of()
                : Map.of(gate.adapters.mcp.McpServer.TOKEN_ENV,
                        credentials.issueAgentToken(ticketNo, clock.now()));
        Session s = agentSessionPort.start(new AgentSessionPort.StartRequest(
                ticketNo, agentConfigId, ticket.clonePath(), ticket.targetRef(),
                initialPrompt, startEnv));
        ctx.status(HttpStatus.CREATED);
        ctx.json(sessionJson(s));
    }

    public void getSession(Context ctx) {
        String id = ctx.pathParam("id");
        Session s = sessionRepository.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + id));
        ctx.status(HttpStatus.OK);
        ctx.json(sessionJson(s));
    }

    /** claude --permission-mode 轮询档位（V24）：PATCH 可接受的枚举集合。 */
    private static final java.util.Set<String> PERMISSION_MODES =
            java.util.Set.of("acceptEdits", "plan", "auto", "bypassPermissions");

    public void patchSession(Context ctx) {
        String id = ctx.pathParam("id");
        Session s = sessionRepository.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + id));
        Map<String, Object> req = Json.parseObject(ctx.body());
        if (!req.containsKey("title") && !req.containsKey("archived")
                && !req.containsKey("permission_auto_accept")
                && !req.containsKey("permission_mode")) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide title, archived, permission_auto_accept, or permission_mode");
        }
        String title = s.title();
        if (req.containsKey("title")) {
            Object rawTitle = req.get("title");
            if (rawTitle == null) {
                title = null;
            } else {
                String t = rawTitle.toString().trim();
                title = t.isEmpty() ? null : t;
            }
        }
        boolean archived = req.containsKey("archived")
                ? Boolean.parseBoolean(String.valueOf(req.get("archived"))) : s.archived();
        boolean permissionAutoAccept = req.containsKey("permission_auto_accept")
                ? Boolean.parseBoolean(String.valueOf(req.get("permission_auto_accept"))) : s.permissionAutoAccept();
        // 权限模式（claude 专属语义）：null = 清除回退默认（acceptEdits）。manual/dontAsk
        // 在 headless -p 无交互面的场景下无意义，不在轮询枚举内即拒收。
        String permissionMode = s.permissionMode();
        if (req.containsKey("permission_mode")) {
            if (s.cli() != AgentCli.CLAUDE) {
                throw new GateException(GateErrorCode.USAGE,
                        "permission_mode is only supported for claude sessions");
            }
            Object rawMode = req.get("permission_mode");
            if (rawMode == null) {
                permissionMode = null;
            } else {
                String m = String.valueOf(rawMode).trim();
                if (!PERMISSION_MODES.contains(m)) {
                    throw new GateException(GateErrorCode.USAGE,
                            "invalid permission_mode: " + m + " (allowed: " + PERMISSION_MODES + ")");
                }
                permissionMode = m;
            }
        }
        Session updated = new Session(s.id(), s.ticketNo(), s.agentConfigId(), s.cli(), s.status(),
                s.cliSessionId(), s.clonePath(), s.allocatedPort(), s.startedAt(), s.finishedAt(),
                s.cumulativeUsage(), title, archived, s.overrideProvider(), s.overrideModel(),
                s.overrideVariant(), permissionAutoAccept, permissionMode);
        sessionRepository.update(updated);
        ctx.status(HttpStatus.OK);
        ctx.json(sessionJson(updated));
    }

    public void deleteSession(Context ctx) {
        String id = ctx.pathParam("id");
        Session s = sessionRepository.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + id));
        // Abort unconditionally: even a non-ACTIVE row may still own an upstream reader or a
        // serve process after edge cases (e.g. an abort that raced a status flip).
        // Pre-mark ABORTED so the opencode adapter takes the hard path (kill serve).
        if (s.status() != gate.domain.session.SessionStatus.ABORTED) {
            sessionRepository.update(s.withStatus(gate.domain.session.SessionStatus.ABORTED)
                    .withFinishedAt(clock.now()));
        }
        agentSessionPort.abort(id);
        sessionRepository.deleteMessages(id);
        sessionRepository.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void listMessages(Context ctx) {
        String id = ctx.pathParam("id");
        if (sessionRepository.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + id);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var m : sessionRepository.findMessages(id)) {
            out.add(messageJson(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", out);
        // 任务清单随消息历史附带（V21）：前端切换会话时一次请求同时拿到清单，
        // 不再全量扫历史反解最后一条 todowrite。无行（从未写过 todo）为空数组。
        body.put("todos", todosJson(id));
        // claude 任务 journal（V24）同样随消息历史附带：TaskCreate/TaskUpdate 平行链的
        // 投影，opencode 会话恒为空数组（工具名天然隔离）。
        body.put("tasks", tasksJson(id));
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** 会话任务清单（V21）：单行快照 + lazy 回填，供轮询兜底与切会话附带查询。 */
    public void getTodos(Context ctx) {
        String id = ctx.pathParam("id");
        if (sessionRepository.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("todos", todosJson(id));
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** claude 任务 journal（V24）：TaskCreate/TaskUpdate 事件累积，供轮询兜底与切会话附带查询。 */
    public void getTasks(Context ctx) {
        String id = ctx.pathParam("id");
        if (sessionRepository.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", tasksJson(id));
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** 快照 JSON 字符串 → 解析后的数组（无行/解析失败按空清单）。 */
    private List<Object> todosJson(String sessionId) {
        String json = sessionRepository.findTodos(sessionId).orElse(null);
        if (json == null) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 任务 journal JSON 字符串 → 解析后的数组（无行/解析失败按空列表）。 */
    private List<Object> tasksJson(String sessionId) {
        String json = sessionRepository.findTasks(sessionId).orElse(null);
        if (json == null) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    public void sendMessage(Context ctx) {
        String sessionId = ctx.pathParam("id");
        Session session = sessionRepository.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "no such session: " + sessionId));
        Map<String, Object> req = Json.parseObject(ctx.body());
        String message = str(req, "message");
        String delivery = str(req, "delivery");
        List<AgentSessionPort.Attachment> attachments = parseAttachments(req);
        if ((message == null || message.isBlank()) && attachments.isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "message is required");
        }
        applyModelOverrideIfPresent(sessionId, req);
        // 缩略图写进工单克隆 .gate/chat-images/（不落库），消息文本追加引用行：
        // 历史重载据此在用户气泡还原缩略图；Agent 也由此得知缩略图在工作区的位置。
        List<String> imagePaths = saveChatThumbnails(session, attachments);
        String outgoing = appendChatImageRefs(message == null ? "" : message, imagePaths);
        // T-107 渲染修复：client_message_id 直通——USER 行以该 id 落库，乐观气泡与落库行
        // 同 id，历史重载原位对账；插队段的行 id 锚点也依赖这一直通。非法/缺省即服务端自配。
        String clientMessageId = str(req, "client_message_id");
        if (clientMessageId != null && !clientMessageId.matches("[A-Za-z0-9_-]{4,64}")) {
            clientMessageId = null;
        }
        String taskId = agentSessionPort.sendMessage(
                new AgentSessionPort.SendRequest(sessionId, outgoing, true, attachments, delivery,
                        clientMessageId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        if (clientMessageId != null) {
            body.put("message_id", clientMessageId);
        }
        if (!imagePaths.isEmpty()) {
            body.put("images", imagePaths);
        }
        ctx.status(HttpStatus.ACCEPTED);
        ctx.json(body);
    }

    /** 读取工单克隆内 .gate/chat-images/ 下的缩略图（带鉴权；文件名白名单 + 路径收敛防穿越）。 */
    public void chatImage(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        String file = ctx.pathParam("file");
        if (!file.matches("[A-Za-z0-9._-]+")) {
            throw new GateException(GateErrorCode.USAGE, "bad chat image name");
        }
        int dot = file.lastIndexOf('.');
        String ext = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = CHAT_IMAGE_MIME_BY_EXT.get(ext);
        if (mime == null) {
            throw new GateException(GateErrorCode.USAGE, "bad chat image extension");
        }
        Ticket ticket = tickets.find(ticketNo)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Path base = Path.of(ticket.clonePath()).resolve(CHAT_IMAGE_DIR).normalize();
        Path target = base.resolve(file).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            throw new GateException(GateErrorCode.USAGE, "no such chat image");
        }
        ctx.contentType(mime);
        try {
            ctx.result(Files.newInputStream(target));
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "read chat image failed: " + e.getMessage());
        }
    }

    /**
     * 粘贴/拖入的非图片附件落盘：浏览器拿不到被复制文件的真实路径（剪贴板只有
     * 文件本体），前端把内容以 base64 传到这里，写入工单克隆 .gate/chat-files/ 并
     * 回传克隆内相对路径，由前端插进消息文本；会话 cwd 即克隆根，Agent 直接可读。
     */
    public void uploadChatFile(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket ticket = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        if (ticket.clonePath() == null || ticket.clonePath().isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "ticket has no workspace: " + ticketNo);
        }
        Map<String, Object> req = Json.parseObject(ctx.body());
        String filename = str(req, "filename");
        if (filename == null || filename.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "filename is required");
        }
        String dataBase64 = str(req, "data_base64");
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "data_base64 is required");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(dataBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new GateException(GateErrorCode.USAGE, "data_base64 is not valid base64");
        }
        if (raw.length == 0) {
            throw new GateException(GateErrorCode.USAGE, "chat file is empty");
        }
        if (raw.length > MAX_CHAT_FILE_BYTES) {
            throw new GateException(GateErrorCode.USAGE,
                    "chat file too large: " + raw.length + " bytes (max " + MAX_CHAT_FILE_BYTES + ")");
        }
        try {
            Path clone = Path.of(ticket.clonePath());
            Path dir = clone.resolve(CHAT_FILE_DIR);
            Files.createDirectories(dir);
            excludeGateDir(clone);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault()).format(clock.now());
            // 时间戳 + 纳秒序号防同秒同名覆盖；落点由本端拼装，文件名已被白名单替换。
            String name = stamp + "-" + Long.toString(System.nanoTime(), 36) + "-"
                    + sanitizeChatFileName(filename);
            Files.write(dir.resolve(name), raw);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", CHAT_FILE_DIR + "/" + name);
            ctx.status(HttpStatus.CREATED);
            ctx.json(body);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "save chat file failed: " + e.getMessage());
        }
    }

    /**
     * 图片附件缩略图落盘到工单克隆 .gate/chat-images/（尽力而为，失败不阻断发送）：
     * 原样保存——最长边 ≤1024px 的预缩放在前端 canvas 完成（java.desktop/ImageIO
     * 在 native-image 二进制里不可用，无 awt 本地库，历史上在桌面端发送必炸 500，
     * 参见 T-112）；目录追加进 .git/info/exclude，不污染 git status。
     * 返回引用行用的相对路径列表。注意 catch Throwable：类初始化失败等 Error
     * 同样不能打断发送（用户消息落点在其后的 sendMessage）。
     */
    private List<String> saveChatThumbnails(Session session, List<AgentSessionPort.Attachment> attachments) {
        List<String> saved = new ArrayList<>();
        if (attachments == null || attachments.isEmpty()) {
            return saved;
        }
        try {
            Path clone = Path.of(session.clonePath());
            Path dir = clone.resolve(CHAT_IMAGE_DIR);
            Files.createDirectories(dir);
            excludeGateDir(clone);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault()).format(clock.now());
            int n = 0;
            for (AgentSessionPort.Attachment att : attachments) {
                String ext = CHAT_IMAGE_EXT_BY_MIME.get(att.mime());
                if (ext == null) {
                    continue;
                }
                byte[] raw;
                try {
                    raw = Base64.getDecoder().decode(att.dataBase64().trim());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                n++;
                String name = stamp + "-" + Long.toString(System.nanoTime(), 36) + "-"
                        + sanitizeImageName(att.filename(), n) + "." + ext;
                Files.write(dir.resolve(name), raw);
                saved.add(CHAT_IMAGE_DIR + "/" + name);
            }
        } catch (Throwable e) {
            System.err.println("[chat-image] save thumbnail failed: " + e);
        }
        return saved;
    }

    /** 引用行追加在消息文本尾部：历史与 Agent 同源可见，格式 [图片引用 #n] <相对路径>。 */
    private static String appendChatImageRefs(String message, List<String> imagePaths) {
        if (imagePaths.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message == null ? "" : message);
        for (int i = 0; i < imagePaths.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[图片引用 #").append(i + 1).append("] ").append(imagePaths.get(i));
        }
        return sb.toString();
    }

    /** 文件名主干白名单化（保留可读性，防路径穿越由端点侧二次校验兜底）。 */
    private static String sanitizeImageName(String filename, int idx) {
        String base = filename == null ? "" : filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || "_".equals(base)) {
            base = "image-" + idx;
        }
        return base.length() > 32 ? base.substring(0, 32) : base;
    }

    /**
     * 粘贴文件的全名白名单化（保留扩展名的可读性；分隔符一并替换，落点由本端
     * 拼装，无穿越面；`..` 单独设防防 resolve 成上级目录）。
     */
    private static String sanitizeChatFileName(String filename) {
        String base = filename == null ? "" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            base = "file";
        }
        // 尾部 64 字符：尽量保住扩展名。
        return base.length() > 64 ? base.substring(base.length() - 64) : base;
    }

    /** .gate/ 写进克隆本地 .git/info/exclude：不改工作区文件、不进 git status。 */
    private static void excludeGateDir(Path clone) {
        try {
            Path exclude = clone.resolve(".git").resolve("info").resolve("exclude");
            if (!Files.isWritable(exclude)) {
                return;
            }
            String content = Files.readString(exclude);
            if (!content.contains(".gate/")) {
                String sep = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
                Files.writeString(exclude, content + sep + ".gate/\n");
            }
        } catch (Exception e) {
            System.err.println("[chat-file] update git exclude failed: " + e);
        }
    }

    public void abortSession(Context ctx) {
        String id = ctx.pathParam("id");
        if (sessionRepository.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + id);
        }
        agentSessionPort.abort(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void setModel(Context ctx) {
        String sessionId = ctx.pathParam("id");
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        Map<String, Object> req = Json.parseObject(ctx.body());
        Session updated = s.withModelOverride(
                mergeOverridePart(s.overrideProvider(), req, "provider_id"),
                mergeOverridePart(s.overrideModel(), req, "model_id"),
                mergeOverridePart(s.overrideVariant(), req, "variant"));
        if (updated.overrideProvider() != null && updated.overrideModel() == null
                || updated.overrideProvider() == null && updated.overrideModel() != null) {
            throw new GateException(GateErrorCode.USAGE,
                    "provider_id and model_id must be provided together");
        }
        sessionRepository.update(updated);
        ctx.status(HttpStatus.OK);
        ctx.json(sessionJson(updated));
    }

    public void listModels(Context ctx) {
        String sessionId = ctx.pathParam("id");
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        int port;
        try {
            // opencode：端口可能已随后端重启失效——懒复活重建 serve 后再拉目录，
            // 否则模型选择器在旧会话上永远为空（无法切换）。
            port = agentSessionPort.ensureEndpoint(sessionId);
        } catch (UnsupportedOperationException e) {
            // claude 等 headless CLI 没有端口语义：会话行记录的端口恒为 -1。
            port = s.allocatedPort();
        }
        if (port <= 0) {
            ctx.status(HttpStatus.OK);
            ctx.json(agentProviderCatalog(s));
            return;
        }
        ctx.status(HttpStatus.OK);
        ctx.json(modelCatalog.fetch(port));
    }

    /**
     * claude 会话的模型目录（与 opencode 的 serve 目录代理是两条路径）：headless CLI 没有
     * {@code /config/providers} 可代理，目录只来自 Agent <b>显式绑定</b> 的 Provider 的模型
     * 缓存（设置中心拉取/手填），每个模型附上 claude 固有的推理强度档位。
     *
     * <p>不做"其他 Provider 缓存"兜底：claude CLI 用它自己的网关与密钥鉴权，模型可用性
     * 以 claude 侧为准，别的 Provider 缓存（为审查引擎/opencode 拉取的）对 claude 没有语义。
     * 绑定 cli-default（CLI 自管模型）或缓存为空时目录为空——前端 claude 路径支持直接
     * 手输模型 ID，不依赖目录。
     */
    private Map<String, Object> agentProviderCatalog(Session s) {
        List<Map<String, Object>> providersOut = new ArrayList<>();
        AgentConfig config = agentConfigs.find(s.agentConfigId()).orElse(null);
        if (config != null && config.providerId() != null) {
            ProviderRepository.ProviderRow row = providers.find(config.providerId()).orElse(null);
            List<String> models = row == null ? List.of() : providers.models(row.id());
            if (row != null && !models.isEmpty()) {
                providersOut.add(providerEntry(row.id(), row.name(), models));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providersOut);
        out.put("source", "agent-provider");
        if (providersOut.isEmpty()) {
            out.put("note", "claude 自管模型/网关：目录为空时可直接在模型选择器输入模型 ID，"
                    + "或在 Agent 配置绑定对应 Provider 并维护其模型列表");
        }
        return out;
    }

    private static Map<String, Object> providerEntry(String id, String name, List<String> models) {
        List<Map<String, Object>> modelsOut = new ArrayList<>();
        for (String modelId : models) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", modelId);
            m.put("name", modelId);
            m.put("variants", CLAUDE_EFFORT_VARIANTS);
            modelsOut.add(m);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("models", modelsOut);
        return p;
    }

    public void listPermissions(Context ctx) {
        String sessionId = ctx.pathParam("id");
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        List<PermissionRequest> list = agentSessionPort.pendingPermissions(sessionId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PermissionRequest r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("permission_id", r.permissionId());
            m.put("permission", r.permission());
            m.put("patterns", r.patterns());
            m.put("always", r.always());
            m.put("metadata", r.metadata());
            m.put("message_id", r.messageId());
            m.put("call_id", r.callId());
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("permissions", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void respondPermission(Context ctx) {
        String sessionId = ctx.pathParam("id");
        String permissionId = ctx.pathParam("permissionId");
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        Map<String, Object> req = Json.parseObject(ctx.body());
        String response = str(req, "response");
        if (response == null || response.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "response is required");
        }
        String normalized = response.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("once", "always", "reject").contains(normalized)) {
            throw new GateException(GateErrorCode.USAGE,
                    "response must be one of once | always | reject, got " + response);
        }
        agentSessionPort.respondPermission(sessionId, permissionId, normalized);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void listQuestions(Context ctx) {
        String sessionId = ctx.pathParam("id");
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (gate.domain.session.QuestionRequest r : agentSessionPort.pendingQuestions(sessionId)) {
            out.add(questionJson(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("questions", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void answerQuestion(Context ctx) {
        String sessionId = ctx.pathParam("id");
        String requestId = ctx.pathParam("requestId");
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        Map<String, Object> req = Json.parseObject(ctx.body());
        List<List<String>> answers = parseAnswers(req.get("answers"));
        if (answers.isEmpty()) {
            throw new GateException(GateErrorCode.USAGE,
                    "answers is required: one selected-label array per question, in order");
        }
        agentSessionPort.respondQuestion(sessionId, requestId, answers);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void rejectQuestion(Context ctx) {
        String sessionId = ctx.pathParam("id");
        String requestId = ctx.pathParam("requestId");
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        agentSessionPort.rejectQuestion(sessionId, requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** answers: [[label,…],…] — one entry per question; entries must be non-null. */
    private static List<List<String>> parseAnswers(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "answers must be an array of label arrays");
        }
        List<List<String>> out = new ArrayList<>();
        for (Object item : list) {
            List<String> picked = new ArrayList<>();
            if (item instanceof List<?> labels) {
                for (Object label : labels) {
                    if (label == null) {
                        continue;
                    }
                    String v = String.valueOf(label).trim();
                    if (!v.isEmpty()) {
                        picked.add(v);
                    }
                }
            } else if (item != null && !String.valueOf(item).isBlank()) {
                // Tolerate a bare string per question (single-select shorthand).
                picked.add(String.valueOf(item).trim());
            }
            out.add(picked);
        }
        return out;
    }

    private static Map<String, Object> questionJson(gate.domain.session.QuestionRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", r.requestId());
        List<Object> prompts = new ArrayList<>();
        for (gate.domain.session.QuestionRequest.QuestionPrompt p : r.questions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("question", p.question());
            q.put("header", p.header());
            List<Object> options = new ArrayList<>();
            for (gate.domain.session.QuestionRequest.QuestionOption o : p.options()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.label());
                om.put("description", o.description());
                options.add(om);
            }
            q.put("options", options);
            q.put("multiple", p.multiple());
            q.put("custom", p.custom());
            prompts.add(q);
        }
        m.put("questions", prompts);
        m.put("message_id", r.messageId());
        m.put("call_id", r.callId());
        return m;
    }

    public void sessionEvents(Context ctx) {
        String id = ctx.pathParam("id");
        if (sessionRepository.find(id).isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", "no such session: " + id, null));
            return;
        }
        startSse(ctx, client -> sessionSseHandler.handle(client, id));
    }

    public void agentsBusy(Context ctx) {
        // 有进行中回合的 session id 快照来源于各 adapter 的 in-flight registry，聚合并排序
        Set<String> ids = agentSessionPort.busySessionIds();
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        List<Map<String, Object>> running = new ArrayList<>();
        for (String sid : sorted) {
            Optional<Session> opt = sessionRepository.find(sid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", sid);
            if (opt.isPresent()) {
                Session s = opt.get();
                m.put("title", s.title());
                m.put("ticket_no", s.ticketNo());
                m.put("cli", s.cli() == null ? null : s.cli().name());
            } else {
                // 查不到会话记录的 id 仍计入 count 并保留 session_id，其余字段为 null
                m.put("title", null);
                m.put("ticket_no", null);
                m.put("cli", null);
            }
            running.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", running.size());
        body.put("running", running);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private static void startSse(Context ctx, java.util.function.Consumer<SseClient> consumer) {
        ctx.res().setStatus(200);
        ctx.res().setCharacterEncoding("UTF-8");
        ctx.res().setContentType("text/event-stream");
        // 禁加 Connection: close——Jetty 会按 Connection 响应头立刻关连接，
        // EventSource 的断流重连永远追不上已经 closed 的连接（多会话失活的直接诱因）。
        ctx.res().addHeader("Cache-Control", "no-cache");
        ctx.res().addHeader("X-Accel-Buffering", "no");
        try {
            ctx.res().flushBuffer();
        } catch (IOException ignored) {
        }
        SseClient client = new SseClient(ctx);
        consumer.accept(client);
    }

    private void applyModelOverrideIfPresent(String sessionId, Map<String, Object> req) {
        boolean hasProvider = req.containsKey("provider_id");
        boolean hasModel = req.containsKey("model_id");
        boolean hasVariant = req.containsKey("variant");
        if (!hasProvider && !hasModel && !hasVariant) {
            return;
        }
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        Session updated = s.withModelOverride(
                mergeOverridePart(s.overrideProvider(), req, "provider_id"),
                mergeOverridePart(s.overrideModel(), req, "model_id"),
                mergeOverridePart(s.overrideVariant(), req, "variant"));
        if (updated.overrideProvider() != null && updated.overrideModel() == null
                || updated.overrideProvider() == null && updated.overrideModel() != null) {
            throw new GateException(GateErrorCode.USAGE,
                    "provider_id and model_id must be provided together");
        }
        sessionRepository.update(updated);
    }

    private static String mergeOverridePart(String current, Map<String, Object> req, String key) {
        if (!req.containsKey(key)) {
            return current;
        }
        Object raw = req.get(key);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static List<AgentSessionPort.Attachment> parseAttachments(Map<String, Object> req) {
        Object raw = req.get("attachments");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "attachments must be an array");
        }
        if (list.isEmpty()) {
            return List.of();
        }
        if (list.size() > MAX_ATTACHMENTS) {
            throw new GateException(GateErrorCode.USAGE,
                    "at most " + MAX_ATTACHMENTS + " attachments per message");
        }
        List<AgentSessionPort.Attachment> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new GateException(GateErrorCode.USAGE, "each attachment must be an object");
            }
            String mime = attr(m, "mime");
            String normalizedMime = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
            if (!IMAGE_MIMES.contains(normalizedMime)) {
                throw new GateException(GateErrorCode.USAGE,
                        "unsupported attachment mime: " + mime + " (supported: " + IMAGE_MIMES + ")");
            }
            String dataBase64 = attr(m, "data_base64");
            if (dataBase64 == null || dataBase64.isBlank()) {
                throw new GateException(GateErrorCode.USAGE, "attachment data_base64 is required");
            }
            String filename = attr(m, "filename");
            out.add(new AgentSessionPort.Attachment(
                    filename == null || filename.isBlank() ? null : filename.trim(),
                    normalizedMime,
                    dataBase64.trim()));
        }
        return List.copyOf(out);
    }

    private static String attr(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static AgentConfig parseAgentConfig(Map<String, Object> req, String id, AgentConfig existing) {
        String configId = id;
        if (configId == null) {
            configId = str(req, "id");
            if (configId == null || configId.isBlank()) {
                throw new GateException(GateErrorCode.USAGE, "agent config id is required");
            }
        }
        String name = str(req, "name");
        if (name == null || name.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config name is required");
        }
        String cliStr = str(req, "cli");
        if (cliStr == null || cliStr.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config cli is required");
        }
        AgentCli cli;
        try {
            cli = AgentCli.valueOf(cliStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GateException(GateErrorCode.USAGE, "unknown agent cli: " + cliStr);
        }
        // provider/model 可空：本地 CLI 会话把选型交给 CLI 自身配置（ADR-12）。
        String providerId = str(req, "provider_id");
        String model = str(req, "model");
        List<String> flags = new ArrayList<>();
        Object flagsRaw = req.get("extra_flags");
        if (flagsRaw instanceof List<?> list) {
            for (Object f : list) {
                flags.add(String.valueOf(f));
            }
        }
        String systemPrompt = str(req, "system_prompt");
        String description = str(req, "description");
        // 缺省注入：请求未携带 inject_context 时视为开启（与 UI 开关默认值一致）。
        Object injectRaw = req.get("inject_context");
        boolean injectContext = !(injectRaw instanceof Boolean b) || b;
        return new AgentConfig(configId, name, cli, providerId, model,
                systemPrompt, flags, description, injectContext);
    }

    private static Map<String, Object> agentConfigJson(AgentConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("cli", c.cli().name());
        m.put("provider_id", c.providerId());
        m.put("model", c.model());
        m.put("system_prompt", c.systemPrompt());
        m.put("extra_flags", c.extraFlags());
        m.put("description", c.description());
        m.put("inject_context", c.injectContext());
        return m;
    }

    public static Map<String, Object> sessionJson(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ticket_no", s.ticketNo());
        m.put("agent_config_id", s.agentConfigId());
        m.put("cli", s.cli().name());
        m.put("status", s.status().name());
        m.put("title", s.title());
        m.put("archived", s.archived());
        m.put("cli_session_id", s.cliSessionId());
        m.put("clone_path", s.clonePath());
        m.put("allocated_port", s.allocatedPort());
        m.put("override_provider", s.overrideProvider());
        m.put("override_model", s.overrideModel());
        m.put("override_variant", s.overrideVariant());
        m.put("permission_auto_accept", s.permissionAutoAccept());
        m.put("permission_mode", s.permissionMode());
        m.put("started_at", s.startedAt().toString());
        m.put("finished_at", s.finishedAt() == null ? null : s.finishedAt().toString());
        if (s.cumulativeUsage() == null) {
            m.put("cumulative_usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", s.cumulativeUsage().promptTokens());
            u.put("completion_tokens", s.cumulativeUsage().completionTokens());
            u.put("total_tokens", s.cumulativeUsage().totalTokens());
            m.put("cumulative_usage", u);
        }
        return m;
    }

    private static Map<String, Object> messageJson(gate.domain.session.SessionMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("session_id", m.sessionId());
        out.put("role", m.role().name());
        out.put("content", m.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (gate.domain.session.ToolCall tc : m.toolCalls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", tc.name());
            cm.put("arguments_json", tc.argumentsJson());
            cm.put("result_json", tc.resultJson());
            calls.add(cm);
        }
        out.put("tool_calls", calls);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (gate.domain.session.TurnPart p : m.parts()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("type", p.type());
            if (p.isTool()) {
                pm.put("name", p.name());
                pm.put("arguments_json", p.argumentsJson());
                pm.put("result_json", p.resultJson());
            } else if (p.isSteer()) {
                // T-107 渲染修复：name = 被吞并 USER 行的 id，前端历史重建按其去重并原位渲染。
                pm.put("name", p.name());
                pm.put("text", p.text());
            } else {
                pm.put("text", p.text());
            }
            parts.add(pm);
        }
        out.put("parts", parts);
        if (m.usage() == null) {
            out.put("usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", m.usage().promptTokens());
            u.put("completion_tokens", m.usage().completionTokens());
            u.put("total_tokens", m.usage().totalTokens());
            out.put("usage", u);
        }
        out.put("degraded", m.degraded());
        out.put("timestamp", m.timestamp().toString());
        // V22 逐消息模型标注：上游实际值（opencode info / claude stream-json），
        // 存量行为 null——前端回退会话当前模型的近似标注。
        out.put("model_provider", m.modelProvider());
        out.put("model_id", m.modelId());
        // V23 逐消息推理强度：发送端钉住的请求值（上游不回传 effort），null 同上回退。
        out.put("reasoning_variant", m.reasoningVariant());
        return out;
    }

    private static String required(Map<String, Object> req, String key) {
        String value = str(req, key);
        if (value == null || value.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, key + " is required");
        }
        return value.trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }
}
