package gate.cli;

import gate.cli.util.GateExceptionHandler;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

/**
 * Entry point (架构落地执行文档 §4.4).
 *
 * <p>Spring Boot runs with {@code WebApplicationType.NONE} and the banner off — it is a DI/lifecycle
 * shell, nothing more. The actual object graph is assembled by {@link GateComponents} with plain
 * constructor injection, which is why the domain and application layers never see a Spring
 * annotation and the DI choice stays reversible.
 *
 * <p>There is exactly one {@code System.exit} in the whole program, here, and it uses the exit code
 * picocli computes via {@link GateExceptionHandler} (the §8.3 table). stdout is the JSON channel and
 * each command flushes it before returning, so the exit cannot truncate machine output.
 */
@SpringBootApplication
public class GateApp {

    public static void main(String[] args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(GateApp.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false);
        int exitCode;
        try (ConfigurableApplicationContext context = builder.run()) {
            CommandLine cmd = new CommandLine(new RootCommand())
                    .setExecutionExceptionHandler(new GateExceptionHandler())
                    .setCaseInsensitiveEnumValuesAllowed(true);
            exitCode = cmd.execute(args);
        }
        System.out.flush();
        System.exit(exitCode);
    }
}
