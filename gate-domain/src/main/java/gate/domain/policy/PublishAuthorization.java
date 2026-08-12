package gate.domain.policy;

import gate.domain.git.ObjectId;
import gate.domain.review.EngineDescriptor;

/**
 * Capability token proving that {@link GatePolicy} allowed this exact snapshot to be published
 * (架构落地执行文档 §8.2, ADR-7).
 *
 * <p>There is no public constructor and no public factory. The only way to obtain one is
 * {@link #mint} — package-private, called solely by {@link GatePolicy}. Since
 * {@code CommitPublisher.publish} requires one as a parameter, "publishing without a decision"
 * is not expressible in the type system rather than merely discouraged in review.
 *
 * <p>It binds {@code (ticketNo, reviewRound, treeHash)} so an authorization minted for one
 * snapshot cannot be presented for another — the in-process analogue of the on-disk approval
 * record's four-tuple binding (§6.2).
 */
public final class PublishAuthorization {

    private final String ticketNo;
    private final int reviewRound;
    private final ObjectId treeHash;
    private final EngineDescriptor engine;

    private PublishAuthorization(String ticketNo, int reviewRound, ObjectId treeHash, EngineDescriptor engine) {
        this.ticketNo = ticketNo;
        this.reviewRound = reviewRound;
        this.treeHash = treeHash;
        this.engine = engine;
    }

    /** Package-private on purpose: only {@link GatePolicy} may mint authority. */
    static PublishAuthorization mint(String ticketNo, int reviewRound, ObjectId treeHash, EngineDescriptor engine) {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (reviewRound < 1) {
            throw new IllegalArgumentException("reviewRound must be >= 1");
        }
        if (treeHash == null) {
            throw new IllegalArgumentException("treeHash must not be null");
        }
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        return new PublishAuthorization(ticketNo, reviewRound, treeHash, engine);
    }

    public String ticketNo() {
        return ticketNo;
    }

    public int reviewRound() {
        return reviewRound;
    }

    public ObjectId treeHash() {
        return treeHash;
    }

    public EngineDescriptor engine() {
        return engine;
    }

    /**
     * Guards against presenting an authorization for a different snapshot than the one being
     * published. Callers on the publish path must invoke this before doing anything irreversible.
     */
    public boolean authorises(String ticket, int round, ObjectId tree) {
        return ticketNo.equals(ticket) && reviewRound == round && treeHash.equals(tree);
    }

    @Override
    public String toString() {
        return "PublishAuthorization[" + ticketNo + "/" + reviewRound + "/" + treeHash.hex() + "]";
    }
}
