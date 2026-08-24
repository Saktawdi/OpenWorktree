package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.PreflightChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate preflight}: run the two-tier startup checks (§10.3). Any CORE failure exits 22; ENGINE
 * checks are skipped in P1 (no engine configured) and cannot fail the command.
 */
@CommandLine.Command(name = "preflight", description = "Run startup preflight checks (fail-closed)")
public final class PreflightCommand extends BaseCommand {

    @Override
    public void run() {
        GateComponents c = components();
        PreflightChecker.Report report = c.preflightChecker().check();
        List<Map<String, Object>> checks = new ArrayList<>();
        for (PreflightChecker.Check check : report.checks()) {
            checks.add(Map.of(
                    "id", check.id(),
                    "tier", check.tier().name(),
                    "passed", check.passed(),
                    "detail", check.detail()));
        }
        JsonOut.emit(System.out, "preflight", Map.of(
                "ok", report.ok(),
                "checks", checks));
        if (!report.ok()) {
            String failed = report.failures().stream()
                    .map(f -> f.id() + ": " + f.detail())
                    .reduce((a, b) -> a + " | " + b).orElse("");
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "preflight failed: " + failed);
        }
    }
}
