package gate.web;

import gate.domain.config.GateConfig;
import gate.domain.error.GateException;
import java.nio.file.Path;

/**
 * gate-web entry point (执行文档-后端-web §2.2): a lightweight {@code main}, NOT Spring Boot web
 * (§2.4 chose the JDK {@link com.sun.net.httpserver.HttpServer}).
 *
 * <p>Usage: {@code java -jar gate-web.jar --config gate.toml [--git <git>]}. Startup sequence:
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

    private GateWebApp() {
    }

    public static void main(String[] args) {
        String configPath = null;
        String gitExecutable = "git";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> configPath = args[++i];
                case "--git" -> gitExecutable = args[++i];
                default -> {
                    System.err.println("gate-web: unknown argument: " + args[i]);
                    System.exit(GateErrorExit.USAGE);
                }
            }
        }
        if (configPath == null) {
            System.err.println("gate-web: --config <gate.toml> is required");
            System.exit(GateErrorExit.USAGE);
        }

        try {
            WebComponents components = WebComponents.fromConfig(Path.of(configPath), gitExecutable);
            GateConfig.WebConfig web = components.config().web();

            String token = WebToken.ensure(web.humanTokenFile(), components.credentials(),
                    components.clock().now());

            WebServer server = new WebServer(components);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start();

            System.err.println("GATE_WEB_TOKEN=" + token + "  (also written to " + web.humanTokenFile() + ")");
            System.err.println("gate-web: listening on http://" + web.bind() + ":" + server.port() + "/");
            Thread.currentThread().join();
        } catch (GateException e) {
            System.err.println("gate-web: " + e.code().name() + ": " + e.getMessage());
            System.exit(e.code().code());
        } catch (Exception e) {
            System.err.println("gate-web: INTERNAL: " + e);
            System.exit(GateErrorExit.INTERNAL);
        }
    }

    /** Exit codes mirrored from the §8.3 table for the two cases main handles directly. */
    private static final class GateErrorExit {
        static final int USAGE = 64;
        static final int INTERNAL = 70;
    }
}
