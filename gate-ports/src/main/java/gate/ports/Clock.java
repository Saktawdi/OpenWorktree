package gate.ports;

import java.time.Instant;

/** Time source. A port so recovery/determinism tests can pin it. */
public interface Clock {

    Instant now();
}
