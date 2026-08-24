package gate.adapters.engine;

import gate.ports.store.BlobStore;
import gate.ports.engine.ReviewEngine;
import gate.ports.engine.ReviewEngineFactory;

/** P1 factory: every review is a manual verdict expressed as a degenerate engine. */
public final class ManualReviewEngineFactory implements ReviewEngineFactory {

    private final BlobStore blobStore;

    public ManualReviewEngineFactory(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    @Override
    public ReviewEngine forManualVerdict(Boolean pass, String note) {
        return new ManualReviewEngine(blobStore, pass, note);
    }

    @Override
    public ReviewEngine forPrism() {
        // P1 has no live engine wired in.
        return null;
    }
}
