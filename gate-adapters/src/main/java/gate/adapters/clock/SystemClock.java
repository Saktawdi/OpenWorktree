package gate.adapters.clock;

import gate.ports.infra.Clock;
import java.time.Instant;

/** System clock. */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
