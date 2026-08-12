package gate.domain.blob;

/**
 * Reference to a large object stored outside SQLite (diff text, raw engine output).
 *
 * @param relPath store-relative path, '/'-separated
 * @param bytes   size in bytes
 * @param sha256  lower-case hex digest, so DB tampering is cross-checkable against the file
 */
public record BlobRef(String relPath, long bytes, String sha256) {

    public BlobRef {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("relPath must not be blank");
        }
        if (relPath.contains("..")) {
            throw new IllegalArgumentException("relPath must not contain '..': " + relPath);
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0");
        }
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex chars");
        }
    }
}
