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
    // Phase 3 binding: ref, old OID, expiry, nonce (ADR-003)
    private final String targetRef;
    private final ObjectId baseCommit;
    private final java.time.Instant issuedAt;
    private final java.time.Instant expiresAt;
    private final String nonce;

    private PublishAuthorization(String ticketNo, int reviewRound, ObjectId treeHash, EngineDescriptor engine) {
        this(ticketNo, reviewRound, treeHash, engine, null, null,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600),
                java.util.UUID.randomUUID().toString());
    }

    private PublishAuthorization(String ticketNo, int reviewRound, ObjectId treeHash, EngineDescriptor engine,
                                 String targetRef, ObjectId baseCommit,
                                 java.time.Instant issuedAt, java.time.Instant expiresAt, String nonce) {
        this.ticketNo = ticketNo;
        this.reviewRound = reviewRound;
        this.treeHash = treeHash;
        this.engine = engine;
        this.targetRef = targetRef;
        this.baseCommit = baseCommit;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.nonce = nonce;
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

    /** Bound mint: binds ref, old OID, expiry and nonce (ADR-003 §7.1). */
    static PublishAuthorization mint(String ticketNo, int reviewRound, ObjectId treeHash, EngineDescriptor engine,
                                     String targetRef, ObjectId baseCommit,
                                     java.time.Instant issuedAt, java.time.Instant expiresAt, String nonce) {
        if (ticketNo == null || ticketNo.isBlank()) throw new IllegalArgumentException("ticketNo must not be blank");
        if (reviewRound < 1) throw new IllegalArgumentException("reviewRound must be >= 1");
        if (treeHash == null) throw new IllegalArgumentException("treeHash must not be null");
        if (engine == null) throw new IllegalArgumentException("engine must not be null");
        if (targetRef == null || targetRef.isBlank()) throw new IllegalArgumentException("targetRef must not be blank");
        if (baseCommit == null) throw new IllegalArgumentException("baseCommit must not be null");
        if (issuedAt == null || expiresAt == null) throw new IllegalArgumentException("issuedAt/expiresAt must not be null");
        if (nonce == null || nonce.isBlank()) throw new IllegalArgumentException("nonce must not be blank");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be after issuedAt");
        return new PublishAuthorization(ticketNo, reviewRound, treeHash, engine, targetRef, baseCommit, issuedAt, expiresAt, nonce);
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

    public String targetRef() { return targetRef; }
    public ObjectId baseCommit() { return baseCommit; }
    public java.time.Instant issuedAt() { return issuedAt; }
    public java.time.Instant expiresAt() { return expiresAt; }
    public String nonce() { return nonce; }

    /**
     * Guards against presenting an authorization for a different snapshot than the one being
     * published. Callers on the publish path must invoke this before doing anything irreversible.
     */
    public boolean authorises(String ticket, int round, ObjectId tree) {
        return ticketNo.equals(ticket) && reviewRound == round && treeHash.equals(tree);
    }

    /** Full binding check including ref and old OID when present (ADR-003). */
    public boolean authorisesFull(String ticket, int round, ObjectId tree, String ref, ObjectId oldOid) {
        if (!authorises(ticket, round, tree)) return false;
        if (targetRef != null && !targetRef.equals(ref)) return false;
        if (baseCommit != null && oldOid != null && !baseCommit.equals(oldOid)) return false;
        return true;
    }

    public boolean isExpired(java.time.Instant now) {
        return now != null && expiresAt != null && now.isAfter(expiresAt);
    }

    @Override
    public String toString() {
        return "PublishAuthorization[" + ticketNo + "/" + reviewRound + "/" + treeHash.hex()
                + (targetRef == null ? "" : "/" + targetRef + "/" + (baseCommit == null ? "null" : baseCommit.hex()))
                + "/nonce=" + nonce + "]";
    }
}
