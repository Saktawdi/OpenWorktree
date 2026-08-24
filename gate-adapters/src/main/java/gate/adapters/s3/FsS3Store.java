package gate.adapters.s3;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.S3Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fs-backed S3 mock implementing digest-addressed conditional semantics (Phase3 §15.3).
 * Key = digest/sha256, versionId = UUID, etag = sha256.
 * Real S3 would be enabled via profile; local tests use this.
 */
public final class FsS3Store implements S3Store {

    private final Path root;
    private final ConcurrentHashMap<String, PutResult> index = new ConcurrentHashMap<>();

    public FsS3Store(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try { Files.createDirectories(root); } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create s3 root", e);
        }
    }

    @Override
    public PutResult put(String key, byte[] data, String expectedSha256) {
        String sha = sha256(data);
        if (expectedSha256 != null && !sha.equalsIgnoreCase(expectedSha256)) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "digest mismatch: expected " + expectedSha256 + " got " + sha);
        }
        if (data.length != sha.length() && false) {} // size check done by caller
        String version = UUID.randomUUID().toString();
        Path target = root.resolve(sanitize(key)).resolve(version);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (Exception e) { throw new GateException(GateErrorCode.GATE_ERROR_IO, "s3 put failed", e); }
        PutResult r = new PutResult(key, version, sha, data.length, sha);
        index.put(key + "#" + version, r);
        index.put(key, r);
        return r;
    }

    @Override
    public PutResult putConditional(String key, byte[] data, String expectedSha256, String ifMatchVersion) {
        PutResult current = index.get(key);
        if (ifMatchVersion != null) {
            if (current == null || !ifMatchVersion.equals(current.versionId())) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO, "conditional write failed: version mismatch");
            }
        } else {
            if (current != null) throw new GateException(GateErrorCode.GATE_ERROR_IO, "conditional create failed: already exists");
        }
        return put(key, data, expectedSha256);
    }

    @Override public Optional<PutResult> head(String key) { return Optional.ofNullable(index.get(key)); }

    @Override public byte[] get(String key) {
        PutResult r = index.get(key);
        if (r == null) throw new GateException(GateErrorCode.GATE_ERROR_IO, "s3 not found: " + key);
        Path target = root.resolve(sanitize(key)).resolve(r.versionId());
        try { return Files.readAllBytes(target); }
        catch (Exception e) { throw new GateException(GateErrorCode.GATE_ERROR_IO, "s3 get failed", e); }
    }

    @Override public void delete(String key, String versionId) {
        index.remove(key + "#" + versionId);
        if (versionId.equals(index.getOrDefault(key, new PutResult(key,"", "",0,"")).versionId())) index.remove(key);
        try { Files.deleteIfExists(root.resolve(sanitize(key)).resolve(versionId)); } catch (Exception ignored) {}
    }

    @Override public int gcOrphans(Instant threshold) {
        // mock: no orphan tracking without DB reference; return 0
        return 0;
    }

    private static String sanitize(String k) { return k.replaceAll("[^A-Za-z0-9._/-]", "_"); }

    static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new GateException(GateErrorCode.INTERNAL, "SHA-256 unavailable", e); }
    }
}
