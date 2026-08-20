package gate.ports.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * WORM audit archive with KMS checkpoint (ADR-007, production-architecture §12.2).
 * Local: file + HMAC; enterprise: S3 Object Lock / WORM with KMS signature.
 */
public interface AuditArchivePort {

    record Checkpoint(String checkpointId, String prevCheckpointHash, String rootHash,
                      String kmsKeyId, String signature, Instant createdAt, long eventCount) {}

    /** Append audit event hash-chain and return current chain head. */
    String appendAndGetHead(String chainHashLine);

    /** Create a KMS-signed checkpoint covering events since last checkpoint. */
    Checkpoint createCheckpoint(String kmsKeyId);

    /** Verify whole chain and all checkpoints. */
    boolean verifyChain();

    /** List checkpoints. */
    List<Checkpoint> listCheckpoints();

    /** Find checkpoint by id. */
    Optional<Checkpoint> findCheckpoint(String checkpointId);

    /** Detect tampering: return null if intact, else description. */
    String detectTampering();
}
