package gate.web;

import gate.domain.config.GateConfig;
import gate.domain.error.GateException;
import gate.web.security.WebToken;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gate-web entry point (执行文档-后端-web §2.2): a lightweight {@code main}, NOT Spring Boot web
 * (§2.4 chose the JDK {@link com.sun.net.httpserver.HttpServer}).
 *
 * <p>Usage: {@code java -cp ... gate.web.GateWebApp [--config gate.toml] [--git <git>]}. Without
 * {@code --config} the default {@code local-run/gate.toml} is used and generated on first run
 * ({@link BootstrapConfig}). In the native-image deployment the SAME binary doubles as the MCP
 * stdio server: {@code ow mcp --config gate.toml} dispatches to {@code McpServeApp} — this is how
 * {@link GateMcpProvisioning} addresses the child when there is no JVM to rebuild a classpath
 * from. Startup sequence:
 * <ol>
 *   <li>load config + assemble the graph ({@link WebComponents#fromConfig});</li>
 *   <li>bootstrap/read the HUMAN web token ({@link WebToken#ensure}) and print it once;</li>
 *   <li>bind the loopback HTTP server and serve.</li>
 * </ol>
 *
 * <p>{@code web.bind = 0.0.0.0} is refused inside {@link GateConfig.WebConfig} — a fail-closed
 * config error that this main maps to exit 22 (§3.3).
 */
public final class GateWebApp {

    private static final Logger LOG = LoggerFactory.getLogger(GateWebApp.class);

    private GateWebApp() {
    }

    public static void main(String[] args) {
        // Native deployment: the same executable is the MCP child ("ow mcp --config ...").
        // On a regular JVM the legacy java -cp path below still applies, so this dispatch is
        // unobservable there unless someone passes "mcp" explicitly.
        if (args.length > 0 && "mcp".equals(args[0])) {
            gate.bootstrap.McpServeApp.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        String configPath = null;
        String gitExecutable = "git";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> configPath = args[++i];
                case "--git" -> gitExecutable = args[++i];
                default -> {
                    LOG.error("unknown argument: {}", args[i]);
                    System.exit(GateErrorExit.USAGE);
                }
            }
        }
        // no --config: fall back to local-run/gate.toml, generated on first run (BootstrapConfig)

        try {
            Path config = BootstrapConfig.resolve(
                    configPath == null ? null : Path.of(configPath));
            WebComponents components = WebComponents.fromConfig(config, gitExecutable);
            GateConfig.WebConfig web = components.config().web();

            String token = WebToken.ensure(web.humanTokenFile(), components.credentials(),
                    components.clock().now());

            WebServer server = new WebServer(components);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start();

            // 凭据交接：token 同时落盘 web-token 文件；这里仅日志告知（start-local 指引用户查看）。
            LOG.info("GATE_WEB_TOKEN={} (also written to {})", token, web.humanTokenFile());
            LOG.info("listening on http://{}:{}/", web.bind(), server.port());
            Thread.currentThread().join();
        } catch (GateException e) {
            LOG.error("{}: {}", e.code().name(), e.getMessage());
            System.exit(e.code().code());
        } catch (Exception e) {
            LOG.error("INTERNAL", e);
            System.exit(GateErrorExit.INTERNAL);
        }
    }

    /** Exit codes mirrored from the §8.3 table for the two cases main handles directly. */
    private static final class GateErrorExit {
        static final int USAGE = 64;
        static final int INTERNAL = 70;
    }
}
