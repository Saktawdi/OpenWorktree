package gate.adapters.engine;

import gate.ports.BlobStore;
import gate.ports.ReviewEngine;
import gate.ports.ReviewEngineFactory;

/** P1 factory: every review is a manual verdict expressed as a degenerate engine. */
public final class ManualReviewEngineFactory implements ReviewEngineFactory {

    private final BlobStore blobStore;

    public ManualReviewEngineFactory(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    @Override
    public ReviewEngine forManualVerdict(boolean pass, String note) {
        return new ManualReviewEngine(blobStore, pass, note);
    }
}
