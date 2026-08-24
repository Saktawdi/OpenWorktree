package gate.ports.store;

import gate.domain.blob.BlobRef;

/** Large objects outside SQLite: diff text, raw engine output (架构落地执行文档 §8.1). */
public interface BlobStore {

    BlobRef put(byte[] data, String relPath);

    byte[] get(BlobRef ref);
}
