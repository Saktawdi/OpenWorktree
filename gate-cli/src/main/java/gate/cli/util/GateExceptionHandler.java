package gate.cli.util;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.PrintWriter;
import picocli.CommandLine;

/**
 * Maps any exception to the §8.3 exit-code table at the single {@code System.exit} site.
 *
 * <p>A {@link GateException} already carries its code. Picocli's own usage error is 64
 * (EX_USAGE). Everything else is 70 (EX_SOFTWARE) — an unclassified failure is a bug, not a
 * silent success. Human-readable text goes to stderr; stdout is reserved for the JSON channel.
 */
public final class GateExceptionHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, CommandLine.ParseResult parseResult) {
        PrintWriter err = cmd.getErr();
        int code;
        if (ex instanceof GateException gateEx) {
            code = gateEx.code().code();
            err.println("gate: " + gateEx.code().name() + ": " + gateEx.getMessage());
        } else {
            code = GateErrorCode.INTERNAL.code();
            err.println("gate: INTERNAL: " + ex);
            ex.printStackTrace(err);
        }
        err.flush();
        return code;
    }
}
