package gate.adapters.blob;

import gate.domain.blob.BlobRef;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.BlobStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Filesystem blob store for diff text and raw engine output (架构落地执行文档 §8.1: the DB keeps a
 * path plus size plus digest, the bytes live on disk).
 */
public final class FsBlobStore implements BlobStore {

    private final Path root;

    public FsBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create blob root " + this.root, e);
        }
    }

    @Override
    public BlobRef put(byte[] data, String relPath) {
        Path target = resolveInside(relPath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot write blob " + target, e);
        }
        return new BlobRef(relPath.replace('\\', '/'), data.length, sha256(data));
    }

    @Override
    public byte[] get(BlobRef ref) {
        Path target = resolveInside(ref.relPath());
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot read blob " + target, e);
        }
    }

    /** Refuses any relative path that would land outside the store root. */
    private Path resolveInside(String relPath) {
        Path resolved = root.resolve(relPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new GateException(GateErrorCode.INTERNAL, "blob path escapes the store root: " + relPath);
        }
        return resolved;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new GateException(GateErrorCode.INTERNAL, "SHA-256 unavailable", e);
        }
    }
}
