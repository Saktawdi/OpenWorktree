package gate.adapters.config;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行级写回 gate.toml，保持注释/分区顺序/空行/换行符风格（执行文档-后端-web §8 设置中心）。
 *
 * <p>fail-closed: 候选文本先写入同目录临时文件并用 {@link TomlGateConfigLoader} 完整校验，失败则原文件不动；
 * 成功后先备份为 {@code .bak} 再原子替换（不支持原子移动则先删后移）。
 */
public final class TomlGateConfigWriter {

    private static final Set<String> NON_EDITABLE = Set.of(
            "schema_version",
            "auth_repo", "clones_root", "gate_home", "approvals_dir", "db_path",
            "blob_root", "audit_path", "locks_dir", "index_dir",
            "web.human_token_file");

    private enum Type { INT, STRING, BOOL, STRING_LIST }

    private static final Map<String, Type> KEY_TYPES = new LinkedHashMap<>();

    static {
        // top
        KEY_TYPES.put("schema_version", Type.INT);
        KEY_TYPES.put("project", Type.STRING);
        KEY_TYPES.put("auth_repo", Type.STRING);
        KEY_TYPES.put("clones_root", Type.STRING);
        KEY_TYPES.put("target_ref_whitelist", Type.STRING_LIST);
        KEY_TYPES.put("gate_home", Type.STRING);
        KEY_TYPES.put("approvals_dir", Type.STRING);
        KEY_TYPES.put("db_path", Type.STRING);
        KEY_TYPES.put("blob_root", Type.STRING);
        KEY_TYPES.put("audit_path", Type.STRING);
        KEY_TYPES.put("locks_dir", Type.STRING);
        KEY_TYPES.put("index_dir", Type.STRING);
        KEY_TYPES.put("gate_identity.name", Type.STRING);
        KEY_TYPES.put("gate_identity.email", Type.STRING);
        KEY_TYPES.put("gate_identity.date", Type.STRING);
        KEY_TYPES.put("policy.strictness", Type.STRING);
        KEY_TYPES.put("policy.require_coverage", Type.BOOL);
        KEY_TYPES.put("policy.max_diff_bytes", Type.INT);
        KEY_TYPES.put("policy.max_diff_lines", Type.INT);
        KEY_TYPES.put("policy.engine_accept_degraded", Type.BOOL);
        KEY_TYPES.put("engine.cmd", Type.STRING);
        KEY_TYPES.put("engine.args", Type.STRING_LIST);
        KEY_TYPES.put("engine.timeout_seconds", Type.INT);
        KEY_TYPES.put("engine.provider_id", Type.STRING);
        KEY_TYPES.put("engine.model", Type.STRING);
        KEY_TYPES.put("web.bind", Type.STRING);
        KEY_TYPES.put("web.port", Type.INT);
        KEY_TYPES.put("web.allowed_origins", Type.STRING_LIST);
        KEY_TYPES.put("web.human_token_file", Type.STRING);
        KEY_TYPES.put("session.port_range_min", Type.INT);
        KEY_TYPES.put("session.port_range_max", Type.INT);
        KEY_TYPES.put("session.default_cli", Type.STRING);
        KEY_TYPES.put("session.default_agent_config", Type.STRING);
        KEY_TYPES.put("session.start_timeout_seconds", Type.INT);
        KEY_TYPES.put("agent.default_model", Type.STRING);
        KEY_TYPES.put("agent.default_provider", Type.STRING);
        KEY_TYPES.put("agent.context_template", Type.STRING);
    }

    // —— 设置中心目录复用：SettingsRoutes 直接引用这里的目录，与写回校验保持单一事实来源 ——

    /** 该键是否可在设置中心修改（与 {@link #write} 的拒绝集合一致）。 */
    public static boolean isEditable(String key) {
        return KEY_TYPES.containsKey(key) && !NON_EDITABLE.contains(key);
    }

    /** 键的声明类型名（"int"/"bool"/"string"/"string_list"）；null = 不在目录内。 */
    public static String typeName(String key) {
        Type t = KEY_TYPES.get(key);
        if (t == null) {
            return null;
        }
        return switch (t) {
            case INT -> "int";
            case BOOL -> "bool";
            case STRING -> "string";
            case STRING_LIST -> "string_list";
        };
    }

    /** 全部目录键，按声明顺序（loader KNOWN_KEYS 的镜像）。 */
    public static List<String> knownKeys() {
        return List.copyOf(KEY_TYPES.keySet());
    }

