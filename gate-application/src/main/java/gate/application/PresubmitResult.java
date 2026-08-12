package gate.application;

import gate.domain.snapshot.CaptureIntegrityReport;
import java.util.List;

/**
 * Outcome of a presubmit.
 *
 * @param reviewRound the round actually allocated. An empty diff or a blocked capture allocates
 *                    nothing — the round counter must not be consumed (§3.2).
 */
public record PresubmitResult(
        String ticketNo,
        int reviewRound,
        String treeHash,
        String baseCommit,
        String targetRef,
        long diffBytes,
        List<String> changedPaths,
        CaptureIntegrityReport integrity) {

    public PresubmitResult {
        changedPaths = List.copyOf(changedPaths);
    }
}
