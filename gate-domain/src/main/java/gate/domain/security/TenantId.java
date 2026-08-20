package gate.domain.security;

/**
 * Value object for tenant isolation. All facts must be tenant-scoped (ADR-007).
 */
public record TenantId(String value) {
    public static final TenantId DEFAULT = new TenantId("default");
    public TenantId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("tenantId blank");
    }
    @Override public String toString() { return value; }
}
