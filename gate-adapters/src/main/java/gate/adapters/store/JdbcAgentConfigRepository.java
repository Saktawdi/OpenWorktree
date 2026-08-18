package gate.adapters.store;

import gate.application.MiniJson;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.ports.AgentConfigRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed {@link AgentConfigRepository} over the {@code agent_config} table. */
public final class JdbcAgentConfigRepository implements AgentConfigRepository {

    private static final String CLI_DEFAULT = "cli-default";

    private final JdbcTemplate jdbc;

    public JdbcAgentConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AgentConfig> MAPPER = (ResultSet rs, int n) -> new AgentConfig(
            rs.getString("id"),
            rs.getString("name"),
            AgentCli.valueOf(rs.getString("cli")),
            readOptional(rs.getString("provider_id")),
            readOptional(rs.getString("model")),
            rs.getString("system_prompt"),
            parseStringList(rs.getString("extra_flags")),
            rs.getString("description"));

    @Override
    public List<AgentConfig> findAll() {
        return jdbc.query("SELECT * FROM agent_config ORDER BY id", MAPPER);
    }

    @Override
    public Optional<AgentConfig> find(String id) {
        List<AgentConfig> rows = jdbc.query("SELECT * FROM agent_config WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void insert(AgentConfig config, Instant now) {
        jdbc.update("""
                INSERT INTO agent_config(id, name, cli, provider_id, model, system_prompt, extra_flags,
                                        description, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                config.id(), config.name(), config.cli().name(), storeOptional(config.providerId()),
                storeOptional(config.model()),
                config.systemPrompt(), writeStringList(config.extraFlags()), config.description(),
                now.toString(), now.toString());
    }

    @Override
    public void update(AgentConfig config, Instant now) {
        jdbc.update("""
                UPDATE agent_config SET name = ?, cli = ?, provider_id = ?, model = ?,
                       system_prompt = ?, extra_flags = ?, description = ?, updated_at = ?
                WHERE id = ?
                """,
                config.name(), config.cli().name(), storeOptional(config.providerId()),
                storeOptional(config.model()),
                config.systemPrompt(), writeStringList(config.extraFlags()), config.description(),
                now.toString(), config.id());
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM agent_config WHERE id = ?", id);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (parsed instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    out.add(String.valueOf(o));
                }
                return out;
            }
        } catch (Exception ignored) {
            // A malformed list is treated as empty rather than breaking the console.
        }
        return List.of();
    }

    private static String writeStringList(List<String> values) {
        List<String> safe = values == null ? List.of() : values;
        return write(safe);
    }

    private static String readOptional(String value) {
        return CLI_DEFAULT.equals(value) ? null : value;
    }

    private static String storeOptional(String value) {
        return value == null || value.isBlank() ? CLI_DEFAULT : value;
    }

    private static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
