package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.adapters.clock.SystemClock;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.session.OpenCodeServeAdapter;
import gate.adapters.session.PortAllocator;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.PermissionRequest;
import gate.domain.session.Session;
import gate.domain.session.SessionStreamChunk;
import gate.ports.AgentSessionPort;
import gate.ports.ProviderRepository;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OpenCodeServePermissionTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcSessionRepository sessions;
    private OpenCodeServeAdapter adapter;
    private final List<String> replyPaths = new CopyOnWriteArrayList<>();
    private final List<String> replyBodies = new CopyOnWriteArrayList<>();
    private String pendingSnapshotJson = "[]";

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-perm-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-perm", "Perm Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        new JdbcTicketRepository(jdbc).insert(new gate.domain.ticket.Ticket("OPEN-P1", "t", "refs/heads/main",
                root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Files.createDirectories(root.resolve("clone"));
        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = fakeServer.getAddress().getPort();
        fakeServer.createContext("/health", this::health);
        fakeServer.createContext("/session", this::session);
        fakeServer.createContext("/permission", this::permission);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();
        PortAllocator allocator = new PortAllocator(port, port);
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, new JdbcTicketRepository(jdbc), null, null, ticketLocks,
                new SystemClock(), allocator, "", 60, null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.close();
        }
        if (fakeServer != null) {
            fakeServer.stop(0);
        }
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void asked_emits_chunk_and_auto_allow_posts_once() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-P1", "oc-perm", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();
        assertEquals("sess-1", session.cliSessionId());

        // Auto-accept on: every asked must be answered "once" by the server.
        sessions.update(session.withPermissionAutoAccept(true));

        CountDownLatch asked = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk.PermissionAskedChunk> askedChunk = new AtomicReference<>();
        CountDownLatch replied = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk.PermissionRepliedChunk> repliedChunk = new AtomicReference<>();
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.PermissionAskedChunk pa) {
                askedChunk.set(pa);
                asked.countDown();
            } else if (chunk instanceof SessionStreamChunk.PermissionRepliedChunk pr) {
                repliedChunk.set(pr);
                replied.countDown();
            }
        });

        assertTrue(asked.await(5, TimeUnit.SECONDS), "asked chunk never arrived");
        SessionStreamChunk.PermissionAskedChunk pa = askedChunk.get();
        assertNotNull(pa);
        PermissionRequest r = pa.request();
        assertEquals("perm-a", r.permissionId());
        assertEquals("bash", r.permission());
        assertEquals(List.of("src/a.ts", "src/b.ts"), r.patterns());
        assertEquals(List.of("read"), r.always());
        assertEquals("msg-9", r.messageId());
        assertEquals("call-9", r.callId());
        assertEquals(sid, pa.sessionId());

        assertTrue(replied.await(5, TimeUnit.SECONDS), "auto replied chunk never arrived");
        SessionStreamChunk.PermissionRepliedChunk pr = repliedChunk.get();
        assertEquals("perm-a", pr.permissionId());
        assertEquals("once", pr.response());
        assertTrue(pr.auto(), "auto-allow must mark the reply as automatic");

        assertTrue(replyPaths.contains("/permission/perm-a/reply"), replyPaths.toString());
        assertEquals("{\"reply\":\"once\"}", replyBodies.get(0));
        assertEquals(0, adapter.pendingPermissions(sid).size());
    }

    @Test
    void respond_permission_posts_the_chosen_reply() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-P1", "oc-perm", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        adapter.respondPermission(session.id(), "perm-a", "always");
        assertEquals("/permission/perm-a/reply", replyPaths.get(0));
        assertEquals("{\"reply\":\"always\"}", replyBodies.get(0));
    }

    @Test
    void pending_permissions_merge_the_serve_snapshot_filtered_by_session() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-P1", "oc-perm", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();
        pendingSnapshotJson = "["
                + "{\"id\":\"perm-a\",\"sessionID\":\"sess-1\",\"permission\":\"bash\","
                + "\"patterns\":[\"x\"],\"always\":[],\"metadata\":{}},"
                + "{\"id\":\"perm-other\",\"sessionID\":\"sess-9\",\"permission\":\"edit\","
                + "\"patterns\":[],\"always\":[],\"metadata\":{}}"
                + "]";
        List<PermissionRequest> pending = adapter.pendingPermissions(sid);
        assertEquals(1, pending.size());
        assertEquals("perm-a", pending.get(0).permissionId());
    }

    private void health(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void session(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void permission(HttpExchange exchange) throws java.io.IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            replyPaths.add(exchange.getRequestURI().getPath());
            replyBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "true".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else {
            byte[] body = pendingSnapshotJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private void events(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
    try {
        Thread.sleep(300);
        String asked = "{\"id\":\"evt_perm\",\"type\":\"permission.asked\",\"properties\":"
            + "{\"id\":\"perm-a\",\"sessionID\":\"sess-1\",\"permission\":\"bash\","
            + "\"patterns\":[\"src/a.ts\",\"src/b.ts\"],\"always\":[\"read\"],"
            + "\"metadata\":{\"depth\":1},\"tool\":{\"messageID\":\"msg-9\",\"callID\":\"call-9\"}}}";
        sse(os, asked);
        new CountDownLatch(1).await(15, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
        // server shutting down
    } finally {
        os.close();
    }
    }

    private static void sse(OutputStream os, String json) throws java.io.IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
