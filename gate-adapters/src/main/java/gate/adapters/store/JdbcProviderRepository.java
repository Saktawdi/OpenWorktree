package gate.adapters.store;

import gate.ports.ProviderRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@code provider} / {@code model} store (§10.1.1).
 *
 * <p>P1 only ever writes the {@code manual} row. The tables exist now so P2 can attach prism behind
 * the newapi gateway without a migration, and so {@code review_result} can carry the reviewer
 * coordinates from day one.
 */
public final class JdbcProviderRepository implements ProviderRepository {

    private final JdbcTemplate jdbc;

    public JdbcProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ProviderRow> MAPPER = (ResultSet rs, int n) -> new ProviderRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("base_url"),
            rs.getString("api_key_ref"),
            rs.getString("type"),
            Instant.parse(rs.getString("created_at")));

    @Override
    public void upsert(ProviderRow row, Instant now) {
        jdbc.update("""
                INSERT INTO provider(id, name, base_url, api_key_ref, type, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    base_url = excluded.base_url,
                    api_key_ref = excluded.api_key_ref,
                    type = excluded.type,
                    updated_at = excluded.updated_at
                """,
                row.id(), row.name(), row.baseUrl(), row.apiKeyRef(), row.type(),
                row.createdAt().toString(), now.toString());
    }

    @Override
    public Optional<ProviderRow> find(String id) {
        List<ProviderRow> rows = jdbc.query("SELECT * FROM provider WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ProviderRow> findAll() {
        return jdbc.query("SELECT * FROM provider ORDER BY id", MAPPER);
    }

    @Override
    public void replaceModels(String providerId, List<String> modelNames, Instant pulledAt) {
        jdbc.update("DELETE FROM model WHERE provider_id = ?", providerId);
        for (String model : modelNames) {
            jdbc.update("INSERT INTO model(provider_id, model_name, pulled_at) VALUES (?,?,?)",
                    providerId, model, pulledAt.toString());
        }
    }

    @Override
    public List<String> models(String providerId) {
        return jdbc.queryForList("SELECT model_name FROM model WHERE provider_id = ? ORDER BY model_name",
                String.class, providerId);
    }
}
