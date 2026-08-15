package gate.web;

import com.sun.net.httpserver.HttpServer;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The JDK {@link HttpServer} wrapper (执行文档-后端-web §2.4, §3.3).
 *
 * <p>Binds strictly to the configured loopback address — {@code 0.0.0.0} is already rejected in
 * {@link GateConfig.WebConfig}'s constructor, so by the time we get here {@code bind} is a loopback
 * host. The {@code /api} context carries the router + auth gate; everything else is static SPA.
 *
 * <p>A small fixed thread pool backs the server. SSE handlers (later stages) hold a thread for the
 * life of the stream, so the pool is sized generously relative to the tiny route count.
 */
final class WebServer implements AutoCloseable {

    private final HttpServer server;
    private final WebComponents components;
    private final int port;

    WebServer(WebComponents components) {
        this.components = components;
        GateConfig config = components.config();
        GateConfig.WebConfig web = config.web();
        AuthFilter authFilter = new AuthFilter(components.credentials(), web.allowedOrigins());
        ApiHandler apiHandler = new ApiHandler(components, authFilter);

        try {
            this.server = HttpServer.create(new InetSocketAddress(web.bind(), web.port()), 0);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot bind gate-web to " + web.bind() + ":" + web.port(), e);
        }
        server.createContext("/api", apiHandler);
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        this.port = server.getAddress().getPort();
    }

    void start() {
        server.start();
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(0);
        components.close();
    }
}
