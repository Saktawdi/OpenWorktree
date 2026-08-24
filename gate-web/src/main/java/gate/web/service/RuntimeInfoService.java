package gate.web.service;

import gate.adapters.process.CliLocator;
import gate.application.GateService;
import gate.application.StatusQuery;
import gate.application.StatusResult;
import gate.domain.config.GateConfig;
import gate.domain.session.SessionStatus;
import gate.domain.task.GateTaskStatus;
import gate.ports.ProcessRunner;
import gate.ports.SessionRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real runtime-environment snapshot for the settings page's 服务状态 panel (V5 web console) — the
 * page previously showed only the static {@code /api/config} view, which says nothing about the
 * machine the gate actually runs on.
 *
 * <p>Everything here is read-only probing: git / engine / agent-CLI versions via {@link
 * ProcessRunner}, JVM + OS from system properties, live counters from the repositories. CLI probes
 * are cached for {@link #PROBE_TTL} so repeated UI refreshes don't spawn a process storm; counters
 * and uptime are always fresh. Probing never throws — an unavailable binary reports {@code
 * available:false} rather than failing the endpoint.
 */
final public class RuntimeInfoService {

    private static final Duration PROBE_TTL = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration MODEL_PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final List<String> CLAUDE_MODEL_HINTS = List.of("default", "sonnet", "opus", "haiku");
    /** cc-switch's version pattern: a plain semver with an optional prerelease suffix. */
    private static final Pattern VERSION_RE = Pattern.compile("\\d+\\.\\d+\\.\\d+(-[\\w.]+)?");

    private final GateConfig config;
    private final ProcessRunner processRunner;
    private final CliLocator cliLocator;
    private final GateService gateService;
    private final TicketRepository tickets;
    private final SessionRepository sessions;
    private final TaskRegistry tasks;
    private final Instant startedAt;
    private final String gitExecutable;
    private final Clock clock;

    /** Minimal clock seam so tests can age the probe cache deterministically. */
    public interface Clock {
        Instant now();
    }

    /**
     * One CLI probe outcome. {@code note} distinguishes "not installed" from "installed but
     * {@code --version} itself fails" (e.g. a Node version the CLI rejects): it carries the
     * stderr tail so the UI can label the entry 已安装·无法运行 instead of 未安装.
     */
    private record Probe(Instant at, boolean available, String version, String note,
                         List<String> models, String modelSource) {
    }

    private record ProbeCache(Instant at, Probe git, Probe engine, Probe claude, Probe opencode) {
    }

    private volatile ProbeCache cache;
    private volatile ModelCatalog opencodeModelCatalog = ModelCatalog.loading();
    private final AtomicBoolean opencodeModelProbeRunning = new AtomicBoolean();

    public RuntimeInfoService(GateConfig config, String gitExecutable, ProcessRunner processRunner,
                       CliLocator cliLocator, GateService gateService,
                       TicketRepository tickets, SessionRepository sessions,
                       TaskRegistry tasks, Instant startedAt, Clock clock) {
        this.config = config;
        this.processRunner = processRunner;
        this.cliLocator = cliLocator;
        this.gateService = gateService;
        this.tickets = tickets;
        this.sessions = sessions;
        this.tasks = tasks;
        this.startedAt = startedAt;
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable;
        this.clock = clock;
    }

    /** Builds the {@code GET /api/runtime} JSON body. */
    public Map<String, Object> snapshot() {
        ProbeCache pc = probeAll();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "gate-web");
        body.put("started_at", startedAt.toString());
        body.put("uptime_seconds", Math.max(0, Duration.between(startedAt, clock.now()).toSeconds()));

        Map<String, Object> java = new LinkedHashMap<>();
        java.put("version", System.getProperty("java.version"));
        java.put("vendor", System.getProperty("java.vendor"));
        body.put("java", java);

        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("arch", System.getProperty("os.arch"));
        os.put("version", System.getProperty("os.version"));
        body.put("os", os);

        body.put("git", probeJson(pc.git(), gitExecutable));
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("configured", config.engineConfigured());
        if (config.engineConfigured()) {
            engine.put("cmd", config.engine().cmd());
            engine.put("available", pc.engine().available());
            engine.put("version", pc.engine().version());
        } else {
            engine.put("cmd", null);
            engine.put("available", false);
            engine.put("version", null);
        }
        body.put("engine", engine);

        List<Map<String, Object>> agentClis = new ArrayList<>();
        agentClis.add(probeJson(pc.claude(), "claude"));
        agentClis.add(probeJson(pc.opencode(), "opencode"));
        body.put("agent_clis", agentClis);

        Map<String, Object> database = new LinkedHashMap<>();
        database.put("path", config.dbPath().toString());
        body.put("database", database);

        if (config.webConfigured()) {
            Map<String, Object> web = new LinkedHashMap<>();
            web.put("bind", config.web().bind());
            web.put("port", config.web().port());
            body.put("web", web);
        }

        body.put("gate_home", config.gateHome().toString());
        body.put("auth_repo", config.authRepo().toString());

        // Reuses the same read-only status projection the MCP status tool serves.
        StatusResult status = gateService.status(new StatusQuery(null));
        body.put("auth_tip", status.authTip());
        body.put("auth_commit_count", status.authCommitCount());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("tickets", tickets.findAll().size());
        counts.put("active_sessions", sessions.findByStatus(SessionStatus.ACTIVE).size());
        counts.put("running_tasks", tasks.countByStatus(GateTaskStatus.RUNNING.name()));
        body.put("counts", counts);

        Map<String, Object> ports = new LinkedHashMap<>();
        ports.put("min", config.session() == null ? 49152 : config.session().portRangeMin());
        ports.put("max", config.session() == null ? 65535 : config.session().portRangeMax());
        body.put("session_port_range", ports);
        return body;
    }

    /**
     * Returns the CLI catalog used by the agent settings page. Version probing is deliberately
     * separate from this route because model discovery can invoke a provider-aware CLI command.
     * OpenCode exposes its catalog through {@code opencode models}; Claude Code has no equivalent
     * list command, so its stable aliases are presented as hints while an empty selection means
     * "use the CLI default".
     */
    public Map<String, Object> agentRuntimes() {
        // Version availability must not wait for a provider-aware model catalog. The latter can
        // trigger network/cache work inside OpenCode and is refreshed asynchronously below.
        ProbeCache pc = probeAll();
        ModelCatalog claudeCatalog = new ModelCatalog(CLAUDE_MODEL_HINTS, "cli-hints");
        ModelCatalog opencodeCatalog = pc.opencode().available()
                ? opencodeModelCatalog
                : ModelCatalog.unavailable();
        if (pc.opencode().available() && "cli-loading".equals(opencodeCatalog.source())) {
            scheduleOpenCodeModelProbe();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(probeJson(withCatalog(pc.claude(), claudeCatalog), "claude"));
        rows.add(probeJson(withCatalog(pc.opencode(), opencodeCatalog), "opencode"));
        body.put("agent_runtimes", rows);
        return body;
    }

    private void scheduleOpenCodeModelProbe() {
        if (!opencodeModelProbeRunning.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Path resolved = cliLocator.locate("opencode").orElse(null);
                String command = resolved == null ? "opencode" : resolved.toString();
                opencodeModelCatalog = discoverModels("opencode", command);
            } finally {
                opencodeModelProbeRunning.set(false);
            }
        });
    }

    private ProbeCache probeAll() {
        ProbeCache pc = cache;
        if (pc != null && Duration.between(pc.at(), clock.now()).compareTo(PROBE_TTL) < 0) {
            return pc;
        }
        String engineCmd = config.engineConfigured() ? config.engine().cmd() : null;
        pc = new ProbeCache(clock.now(),
                probe(gitExecutable, "--version", false),
                engineCmd == null ? unavailableProbe() : probe(engineCmd, "version", false),
                probe("claude", "--version", false),
                probe("opencode", "--version", false));
        cache = pc;
        return pc;
    }

    /**
     * Runs {@code executable <versionFlag>} to capture a version string. The bare name is first
     * resolved through {@link CliLocator} — on Windows npm installs {@code claude.cmd} shims that
     * {@code ProcessBuilder} cannot find by bare name, which is what made every agent CLI show up
     * as 未安装 before. When the resolved file exists but {@code --version} fails, the probe keeps
     * {@code available:false} <em>and</em> records a diagnostic note rather than pretending the
     * CLI is absent (cc-switch's installed-but-broken distinction).
     */
    private Probe probe(String executable, String versionFlag, boolean includeModels) {
        Path resolved = cliLocator.locate(executable).orElse(null);
        String command = resolved == null ? executable : resolved.toString();
        try {
            ProcessRunner.ProcRun run = processRunner.run(
                    List.of(command, versionFlag), null, Map.of(), PROBE_TIMEOUT);
            if (run.ok()) {
                String line = run.stdout().lines().map(String::trim).filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElseGet(() -> run.stderr().lines().map(String::trim)
                                .filter(s -> !s.isEmpty()).findFirst().orElse(""));
                if (line.isEmpty()) {
                    ModelCatalog catalog = includeModels ? discoverModels(executable, command)
                            : ModelCatalog.empty();
                    return new Probe(Instant.EPOCH, true, null, null, catalog.models(), catalog.source());
                }
                Matcher m = VERSION_RE.matcher(line);
                ModelCatalog catalog = includeModels ? discoverModels(executable, command)
                        : ModelCatalog.empty();
                return new Probe(Instant.EPOCH, true, m.find() ? m.group() : line, null,
                        catalog.models(), catalog.source());
            }
            String note = null;
            if (resolved != null) {
                note = run.timedOut() ? "探测超时"
                        : tailLines(run.stderr().isBlank() ? run.stdout() : run.stderr(), 2);
            }
            return new Probe(Instant.EPOCH, false, null, note, List.of(), "unavailable");
        } catch (RuntimeException ignored) {
            // unavailable binary — reported as available:false, never an endpoint failure.
            return new Probe(Instant.EPOCH, false, null, null, List.of(), "unavailable");
        }
    }

    private static Probe unavailableProbe() {
        return new Probe(Instant.EPOCH, false, null, null, List.of(), "unavailable");
    }

    private ModelCatalog discoverModels(String executable, String command) {
        String normalized = executable.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("claude")) {
            return new ModelCatalog(CLAUDE_MODEL_HINTS, "cli-hints");
        }
        if (!normalized.contains("opencode")) {
            return ModelCatalog.empty();
        }
        try {
            ProcessRunner.ProcRun run = processRunner.run(
                    List.of(command, "models"), null, Map.of(), MODEL_PROBE_TIMEOUT);
            if (run.ok()) {
                List<String> models = run.stdout().lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .distinct()
                        .limit(200)
                        .toList();
                if (!models.isEmpty()) {
                    return new ModelCatalog(models, "cli");
                }
            }
        } catch (RuntimeException ignored) {
            // A model catalog failure should not make an otherwise installed CLI unavailable.
        }
        return new ModelCatalog(List.of("default"), "cli-default");
    }

    private record ModelCatalog(List<String> models, String source) {
        private static ModelCatalog empty() {
            return new ModelCatalog(List.of(), "none");
        }

        private static ModelCatalog loading() {
            return new ModelCatalog(List.of("default"), "cli-loading");
        }

        private static ModelCatalog unavailable() {
            return new ModelCatalog(List.of(), "unavailable");
        }
    }

    private static Probe withCatalog(Probe base, ModelCatalog catalog) {
        return new Probe(base.at(), base.available(), base.version(), base.note(),
                catalog.models(), catalog.source());
    }

    private static String tailLines(String text, int maxLines) {
        List<String> lines = text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
    }

    private static Map<String, Object> probeJson(Probe p, String executable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", executable);
        m.put("available", p.available());
        m.put("version", p.version());
        if (p.note() != null) {
            m.put("note", p.note());
        }
        m.put("models", p.models());
        m.put("model_source", p.modelSource());
        return m;
    }
}
