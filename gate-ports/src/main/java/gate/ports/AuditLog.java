package gate.ports;

import gate.domain.audit.AuditEvent;

/** Append-only, hash-chained audit log. Never rotated by the application (§10.2). */
public interface AuditLog {

    void append(AuditEvent event);
}
