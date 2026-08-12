package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.ports.BlobStore;
import gate.ports.ReviewEngine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P1's only {@link ReviewEngine}: a human verdict, expressed through the ordinary engine contract.
 *
 * <p>This is not a special case bolted onto the policy. A manual review is a <em>degenerate engine</em>:
 * <ul>
 *   <li>a human "pass" is an {@link EngineReport} with no findings and
 *       {@code coveredPaths == changedPaths} — the human read the whole diff;</li>
 *   <li>a human "reject" is an {@link EngineReport} carrying one BLOCKER {@link Finding}, <b>not</b>
 *       an {@link EngineFailure}. Failures mean the engine malfunctioned; a considered rejection is
 *       a finding. Conflating them would corrupt the exit-code semantics (10 vs 20) that the
 *       orchestration layer uses to decide "feed findings back" versus "retry the gate".</li>
 * </ul>
 *
 * <p>The verdict itself is still derived by {@code GatePolicy}. This class only produces evidence,
 * exactly like prism will in P2 — which is the point: adding the real engine costs no core change.
 *
 * <p>The port contract says {@code review} never throws, so the body is wrapped in the mandatory
 * {@code catch (Throwable)} that converts anything unexpected into an {@code EngineFailure} value.
 */
public final class ManualReviewEngine implements ReviewEngine {

    public static final String ENGINE_ID = "manual";
    public static final String PROVIDER_ID = "manual";

    private final BlobStore blobStore;
    private final boolean pass;
    private final String note;

    public ManualReviewEngine(BlobStore blobStore, boolean pass, String note) {
        this.blobStore = blobStore;
        this.pass = pass;
        this.note = note;
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(ENGINE_ID, "p1", "manual-verdict", PROVIDER_ID, "human");
    }

    @Override
    public ReviewEvidence review(ReviewRequest request) {
        try {
            String raw = "manual verdict: " + (pass ? "PASS" : "REJECT")
                    + "\nticket=" + request.ticketNo()
                    + "\nround=" + request.reviewRound()
                    + "\ntree=" + request.snapshot().treeHash().hex()
                    + "\ncommit=" + request.danglingCommit().hex()
                    + "\nnote=" + (note == null ? "" : note)
                    + "\n";
            BlobRef rawRef = blobStore.put(raw.getBytes(StandardCharsets.UTF_8),
                    "raw/" + request.ticketNo() + "/" + request.reviewRound() + "/manual.txt");

            // A human reviews the diff as a whole, so coverage is the full changed set by definition.
            Set<String> covered = new LinkedHashSet<>(request.snapshot().changedPaths());

            List<Finding> findings = pass
                    ? List.of()
                    : List.of(new Finding(Severity.BLOCKER, "manual-reject", firstPath(request), null, null,
                            "manual/blocker", note == null ? "rejected by human reviewer" : note, null));

            return new EngineReport(
                    describe(),
                    request.snapshot().treeHash().hex(),
                    findings,
                    covered,
                    false,
                    rawRef,
                    pass ? 0 : 1,
                    Duration.ZERO);
        } catch (Throwable t) {
            // The one place a catch-all is mandatory: the port promises not to throw, and every
            // failure must become a value that GatePolicy turns into a reject.
            return new EngineFailure(describe(), EngineFailure.FailureKind.CRASH,
                    "manual review adapter failed: " + t, -1);
        }
    }

    /** A finding needs a path; attribute a whole-diff rejection to the first changed file. */
    private static String firstPath(ReviewRequest request) {
        List<String> changed = request.snapshot().changedPaths();
        return changed.isEmpty() ? "." : changed.get(0);
    }
}
