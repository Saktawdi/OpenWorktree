package gate.adapters.store;

import gate.application.util.MiniJson;
import gate.domain.config.GateConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 一次性数据布局迁移（分根布局 v1：小数据/配置常驻系统盘 per-user 目录，克隆与项目镜像
 * 等大体积数据在非系统盘的独立重根目录；卸载/更新/热更新不再触碰数据根）。
 *
 * <p>桌面壳在启动后端前完成旧布局物理搬移：旧 {@code %APPDATA%\com.openworktree.desktop}
 * 树里的 {@code gate-home}（小数据）搬入新轻根、{@code clones/} 与 {@code auth-*.git} 搬入
 * 新重根，然后在轻根 {@code local-run/} 落下标记文件 {@code layout-migrate.json}：
 * <pre>
 * { "old_clones_root": "C:/…/local-run/clones", "old_auth_parent": "C:/…/local-run" }
 * </pre>
 * 本迁移在每次启动读取该标记并执行（幂等，成功后标记改名 {@code layout-migrate.done.json}）：
 * <ol>
 *   <li>{@code ticket.clone_path} / {@code agent_session.clone_path}：旧克隆根内的绝对路径
 *       压成相对克隆根的路径（如 {@code T-104}）——仓库层读取时按当前克隆根解析回绝对路径；
 *   <li>{@code project.auth_repo}：旧 auth 父目录前缀改写为新重根 auth 目录。
 * </ol>
 * 克隆文件内 .git/config 的 origin 路径由壳在搬移时同步改写，本类不触碰文件。
 */
public final class LegacyLayoutMigration {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyLayoutMigration.class);
    private static final String MARKER = "layout-migrate.json";
    private static final String MARKER_DONE = "layout-migrate.done.json";

    private LegacyLayoutMigration() {
    }

    public static void apply(GateConfig config, JdbcTemplate jdbc) {
        Path marker = config.gateHome().resolveSibling(MARKER);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        String oldClonesRoot;
        String oldAuthParent;
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(text);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalStateException("marker is not a JSON object");
            }
            oldClonesRoot = stringField(m, "old_clones_root");
            oldAuthParent = stringField(m, "old_auth_parent");
        } catch (Exception e) {
            LOG.error("layout migration: cannot parse {}: {} (retry next boot)", marker, e.getMessage());
            return;
        }
        try {
            relativizeCloneColumns(jdbc, oldClonesRoot, "ticket",
                    "SELECT ticket_no, clone_path FROM ticket WHERE is_super = 0 AND clone_path IS NOT NULL",
                    "UPDATE ticket SET clone_path = ? WHERE ticket_no = ?");
            relativizeCloneColumns(jdbc, oldClonesRoot, "agent_session",
                    "SELECT id, clone_path FROM agent_session WHERE clone_path IS NOT NULL",
                    "UPDATE agent_session SET clone_path = ? WHERE id = ?");
            rebaseAuthRepo(jdbc, oldAuthParent, config.authRepo().getParent());
            try {
                Files.move(marker, config.gateHome().resolveSibling(MARKER_DONE),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.deleteIfExists(marker);
            }
            LOG.info("layout migration: legacy absolute clone/auth paths rebased"
                    + " (old_clones_root={}, old_auth_parent={})", oldClonesRoot, oldAuthParent);
        } catch (Exception e) {
            LOG.error("layout migration failed, marker kept for retry: {}", e.getMessage());
        }
    }

    private static String stringField(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalStateException("marker missing field: " + key);
        }
        return v.toString();
    }

    /** Windows 路径归一化（斜杠统一 + 小写），仅用于前缀比较。 */
    private static String norm(String path) {
        return path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    }

    private static void relativizeCloneColumns(JdbcTemplate jdbc, String oldClonesRoot, String table,
                                               String selectSql, String updateSql) {
        String root = norm(oldClonesRoot);
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql);
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Object raw = row.get("clone_path");
            if (raw == null) {
                continue;
            }
            String stored = raw.toString();
            String n = norm(stored);
            if (!n.startsWith(root) || n.length() <= root.length()) {
                continue;
            }
            String rel = stored.substring(oldClonesRoot.length());
            while (!rel.isEmpty() && (rel.charAt(0) == '\\' || rel.charAt(0) == '/')) {
                rel = rel.substring(1);
            }
            if (rel.isEmpty()) {
                continue;
            }
            jdbc.update(updateSql, rel.replace('\\', '/'),
                    row.containsKey("ticket_no") ? row.get("ticket_no") : row.get("id"));
            changed++;
        }
        if (changed > 0) {
            LOG.info("layout migration: relativized {} row(s) in {}", changed, table);
        }
    }

    private static void rebaseAuthRepo(JdbcTemplate jdbc, String oldAuthParent, Path newAuthParent) {
        String oldNorm = norm(oldAuthParent);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, auth_repo FROM project WHERE auth_repo IS NOT NULL");
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Object raw = row.get("auth_repo");
            if (raw == null) {
                continue;
            }
            String stored = raw.toString();
            if (!norm(stored).startsWith(oldNorm)) {
                continue;
            }
            String suffix = stored.substring(oldAuthParent.length());
            while (!suffix.isEmpty() && (suffix.charAt(0) == '\\' || suffix.charAt(0) == '/')) {
                suffix = suffix.substring(1);
            }
            jdbc.update("UPDATE project SET auth_repo = ? WHERE id = ?",
                    newAuthParent.resolve(suffix).toString(), row.get("id"));
            changed++;
        }
        if (changed > 0) {
            LOG.info("layout migration: rebased {} project auth_repo row(s)", changed);
        }
    }
}
