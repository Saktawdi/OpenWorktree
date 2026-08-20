package gate.ports;

import java.util.Optional;

/**
 * KMS / Secret provider (production-architecture §7.1, ADR-003).
 * Local: HMAC; enterprise: asymmetric KMS.
 */
public interface KmsService {

    /** Sign canonical JSON payload (JCS, schema version, object format). Returns signature + keyId. */
    record Signature(String keyId, String algorithm, String signature, long issuedAt, long expiresAt) {}

    Signature sign(String canonicalJson, String keyId);

    boolean verify(String canonicalJson, Signature sig);

    /** Returns current key id, and previous key id if within grace period (rotation). */
    record KeyRing(String currentKeyId, Optional<String> previousKeyId) {}
    KeyRing keyRing();

    /** Check if key is revoked (unconsumed authorizations must fail). */
    boolean isRevoked(String keyId);

    /** Encrypt secret for short-lived injection (never in argv/logs). */
    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
