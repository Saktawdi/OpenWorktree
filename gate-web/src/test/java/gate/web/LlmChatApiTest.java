package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gate.ports.store.ProviderRepository;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("slow")
class LlmChatApiTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private HttpServer mockUpstream;
    private int mockPort;

    @BeforeEach
    void setUp() throws Exception {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        mockPort = mockUpstream.getAddress().getPort();
        mockUpstream.createContext("/chat/completions", exchange -> {
            try {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (!"Bearer mock-decrypted-key".equals(auth)) {
                    byte[] err = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(401, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                    exchange.close();
                    return;
                }

                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                String reqStr = new String(requestBytes, StandardCharsets.UTF_8);

                byte[] resp = "{\"id\":\"chat-123\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Mock reply\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                    os.flush();
                }
                exchange.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        mockUpstream.start();

        harness = new WebHarness();
        // Insert mock provider with KMS encrypted key
        Instant now = Instant.now();
        String encryptedKey = "kms:" + harness.components().kmsService().encrypt("mock-decrypted-key");
        harness.components().providerRepository().upsert(
                new ProviderRepository.ProviderRow("mock-p1", "Mock Provider", "http://127.0.0.1:" + mockPort, encryptedKey, "openai", now),
                now);
        harness.components().providerRepository().replaceModels("mock-p1", java.util.List.of("mock-gpt-4o"), now);

        server = new WebServer(harness.components());
        server.start();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
        client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void testChatRequiresAuth() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/llm/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    @Test
    void testChatBufferedSuccess() throws Exception {
        String payload = """
                {
                  "provider_id": "mock-p1",
                  "model": "mock-gpt-4o",
                  "messages": [{"role": "user", "content": "hi"}],
                  "stream": false
                }
                """;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/llm/chat"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Mock reply"));
    }
}
