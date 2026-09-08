package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;
import gate.ports.store.ProviderRepository;
import gate.web.controller.LlmController;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmControllerUnitTest {

    private InMemoryProviderRepository providers;
    private MockKmsService kms;
    private MockHttpClient http;
    private LlmController controller;

    @BeforeEach
    void setUp() {
        providers = new InMemoryProviderRepository();
        kms = new MockKmsService();
        http = new MockHttpClient();
        controller = new LlmController(providers, kms, http);
    }

    @Test
    void testChatRequiresMessages() {
        Map<String, Object> ctxState = new HashMap<>();
        Context ctx = createContext("{}", ctxState);
        GateException ex = assertThrows(GateException.class, () -> controller.chat(ctx));
        assertEquals(GateErrorCode.USAGE, ex.code());
    }

    @Test
    void testChatResolvesDefaultProviderAndDecryptedKey() throws Exception {
        Map<String, Object> ctxState = new HashMap<>();
        Context ctx = createContext("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":false}", ctxState);

        Instant now = Instant.now();
        ProviderRepository.ProviderRow p1 = new ProviderRepository.ProviderRow(
                "p1", "P1", "http://test-llm.local", "kms:encrypted123", "openai", now);
        providers.upsert(p1, now);
        providers.replaceModels("p1", List.of("gpt-4o"), now);
        kms.map.put("encrypted123", "plain-key-456");

        http.responseToReturn = new MockHttpResponse<>(200, "{\"reply\":\"ok\"}");

        controller.chat(ctx);

        HttpRequest sent = http.lastRequest;
        assertEquals("http://test-llm.local/chat/completions", sent.uri().toString());
        assertEquals("Bearer plain-key-456", sent.headers().firstValue("Authorization").orElse(null));
        assertEquals(200, ctxState.get("status"));
        assertEquals("{\"reply\":\"ok\"}", ctxState.get("result"));
    }

    private static Context createContext(String body, Map<String, Object> state) {
        return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class}, (proxy, method, args) -> {
            String name = method.getName();
            if ("body".equals(name)) {
                return body;
            }
            if ("status".equals(name) && args != null && args.length == 1) {
                state.put("status", args[0]);
                return proxy;
            }
            if ("contentType".equals(name) && args != null && args.length == 1) {
                state.put("contentType", args[0]);
                return proxy;
            }
            if ("result".equals(name) && args != null && args.length == 1) {
                state.put("result", args[0]);
                return proxy;
            }
            return null;
        });
    }

    private static class InMemoryProviderRepository implements ProviderRepository {
        private final Map<String, ProviderRow> rows = new HashMap<>();
        private final Map<String, List<String>> modelsMap = new HashMap<>();

        @Override
        public Optional<ProviderRow> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ProviderRow> findAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void upsert(ProviderRow row, Instant now) {
            rows.put(row.id(), row);
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
            modelsMap.remove(id);
        }

        @Override
        public List<String> models(String providerId) {
            return modelsMap.getOrDefault(providerId, List.of());
        }

        @Override
        public void replaceModels(String providerId, List<String> models, Instant now) {
            modelsMap.put(providerId, new ArrayList<>(models));
        }
    }

    private static class MockKmsService implements KmsService {
        final Map<String, String> map = new HashMap<>();

        @Override
        public Signature sign(String canonicalJson, String keyId) { return null; }

        @Override
        public boolean verify(String canonicalJson, Signature sig) { return true; }

        @Override
        public KeyRing keyRing() { return new KeyRing("k1", Optional.empty()); }

        @Override
        public boolean isRevoked(String keyId) { return false; }

        @Override
        public String encrypt(String plaintext) {
            return "enc_" + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return map.getOrDefault(ciphertext, ciphertext);
        }
    }

    private static class MockHttpClient extends HttpClient {
        HttpRequest lastRequest;
        HttpResponse<String> responseToReturn;

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override
        public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override
        public HttpClient.Redirect followRedirects() { return HttpClient.Redirect.NEVER; }
        @Override
        public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override
        public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override
        public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.lastRequest = request;
            return (HttpResponse<T>) responseToReturn;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private static class MockHttpResponse<T> implements HttpResponse<T> {
        private final int status;
        private final T body;

        MockHttpResponse(int status, T body) {
            this.status = status;
            this.body = body;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Collections.emptyMap(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
