package gate.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import gate.ports.store.ProviderRepository;
import gate.web.service.ProviderModelFetcher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 设置中心"拉取上游模型"的 TLS 放宽回归（TrustAllTls）：上游用<strong>自签证书且 CN 与主机名
 * 不匹配</strong>的 HTTPS 服务模拟 newapi 中转/本地代理的真实形态——默认 JDK 信任库下该调用
 * 必然以 PKIX path building failed / 主机名校验失败拒绝；放宽后必须原样拉回模型列表。
 */
class ProviderModelFetcherTlsTest {

    @TempDir
    Path dir;

    @Test
    void fetchTrustsSelfSignedCertificateAndMismatchedHostname() throws Exception {
        Path keystore = dir.resolve("keystore.p12");
        generateSelfSignedKey(keystore);

        HttpsServer server = selfSignedModelsServer(keystore);
        server.start();
        try {
            ProviderModelFetcher fetcher = new ProviderModelFetcher(dir.resolve(".env"));
            List<String> models = fetcher.fetch(new ProviderRepository.ProviderRow(
                    "selfsigned", "Self-signed upstream",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "none", "openai", Instant.EPOCH));
            assertEquals(List.of("tls-model"), models);
        } finally {
            server.stop(0);
        }
    }

    /** keytool 生成自签密钥（CN 故意不是 127.0.0.1，主机名校验路径也一并覆盖）。 */
    private void generateSelfSignedKey(Path keystore) throws IOException, InterruptedException {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", exe);
        Process p = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12",
                "-dname", "CN=not-the-localhost", "-validity", "1").inheritIO().start();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed, exit=" + p.exitValue());
        }
    }

    private HttpsServer selfSignedModelsServer(Path keystore) throws Exception {
        char[] pass = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance(keystore.toFile(), pass);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"tls-model\"}]}".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
