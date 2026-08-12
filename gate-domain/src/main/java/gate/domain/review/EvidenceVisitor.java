package gate.domain.review;

/** Consumer of the sealed {@link ReviewEvidence} hierarchy. See {@link ReviewEvidence} for why. */
public interface EvidenceVisitor<T> {

    T visit(EngineReport report);

    T visit(EngineFailure failure);
}