    public void write(Path tomlPath, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "updates must not be empty");
        }
        // 校验键合法性与类型
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (!KEY_TYPES.containsKey(key)) {
                throw new GateException(GateErrorCode.USAGE, "unknown key: " + key);
            }
            if (NON_EDITABLE.contains(key)) {
                throw new GateException(GateErrorCode.USAGE, "key is not editable: " + key);
            }
            if (val != null) {
                Type t = KEY_TYPES.get(key);
                if (!isTypeMatch(t, val)) {
                    throw new GateException(GateErrorCode.USAGE,
                            "type mismatch for " + key + ": expected " + t.name().toLowerCase() + ", got " + describeVal(val));
                }
            }
        }

        String raw;
        try {
            raw = Files.readString(tomlPath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot read gate.toml at " + tomlPath, ex);
        }
        String lineSep = raw.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(raw.split("\\r\\n|\\n|\\r", -1)));

        // 构建索引
        Map<String, Integer> keyToLine = new LinkedHashMap<>();
        Map<String, Integer> sectionHeader = new LinkedHashMap<>();
        // 需要实时维护 sectionEnd，改为每次重建或动态调整；这里每次操作后重建索引简化
        // 先建立初始索引
        rebuildIndex(lines, keyToLine, sectionHeader);
        Map<String, Integer> sectionEnd = buildSectionEnd(lines, sectionHeader);

        // 逐个应用 updates（保持插入顺序）
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String fullKey = e.getKey();
            Object val = e.getValue();
            String section = sectionOf(fullKey);
            String shortKey = shortKey(fullKey);

            Integer idx = keyToLine.get(fullKey);
            if (val == null) {
                if (idx != null) {
                    lines.remove((int) idx);
                    // 重建索引
                    keyToLine.clear();
                    sectionHeader.clear();
                    rebuildIndex(lines, keyToLine, sectionHeader);
                    sectionEnd = buildSectionEnd(lines, sectionHeader);
                }
            } else {
                String formatted = shortKey + " = " + formatValue(KEY_TYPES.get(fullKey), val);
                if (idx != null) {
                    lines.set(idx, formatted);
                    // keyToLine 保持不变，sectionEnd 不变
                } else {
                    if (section.isEmpty()) {
                        // 顶层：插入到第一个 section 之前
                        int insertAt = findFirstSectionHeader(lines);
                        if (insertAt < 0) {
                            lines.add(formatted);
                        } else {
                            lines.add(insertAt, formatted);
                        }
                    } else if (sectionHeader.containsKey(section)) {
                        int insertAt = sectionEnd.get(section) + 1;
                        // 若插入点超出范围则追加
                        if (insertAt > lines.size()) insertAt = lines.size();
                        lines.add(insertAt, formatted);
                    } else {
                        // section 不存在，追加
                        // 确保文件末尾有空行分隔则直接追加
                        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank() && !lines.get(lines.size() - 1).isEmpty()) {
                            // keep as is, join will handle
                        }
                        lines.add("[" + section + "]");
                        lines.add(formatted);
                    }
                    keyToLine.clear();
                    sectionHeader.clear();
                    rebuildIndex(lines, keyToLine, sectionHeader);
                    sectionEnd = buildSectionEnd(lines, sectionHeader);
                }
            }
        }

        String candidate = String.join(lineSep, lines);

        // engine.* 校验：候选缺少 engine.cmd 但 updates 包含 engine.* 且未包含 engine.cmd
        boolean hasEngineOtherInUpdates = updates.keySet().stream()
                .anyMatch(k -> k.startsWith("engine.") && !k.equals("engine.cmd") && updates.get(k) != null);
        if (hasEngineOtherInUpdates && !updates.containsKey("engine.cmd")) {
            // 解析候选是否含 engine.cmd
            Map<String, String> candScalars = new LinkedHashMap<>();
            Map<String, List<String>> candLists = new LinkedHashMap<>();
            try {
                parseForValidation(candidate, tomlPath, candScalars, candLists);
            } catch (GateException ignore) {
                // 解析失败交给后续 loader 校验
                candScalars.put("__parse_failed__", "1");
            }
            boolean candHasCmd = candScalars.containsKey("engine.cmd")
                    && candScalars.get("engine.cmd") != null
                    && !candScalars.get("engine.cmd").isBlank();
            if (!candHasCmd) {
                throw new GateException(GateErrorCode.USAGE, "engine.* 需要 engine.cmd");
            }
        }

        // 临时文件校验
        Path tmp;
        try {
            Path parent = tomlPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            tmp = Files.createTempFile(tomlPath.getParent(), tomlPath.getFileName().toString() + ".tmp", null);
            Files.writeString(tmp, candidate, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot write temp file for validation", ex);
        }
        try {
            new TomlGateConfigLoader().load(tmp);
        } catch (GateException ge) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            // 保留原始错误码与消息，前端直接展示
            throw ge;
        } catch (Exception ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "candidate validation failed: " + ex.getMessage(), ex);
        }

        // 备份并原子替换
        Path bak = tomlPath.resolveSibling(tomlPath.getFileName().toString() + ".bak");
        try {
            Files.copy(tomlPath, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot backup gate.toml: " + ex.getMessage(), ex);
        }
        try {
            Files.move(tmp, tomlPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.deleteIfExists(tomlPath);
                Files.move(tmp, tomlPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot replace gate.toml: " + e2.getMessage(), e2);
            }
        } catch (IOException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot replace gate.toml: " + ex.getMessage(), ex);
        }
    }

    private static boolean isTypeMatch(Type t, Object v) {
        return switch (t) {
            case INT -> v instanceof Integer || v instanceof Long;
            case BOOL -> v instanceof Boolean;
            case STRING -> v instanceof String;
            case STRING_LIST -> {
                if (!(v instanceof List<?> list)) yield false;
                for (Object e : list) if (!(e instanceof String)) yield false;
                yield true;
            }
        };
    }

    private static String describeVal(Object v) {
        if (v == null) return "null";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName() + "(" + v + ")";
    }

    private static String formatValue(Type t, Object v) {
        return switch (t) {
            case INT -> String.valueOf(((Number) v).longValue());
            case BOOL -> String.valueOf(v);
            case STRING -> "\"" + escapeString((String) v) + "\"";
            case STRING_LIST -> {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) v;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(escapeString(list.get(i))).append('"');
                }
                sb.append(']');
                yield sb.toString();
            }
        };
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sectionOf(String fullKey) {
        int dot = fullKey.indexOf('.');
        return dot < 0 ? "" : fullKey.substring(0, dot);
    }

    private static String shortKey(String fullKey) {
        int dot = fullKey.indexOf('.');
        return dot < 0 ? fullKey : fullKey.substring(dot + 1);
    }

    private static int findFirstSectionHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String t = stripComment(lines.get(i)).trim();
            if (t.startsWith("[") && t.endsWith("]")) return i;
        }
        return -1;
    }

    private static void rebuildIndex(List<String> lines, Map<String, Integer> keyToLine, Map<String, Integer> sectionHeader) {
        String section = "";
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String stripped = stripComment(raw).trim();
            if (stripped.isEmpty()) continue;
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                section = stripped.substring(1, stripped.length() - 1).trim();
                sectionHeader.putIfAbsent(section, i);
                continue;
            }
            int eq = stripped.indexOf('=');
            if (eq < 0) continue;
            String key = stripped.substring(0, eq).trim();
            String fullKey = section.isEmpty() ? key : section + "." + key;
            // 最后一个出现为准
            keyToLine.put(fullKey, i);
        }
    }

    private static Map<String, Integer> buildSectionEnd(List<String> lines, Map<String, Integer> sectionHeader) {
        Map<String, Integer> end = new LinkedHashMap<>();
        // 按出现顺序排序 header 索引
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(sectionHeader.entrySet());
        ordered.sort(java.util.Comparator.comparingInt(Map.Entry::getValue));
        for (int i = 0; i < ordered.size(); i++) {
            String sec = ordered.get(i).getKey();
            int start = ordered.get(i).getValue();
            int nextStart = (i + 1 < ordered.size()) ? ordered.get(i + 1).getValue() : lines.size();
            end.put(sec, nextStart - 1);
        }
        // 顶层 section "" 的结束位置：第一个 header 之前
        int firstHeader = findFirstSectionHeader(lines);
        if (firstHeader < 0) {
            end.put("", lines.size() - 1);
        } else {
            end.put("", firstHeader - 1);
        }
        return end;
    }

    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == '#' && !inQuote) return line.substring(0, i);
        }
        return line;
    }

    // 供 engine 校验复用的轻量 parse（与 loader 一致但不校验 unknown）
    private static void parseForValidation(String text, Path tomlPath, Map<String, String> scalars, Map<String, List<String>> lists) {
        String section = "";
        int lineNo = 0;
        for (String rawLine : text.split("\n", -1)) {
            // 需要处理 \r
            String lineRaw = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            lineNo++;
            String line = stripComment(lineRaw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            String fullKey = section.isEmpty() ? key : section + "." + key;
            if (value.startsWith("[")) {
                // 简化：不严格校验
                List<String> list = new ArrayList<>();
                if (value.endsWith("]")) {
                    String inner = value.substring(1, value.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String el : inner.split(",")) {
                            String e = el.trim();
                            if (e.startsWith("\"") && e.endsWith("\"") && e.length() >= 2) list.add(e.substring(1, e.length() - 1));
                            else list.add(e);
                        }
                    }
                }
                lists.put(fullKey, list);
            } else {
                String v = value;
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                scalars.put(fullKey, v);
            }
        }
    }
}
