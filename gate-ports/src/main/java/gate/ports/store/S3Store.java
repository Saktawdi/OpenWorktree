package gate.ports.store;

import java.util.Optional;

/**
 * S3-compatible object store (production-architecture §15.3).
 * Local: FsBlobStore; team/enterprise: S3 with versioning.
 * All writes are digest-addressed and conditional.
 */
public interface S3Store {

    record PutResult(String key, String versionId, String etag, long size, String sha256) {}

    /** Put object with digest as key; returns version/etag. Must verify size+digest after. */
    PutResult put(String key, byte[] data, String expectedSha256);

    /** Conditional put: only if version/etag matches (for logical key overwrites). */
    PutResult putConditional(String key, byte[] data, String expectedSha256, String ifMatchVersion);

    /** Head: verify size/digest/version without full get. */
    Optional<PutResult> head(String key);

    /** Get bytes, verified against digest. */
    byte[] get(String key);

    /** Delete with versioning (soft). */
    void delete(String key, String versionId);

    /** GC orphan objects older than threshold with no DB reference. */
    int gcOrphans(java.time.Instant threshold);
}
