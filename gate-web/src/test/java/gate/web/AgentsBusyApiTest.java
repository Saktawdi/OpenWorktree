package gate.web;

import static org.junit.jupiter.api.Assertions.*;

import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Session;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.ports.session.AgentSessionPort;
import gate.ports.infra.Clock;
import gate.ports.store.SessionRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * GET /api/agents/busy 契约：count 与 running 数组，元素含 session_id/title/ticket_no/cli。
 * 复用 WebHarness + 内存 FakeAgentSessionPort + 真实 SessionRepository（SQLite）。
 */
@Tag("slow")
class AgentsBusyApiTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private FakeBusyPort fake;

    @BeforeEach
    void setUp() {
        fake = new FakeBusyPort();
        harness = new WebHarness("git", "127.0.0.1", fake);
        fake.bind(harness.components().sessionRepository(), harness.components().clock());
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        if (harness != null) harness.close();
    }

    @Test
    void busy_empty_returns_zero() throws Exception {
        HttpResponse<String> resp = get("/api/agents/busy");
        assertEquals(200, resp.statusCode(), resp.body());
        Map<String, Object> body = parse(resp.body());
        assertEquals(0, ((Number) body.get("count")).intValue());
        List<?> running = (List<?>) body.get("running");
        assertNotNull(running);
        assertTrue(running.isEmpty());
    }

    @Test
    void busy_sorted_and_unknown_session_has_null_fields() throws Exception {
        // 准备两个真实会话
        harness.components().agentConfigRepository().insert(
                new AgentConfig("busy-cfg", "Busy", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        String ticketNo = "BUSY-T1";
        // 先创建 ticket 行以满足可能的 FK（JdbcTicketRepository 插入）
        harness.components().ticketRepository().insert(new gate.domain.ticket.Ticket(ticketNo, "t", "refs/heads/main",
                harness.root().resolve("clone-busy").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, Instant.now(), Instant.now()));
        Session s1 = new Session("sess-b", ticketNo, "busy-cfg", AgentCli.CLAUDE, SessionStatus.ACTIVE, null,
                harness.root().resolve("clone-busy").toString(), -1, Instant.now(), null, SessionUsage.EMPTY, "标题B", false);
        Session s2 = new Session("sess-a", ticketNo, "busy-cfg", AgentCli.OPENCODE, SessionStatus.ACTIVE, null,
                harness.root().resolve("clone-busy").toString(), 1234, Instant.now(), null, SessionUsage.EMPTY, null, false);
        harness.components().sessionRepository().insert(s1);
        harness.components().sessionRepository().insert(s2);

        // busy 集合含乱序 + 一个幽灵 id（不在 repository 中）
        fake.busyIds.clear();
        fake.busyIds.addAll(Set.of("sess-b", "ghost-1", "sess-a"));

        HttpResponse<String> resp = get("/api/agents/busy");
        assertEquals(200, resp.statusCode(), resp.body());
        Map<String, Object> body = parse(resp.body());
        assertEquals(3, ((Number) body.get("count")).intValue());
        List<Map<String, Object>> running = castList(body.get("running"));
        // 必须排序
        assertEquals(List.of("ghost-1", "sess-a", "sess-b"), running.stream().map(m -> String.valueOf(m.get("session_id"))).toList());
        // 幽灵条目其余字段为 null
        Map<String, Object> ghost = running.get(0);
        assertEquals("ghost-1", ghost.get("session_id"));
        assertNull(ghost.get("title"));
        assertNull(ghost.get("ticket_no"));
        assertNull(ghost.get("cli"));
        // 已知会话字段正确
        Map<String, Object> a = running.get(1);
        assertEquals("sess-a", a.get("session_id"));
        assertNull(a.get("title"));
        assertEquals(ticketNo, a.get("ticket_no"));
        assertEquals("OPENCODE", a.get("cli"));
        Map<String, Object> b = running.get(2);
        assertEquals("sess-b", b.get("session_id"));
        assertEquals("标题B", b.get("title"));
        assertEquals(ticketNo, b.get("ticket_no"));
        assertEquals("CLAUDE", b.get("cli"));
    }

    @Test
    void busy_requires_auth() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/agents/busy")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        return (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : (List<?>) o) out.add((Map<String, Object>) e);
        return out;
    }

    static final class FakeBusyPort implements AgentSessionPort {
        SessionRepository sessions;
        Clock clock;
        final Set<String> busyIds = ConcurrentHashMap.newKeySet();
        void bind(SessionRepository s, Clock c) { this.sessions = s; this.clock = c; }
        @Override public Session start(StartRequest r) {
            String id = UUID.randomUUID().toString();
            Session s = new Session(id, r.ticketNo(), r.agentConfigId(), AgentCli.CLAUDE, SessionStatus.ACTIVE, "cli-"+id, r.clonePath(), -1, clock.now(), null, SessionUsage.EMPTY, null, false);
            sessions.insert(s); return s;
        }
        @Override public String sendMessage(SendRequest r) { return "t"; }
        @Override public void abort(String id) {}
        @Override public java.util.List<gate.domain.session.SessionMessage> getHistory(String id) { return sessions.findMessages(id); }
        @Override public Stream<SessionEvent> streamEvents(String id) { return Stream.empty(); }
        @Override public AutoCloseable attachListener(String id, java.util.function.Consumer<gate.domain.session.SessionStreamChunk> l) { return ()->{}; }
        @Override public void respondPermission(String a, String b, String c) {}
        @Override public java.util.List<gate.domain.session.PermissionRequest> pendingPermissions(String id) { return List.of(); }
        @Override public Set<String> busySessionIds() {
            List<String> sorted = new ArrayList<>(busyIds);
            java.util.Collections.sort(sorted);
            return new LinkedHashSet<>(sorted);
        }
    }
}
