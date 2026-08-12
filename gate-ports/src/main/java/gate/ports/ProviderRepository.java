package gate.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@code provider} / {@code model} tables (架构落地执行文档 §10.1.1). */
public interface ProviderRepository {

    /**
     * @param apiKeyRef reference or ciphertext only. The plaintext key must never reach any other
     *                  table, the audit log, a commit trailer, or argv (ADR-9, §6.1).
     */
    record ProviderRow(String id, String name, String baseUrl, String apiKeyRef, String type, Instant createdAt) {
    }

    void upsert(ProviderRow row, Instant now);

    Optional<ProviderRow> find(String id);

    List<ProviderRow> findAll();

    void replaceModels(String providerId, List<String> modelNames, Instant pulledAt);

    List<String> models(String providerId);
}
