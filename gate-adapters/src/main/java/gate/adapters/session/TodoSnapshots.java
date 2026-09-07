package gate.adapters.session;

import gate.application.util.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * todowrite 快照提取（任务清单重构 V21）：从 CLI 工具调用的 input JSON 里提取规范
 * 任务清单，供 CLI 适配器在 tool 事件到达处 journal 到 session_todo 表（不等整回合
 * idle 落库），以及 {@code JdbcSessionRepository#findTodos} 的 lazy 回填复用。
 *
 * <p>语义（与前端 shared/todoUtils 保持一致）：
 * <ul>
 *   <li>只认「写」工具（todowrite/todo_write）——todoread 是读操作，不产生新快照；</li>
 *   <li>参数为合法数组（含 <b>空数组 = 显式清空</b>）即返回规范 JSON；</li>
 *   <li>解析失败或数组非空但全部条目非法返回 {@code null}——调用方跳过，不覆盖现有快照。</li>
 * </ul>
 */
public final class TodoSnapshots {

    private TodoSnapshots() {
    }

    /** todowrite 类写工具识别（MCP 前缀如 gate_todowrite 一并命中；todoread 不算）。 */
    public static boolean isWriteTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String n = toolName.toLowerCase();
        return n.contains("todowrite") || n.equals("todo_write");
    }

    /**
     * 从 todowrite 参数 JSON 提取规范任务清单。兼容两种载体：直接数组、
     * {@code {"todos":[...]}}。返回规范形 {@code [{content,status,priority?,id?}]} 的
     * JSON 字符串（空数组即 "[]"），非法输入返回 null。
     */
    public static String canonicalJson(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(argumentsJson);
        } catch (Exception e) {
            return null;
        }
        Object items = parsed;
        if (parsed instanceof Map<?, ?> m) {
            items = m.get("todos") != null ? m.get("todos") : m.get("items");
        }
        if (!(items instanceof List<?> raw)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> entry)) {
                continue;
            }
            String content = str(entry.get("content"), entry.get("text"));
            if (content == null) {
                continue;
            }
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("content", content);
            todo.put("status", normalizeStatus(str(entry.get("status"), null)));
            String priority = oneOf(str(entry.get("priority"), null), "high", "medium", "low");
            if (priority != null) {
                todo.put("priority", priority);
            }
            String id = str(entry.get("id"), null);
            if (id != null) {
                todo.put("id", id);
            }
            out.add(todo);
        }
        // 非空数组但全部条目非法 = 输入残缺，不覆盖现有快照（空数组本身是合法清空）。
        if (!raw.isEmpty() && out.isEmpty()) {
            return null;
        }
        return MiniJson.write(out);
    }

    private static String normalizeStatus(String status) {
        return status == null ? "pending" : oneOf(status, "pending", "in_progress", "completed", "cancelled");
    }

    private static String oneOf(String value, String... allowed) {
        if (value == null) {
            return null;
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return a;
            }
        }
        return allowed[0];
    }

    private static String str(Object a, Object b) {
        String s = a instanceof String text && !text.isBlank() ? text : null;
        return s != null ? s : (b instanceof String text2 && !text2.isBlank() ? text2 : null);
    }
}
