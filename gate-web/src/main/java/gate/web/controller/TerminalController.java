package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.ports.store.CredentialRepository;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目 → 终端 (web console): list the project's clone directories and drive an interactive shell
 * session pinned to one of them.
 *
 * <p>Directory enumeration is derived from data the gate already owns: the project workspace it
 * was registered from plus the per-ticket clones under {@code clones_root} (Ticket.clonePath). No
 * filesystem scanning beyond existence checks, so the list is exactly "what belongs to this
 * project".
 *
 * <p>The terminal is a plain {@code cmd.exe} (Windows) / {@code bash -i} (Unix) child process with
 * its working directory set to the chosen clone; I/O is piped (no ConPTY) and carried over a
 * WebSocket in small JSON envelopes. All client→server frames must be JSON; the first frame must
 * be {@code start} carrying the HUMAN web token (the WS upgrade itself is not authenticated —
 * Javalin 5 has no upgrade-stage hook — so authorization happens before any process is spawned).
 * Server→client frames are {@code data} (decoded shell output), {@code exit} and {@code error}.
 * Process output is decoded with the platform console charset ({@code sun.jnu.encoding}) so
 * localized tool output survives the pipe.
 */
public final class TerminalController implements WebController {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalController.class);

    /** Upper bound on concurrently live shell processes (one per WebSocket session). */
    private static final int MAX_SESSIONS = 8;

    private static final String CTRL_C = "\u0003";

    private static final ConcurrentHashMap<WsContext, TerminalSession> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger LIVE_SESSIONS = new AtomicInteger();

    /**
     * 错误帧后延迟关闭用。sendError 后立即 close 会与未 flush 的 error 帧竞态——客户端只看到
     * 1006 静默断开、拿不到任何提示（前端表现为"永远空白"），延迟一小段保证错误帧先落地。
     */
    private static final ScheduledExecutorService CLOSE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "terminal-close-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ProjectRepository projects;
    private final TicketRepository tickets;
    private final CredentialRepository credentials;

    public TerminalController(ProjectRepository projects, TicketRepository tickets,
            CredentialRepository credentials) {
        this.projects = projects;
        this.tickets = tickets;
        this.credentials = credentials;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/projects/{id}/terminals", this::listTerminals);
        app.ws("/ws/terminal", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
            ws.onError(this::onError);
        });
    }

    /** GET /api/projects/{id}/terminals — workspace + per-ticket clone directories. */
    public void listTerminals(Context ctx) {
        Project p = requireProject(ctx.pathParam("id"));
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry(p.workspacePath(), "工作区", "workspace", null, null));

        List<Ticket> projectTickets = new ArrayList<>(tickets.findAllByProject(p.id()));
        projectTickets.sort(Comparator.comparing(Ticket::updatedAt).reversed());
        Set<String> seen = new LinkedHashSet<>();
        for (Ticket t : projectTickets) {
            String clone = t.clonePath();
            if (clone == null || clone.isBlank() || !seen.add(clone)) {
                continue;
            }
            entries.add(entry(clone, t.ticketNo(), "clone", t.ticketNo(), t.title()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("entries", entries);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /* ─── websocket session handling ─── */

    private void onConnect(WsConnectContext ctx) {
        ctx.attribute("started", Boolean.FALSE);
    }

    private void onMessage(WsMessageContext ctx) {
        // 管线兜底收 Throwable（含 Error）：native 镜像缺运行时元数据时抛的是
        // NoClassDefFoundError 这类 Error，不接住就是"零日志静默断连"，CI 冒烟
        // 只能看到客户端侧 EOF，永远定位不到是哪一环炸了。
        try {
            dispatchMessage(ctx);
        } catch (Throwable t) {
            LOG.error("terminal ws pipeline failure: {}", t.toString(), t);
            sendErrorThenClose(ctx, "terminal pipeline failure: " + t);
        }
    }

    private void dispatchMessage(WsMessageContext ctx) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(ctx.message());
        } catch (GateException e) {
            sendError(ctx, "malformed message");
            return;
        }
        String op = String.valueOf(msg.getOrDefault("op", ""));

        if (Boolean.FALSE.equals(ctx.attribute("started"))) {
            handleStart(ctx, op, msg);
            return;
        }
        TerminalSession session = SESSIONS.get(ctx);
        if (session == null) {
            sendError(ctx, "no live terminal on this connection");
            return;
        }
        switch (op) {
            case "input" -> session.writeInput(String.valueOf(msg.getOrDefault("data", "")));
            case "ping" -> sendFrame(ctx, Map.of("op", "pong")); // 心跳回包（客户端 15s 一拍，防 Jetty idle 断连）
            case "stop" -> ctx.session.close();
            default -> sendError(ctx, "unsupported op: " + op);
        }
    }

    /** First frame on a connection: authenticate, resolve the directory allowlist, spawn the shell. */
    private void handleStart(WsContext ctx, String op, Map<String, Object> msg) {
        if (!op.equals("start")) {
            sendError(ctx, "first message must be {op: start}");
            return;
        }
        String token = String.valueOf(msg.getOrDefault("token", ""));
        CredentialRepository.Domain domain = credentials.validate(token);
        if (!domain.isValid() || !domain.isHuman()) {
            sendErrorThenClose(ctx, "invalid or non-HUMAN token (ADR-10)");
            return;
        }
        Project p;
        try {
            p = requireProject(String.valueOf(msg.getOrDefault("project", "")));
        } catch (GateException e) {
            sendErrorThenClose(ctx, e.getMessage());
            return;
        }
        Path dir = Path.of(String.valueOf(msg.getOrDefault("dir", ""))).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir) || !isProjectDirectory(p, dir)) {
            sendErrorThenClose(ctx, "directory is not part of this project: " + dir);
            return;
        }
        if (LIVE_SESSIONS.get() >= MAX_SESSIONS) {
            sendErrorThenClose(ctx, "too many live terminals (" + MAX_SESSIONS + " max)");
            return;
        }
        try {
            TerminalSession session = new TerminalSession(ctx, dir);
            SESSIONS.put(ctx, session);
            ctx.attribute("started", Boolean.TRUE);
            session.start();
            ctx.send(Json.write(Map.of("op", "started", "dir", dir.toString())));
        } catch (IOException e) {
            LOG.warn("terminal spawn failed for {}: {}", dir, e.getMessage());
            sendErrorThenClose(ctx, "cannot spawn shell: " + e.getMessage());
        }
    }

    private void onClose(WsCloseContext ctx) {
        TerminalSession session = SESSIONS.remove(ctx);
        if (session != null) {
            session.close();
        }
    }

    private void onError(WsErrorContext ctx) {
        // Jetty 侧的异常此前被整只吞掉——WS 管线在 native 里出问题时这里是唯一的
        // 现场证据，必须 ERROR 级落日志（配合 CI 冒烟的 boot.log dump）。
        Throwable error = ctx.error();
        LOG.error("terminal ws error on connection: {}", error == null ? "unknown" : error.toString(), error);
        TerminalSession session = SESSIONS.remove(ctx);
        if (session != null) {
            session.close();
        }
    }

    private void sendError(WsContext ctx, String message) {
        try {
            ctx.send(Json.write(Map.of("op", "error", "message", message)));
        } catch (Exception e) {
            LOG.warn("terminal send failed: {}", e.toString());
        }
    }

    /** sendError 后延迟一小段再 close：保证错误帧先送达客户端（立即 close 会把它挤掉）。 */
    private void sendErrorThenClose(WsContext ctx, String message) {
        sendError(ctx, message);
        CLOSE_SCHEDULER.schedule(() -> {
            try {
                ctx.session.close();
            } catch (Exception e) {
                LOG.debug("terminal close failed: {}", e.getMessage());
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static void sendFrame(WsContext ctx, Map<String, Object> payload) {
        try {
            ctx.send(Json.write(payload));
        } catch (Exception e) {
            LOG.warn("terminal send failed: {}", e.toString());
        }
    }

    /* ─── helpers ─── */

    private Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }

    /**
     * A directory may host a terminal only if the gate itself created/registered it: the project
     * workspace or one of the project ticket clones. This keeps the shell away from arbitrary
     * local paths even though the client supplies the path.
     */
    private boolean isProjectDirectory(Project p, Path candidate) {
        if (candidate.equals(Path.of(p.workspacePath()).toAbsolutePath().normalize())) {
            return true;
        }
        for (Ticket t : tickets.findAllByProject(p.id())) {
            String clone = t.clonePath();
            if (clone == null || clone.isBlank()) {
                continue;
            }
            if (candidate.equals(Path.of(clone).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> entry(String path, String label, String type, String ticketNo,
            String ticketTitle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("label", label);
        m.put("type", type);
        m.put("ticket_no", ticketNo);
        m.put("ticket_title", ticketTitle);
        m.put("exists", Files.isDirectory(Path.of(path)));
        return m;
    }

    /**
     * One piped shell process attached to one WebSocket. Output is pumped on a daemon thread and
     * decoded incrementally (the decoder carries incomplete multibyte sequences across reads).
     */
    private static final class TerminalSession {

        private final WsContext ctx;
        private final Path dir;
        private Charset charset;
        private final boolean windows;
        private Process process;
        private Thread pump;
        /** Bumped on every spawn: only the pump of the CURRENT generation may report exit. */
        private long generation;
        private volatile boolean closed;

        TerminalSession(WsContext ctx, Path dir) {
            this.ctx = ctx;
            this.dir = dir;
            this.windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .contains("windows");
            this.charset = Charset.defaultCharset();
        }

        private static Charset consoleCharset(boolean windows) {
            if (!windows) {
                return Charset.defaultCharset();
            }
            return detectWindowsConsoleCharset();
        }

        /**
         * The child cmd.exe converts its output with the console code page it inherits from us —
         * which varies (936 in a plain console, 65001 under MSYS terminals). Ask it directly
         * (cmd /c chcp) and map the number, instead of guessing from JVM properties.
         */
        private static Charset detectWindowsConsoleCharset() {
            try {
                Process p = new ProcessBuilder("cmd.exe", "/c", "chcp")
                        .redirectErrorStream(true).start();
                String out;
                try (InputStream in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                p.waitFor(5, TimeUnit.SECONDS);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+")
                        .matcher(out);
                if (m.find()) {
                    return codepageCharset(Integer.parseInt(m.group()));
                }
            } catch (Exception e) {
                LOG.debug("console codepage detection failed: {}", e.getMessage());
            }
            return Charset.forName("GBK");
        }

        private static Charset codepageCharset(int cp) {
            return switch (cp) {
                case 65001 -> java.nio.charset.StandardCharsets.UTF_8;
                case 936 -> Charset.forName("GBK");
                case 950 -> Charset.forName("Big5");
                case 932 -> Charset.forName("Shift_JIS");
                case 949 -> Charset.forName("EUC-KR");
                case 437 -> Charset.forName("IBM437");
                case 850 -> Charset.forName("IBM850");
                case 1252 -> Charset.forName("windows-1252");
                default -> {
                    try {
                        yield Charset.forName("windows-" + cp);
                    } catch (Exception e) {
                        yield Charset.defaultCharset();
                    }
                }
            };
        }

        synchronized void start() throws IOException {
            if (windows) {
                charset = consoleCharset(true);
            }
            LIVE_SESSIONS.incrementAndGet();
            spawn();
        }

        private void spawn() throws IOException {
            // /Q：cmd 从管道读入的命令默认会回显命令行，与前端本地回显叠加成两遍；关掉它。
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd.exe", "/Q")
                    : new ProcessBuilder("/bin/bash", "-i");
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process fresh = pb.start();
            this.process = fresh;
            long gen = ++generation;
            Thread t = new Thread(() -> pump(fresh, gen), "terminal-pump");
            t.setDaemon(true);
            this.pump = t;
            t.start();
        }

        /** Destroys the current shell and starts a fresh one in the same directory (Ctrl+C path). */
        synchronized void respawn() {
            if (closed) {
                return;
            }
            destroyProcess();
            try {
                spawn();
            } catch (IOException e) {
                LOG.warn("terminal respawn failed: {}", e.getMessage());
                send(Map.of("op", "error", "message", "cannot respawn shell: " + e.getMessage()));
            }
        }

        synchronized void writeInput(String data) {
            if (closed || process == null) {
                return;
            }
            if (data.contains(CTRL_C)) {
                // Piped stdin cannot deliver SIGINT/VK_BREAK: emulate ^C by recycling the shell.
                send(Map.of("op", "data", "data", "^C\r\n"));
                respawn();
                return;
            }
            try {
                OutputStream out = process.getOutputStream();
                out.write(data.getBytes(charset));
                out.flush();
            } catch (IOException e) {
                LOG.debug("terminal input dropped: {}", e.getMessage());
            }
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            destroyProcess();
            LIVE_SESSIONS.decrementAndGet();
        }

        private void destroyProcess() {
            if (process != null) {
                process.destroyForcibly();
                process = null;
            }
            if (pump != null) {
                pump.interrupt();
                pump = null;
            }
        }

        private void pump(Process proc, long gen) {
            CharBuffer chars = CharBuffer.allocate(8192);
            try (InputStream in = proc.getInputStream()) {
                CharsetDecoder decoder = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                byte[] buf = new byte[8192];
                int n;
                while (!closed && (n = in.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    decoder.decode(ByteBuffer.wrap(buf, 0, n), chars, false);
                    chars.flip();
                    if (chars.hasRemaining()) {
                        send(Map.of("op", "data", "data", chars.toString()));
                    }
                    chars.clear();
                }
            } catch (IOException ignored) {
                // closed or process died — the exit frame below covers both
            }
            // A respawn destroyed this shell: the newer pump owns the connection now.
            if (!closed && gen == generation) {
                int code = -1;
                try {
                    code = proc.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(Map.of("op", "exit", "code", code));
            }
        }

        private void send(Map<String, Object> payload) {
            try {
                ctx.send(Json.write(payload));
            } catch (Exception e) {
                LOG.warn("terminal send failed: {}", e.toString());
            }
        }
    }
}
