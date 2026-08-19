package gate.application.publish;

import gate.application.PublishCommand;
import gate.application.PublishResult;

/**
 * Publish capability handler (EX-002).
 * Owns PublishIntent, Git CAS, reconcile. Must enforce I1-I2-I5 invariants.
 */
public final class PublishHandler {
    public PublishResult handle(PublishCommand cmd) {
        throw new UnsupportedOperationException("PublishHandler not yet wired - see EX-002");
    }
}
