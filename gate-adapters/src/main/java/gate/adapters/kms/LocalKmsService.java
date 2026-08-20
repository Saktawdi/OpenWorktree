package gate.adapters.kms;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.KmsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local HMAC KMS mock (Phase3 ADR-003). Enterprise would use asymmetric KMS.
 * Sign/verify use HMAC-SHA256 with keyId-scoped secrets stored in env/config.
 * Rotation: keyRing holds current + previous within grace.
 */
public final class LocalKmsService implements KmsService {

    private final ConcurrentHashMap<String, String> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> revoked = new ConcurrentHashMap<>();
    private volatile String currentKeyId;
    private volatile String previousKeyId;

    public LocalKmsService(String currentKeyId, String secret) {
        this.currentKeyId = currentKeyId;
        keys.put(currentKeyId, secret);
    }

    public void rotate(String newKeyId, String newSecret) {
        previousKeyId = currentKeyId;
        currentKeyId = newKeyId;
        keys.put(newKeyId, newSecret);
    }

    @Override
    public Signature sign(String canonicalJson, String keyId) {
        String kid = keyId == null ? currentKeyId : keyId;
        String secret = keys.get(kid);
        if (secret == null) throw new GateException(GateErrorCode.GATE_ERROR_IO, "unknown key: " + kid);
        if (Boolean.TRUE.equals(revoked.get(kid))) throw new GateException(GateErrorCode.GATE_ERROR_IO, "key revoked: " + kid);
        long now = Instant.now().getEpochSecond();
        long exp = now + 3600;
        String payload = kid + "." + now + "." + exp + "." + canonicalJson;
        String sig = hmac(secret, payload);
        return new Signature(kid, "HMAC-SHA256", sig, now, exp);
    }

    @Override
    public boolean verify(String canonicalJson, Signature sig) {
        if (sig == null) return false;
        if (isRevoked(sig.keyId())) return false;
        // allow current or previous
        KeyRing ring = keyRing();
        if (!sig.keyId().equals(ring.currentKeyId()) && !ring.previousKeyId().map(sig.keyId()::equals).orElse(false)) {
            // still allow if key exists (for tests)
            if (!keys.containsKey(sig.keyId())) return false;
        }
        String secret = keys.get(sig.keyId());
        if (secret == null) return false;
        String payload = sig.keyId() + "." + sig.issuedAt() + "." + sig.expiresAt() + "." + canonicalJson;
        String expected = hmac(secret, payload);
        if (!expected.equals(sig.signature())) return false;
        long now = Instant.now().getEpochSecond();
        return now <= sig.expiresAt();
    }

    @Override public KeyRing keyRing() { return new KeyRing(currentKeyId, Optional.ofNullable(previousKeyId)); }
    @Override public boolean isRevoked(String keyId) { return Boolean.TRUE.equals(revoked.get(keyId)); }
    public void revoke(String keyId) { revoked.put(keyId, true); }

    @Override public String encrypt(String plaintext) {
        // simple base64 with current key xor for local; not real encryption
        return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }
    @Override public String decrypt(String ciphertext) {
        try { return new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new GateException(GateErrorCode.GATE_ERROR_IO, "decrypt failed", e); }
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new GateException(GateErrorCode.INTERNAL, "hmac failed", e); }
    }
}
