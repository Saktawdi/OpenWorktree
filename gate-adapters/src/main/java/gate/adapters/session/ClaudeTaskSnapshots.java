package gate.adapters.session;

import gate.application.util.MiniJson;
import gate.domain.session.TurnPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * claude 任务清单 journal 提取（V24 TaskCreate/TaskUpdate 平行链）：把 claude 独有的
 * 任务工具事件累积成规范任务列表，供 CLI 适配器写入 session_task 表。
 *
 * <p>与 {@link TodoSnapshots}（todowrite 全量快照）的关键差异：TaskCreate/TaskUpdate 是
 * <b>增量事件</b>而非快照——TaskCreate 追加条目、TaskUpdate 按 id 改字段，journal 的值
 * 是整个事件流的累积结果。
 *
 * <p>id 语义（两级）：
 * <ul>
 *   <li>live 阶段（content_block_stop，result 未到）：TaskCreate 的 id 由 claude 服务端
 *       分配、尚不可知，按 journal 现有 max+1 乐观分配——claude 的 id 每会话单调递增，
 *       绝大多数时候直接命中；</li>
 *   <li>回合终态（消息 parts 已带 result_json）：从 "Task #N created successfully" 确定性
 *       解析真实 id。终态后适配器按消息历史<b>全量重放</b>整表覆盖（journal = 历史的纯
 *       投影），live 阶段的乐观错位/幽灵条目在下一个成功回合自愈。</li>
 * </ul>
 *
 * <p>工具识别：TaskCreate/TaskUpdate 是 claude 内置工具名（opencode 无此命名，天然隔离）；
 * TaskList 是只读工具，不产生事件。解析失败的条目跳过，不中断累积。
 */
public final class ClaudeTaskSnapshots {

    private ClaudeTaskSnapshots() {
    }

    /** claude 任务写工具识别（TaskCreate/TaskUpdate；TaskList 只读不算）。 */
    public static boolean isWriteTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String n = toolName.toLowerCase();
        return n.equals("taskcreate") || n.equals("taskupdate");
    }

    /**
     * Read-modify-write：把一个任务工具事件应用到现有 journal JSON 上，返回新 journal
     * JSON。非任务工具 / 参数不可解析 / 更新了不存在的 id → 返回 {@code null}（调用方
     * 跳过，不覆盖现有 journal）。
     */
    public static String applyJson(String currentJson, String toolName, String argumentsJson,
                                   String resultJson) {
        if (!isWriteTool(toolName)) {
            return null;
        }
        List<Object> tasks = parseList(currentJson);
        if (!apply(tasks, toolName, argumentsJson, resultJson)) {
            return null;
        }
        return MiniJson.write(tasks);
    }

    /**
     * 全量重放：按 rowid 序扫消息 parts 的全部工具段，从头累积出整表 journal。
     * 一个任务事件都没有（V24 之前的会话或从未用过任务工具）返回 {@code null}——
     * 调用方据此区分「无 journal」与「空 journal」。
     */
    public static String replayJson(List<TurnPart> toolParts) {
        boolean any = false;
        List<Object> tasks = new ArrayList<>();
        for (TurnPart p : toolParts) {
            if (!isWriteTool(p.name())) {
                continue;
            }
            if (apply(tasks, p.name(), p.argumentsJson(), p.resultJson())) {
                any = true;
            }
        }
        return any ? MiniJson.write(tasks) : null;
    }

    // -------------------------------------------------------------------------------------------
    // 累积内核
    // -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static boolean apply(List<Object> tasks, String toolName, String argumentsJson,
                                 String resultJson) {
        Map<String, Object> args;
        try {
            Object parsed = MiniJson.parse(argumentsJson == null ? "" : argumentsJson);
            if (!(parsed instanceof Map<?, ?> m)) {
                return false;
            }
            args = (Map<String, Object>) m;
        } catch (Exception e) {
            return false;
        }
        String n = toolName.toLowerCase();
        if (n.equals("taskcreate")) {
            return applyCreate(tasks, args, resultJson);
        }
        return applyUpdate(tasks, args);
    }

    @SuppressWarnings("unchecked")
    private static boolean applyCreate(List<Object> tasks, Map<String, Object> args,
                                       String resultJson) {
        String subject = str(args.get("subject"));
        String description = str(args.get("description"));
        String activeForm = str(args.get("activeForm"));
        if (subject == null && description == null) {
            return false;
        }
        // 权威 id 优先（终态重放路径）；live 路径 result 未到，按 max+1 乐观分配。
        Integer id = createdTaskId(resultJson);
        if (id == null) {
            id = (maxId(tasks) == null ? 0 : maxId(tasks)) + 1;
        }
        Map<String, Object> task = new java.util.LinkedHashMap<>();
        task.put("id", id);
        if (subject != null) {
            task.put("subject", subject);
        }
        if (description != null) {
            task.put("description", description);
        }
        if (activeForm != null) {
            task.put("activeForm", activeForm);
        }
        task.put("status", "pending");
        // 同 id 重复创建：last-wins 覆盖（防御性；正常时序不会发生）。
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i) instanceof Map<?, ?> m && id.equals(intOrNull(((Map<String, Object>) m).get("id")))) {
                tasks.set(i, task);
                return true;
            }
        }
        tasks.add(task);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean applyUpdate(List<Object> tasks, Map<String, Object> args) {
        Integer id = intOrNull(args.get("taskId") != null ? args.get("taskId") : args.get("task_id"));
        if (id == null) {
            return false;
        }
        for (Object o : tasks) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> task = (Map<String, Object>) m;
            if (!id.equals(intOrNull(task.get("id")))) {
                continue;
            }
            // 全字段应用（工单对齐：TaskUpdate 不仅能改 status）：args 里出现的字段全量覆盖。
            String status = normalizeStatus(str(args.get("status")));
            if (status != null) {
                task.put("status", status);
            }
            String subject = str(args.get("subject"));
            if (subject != null) {
                task.put("subject", subject);
            }
            String description = str(args.get("description"));
            if (description != null) {
                task.put("description", description);
            }
            String activeForm = str(args.get("activeForm"));
            if (activeForm != null) {
                task.put("activeForm", activeForm);
            }
            return true;
        }
        // 更新不存在的 id（跨会话残留/老会话未回放）：忽略，不污染 journal。
        return false;
    }

    /** "Task #12 created successfully: …" → 12；不匹配返回 null。 */
    static Integer createdTaskId(String resultJson) {
        if (resultJson == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Task\\s*#(\\d+)\\s+created", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(resultJson);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer maxId(List<Object> tasks) {
        Integer max = null;
        for (Object o : tasks) {
            if (o instanceof Map<?, ?> m) {
                Integer id = intOrNull(((Map<?, ?>) m).get("id"));
                if (id != null && (max == null || id > max)) {
                    max = id;
                }
            }
        }
        return max;
    }

    /** taskId 宽容解析：字符串 "1" 或数字 1 → 1。 */
    private static Integer intOrNull(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "pending", "in_progress", "completed", "cancelled" -> status;
            default -> null;
        };
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (parsed instanceof List<?> list) {
                return new ArrayList<>((List<Object>) list);
            }
        } catch (Exception ignored) {
            // journal 损坏按空表重来（下一回合终态重放会整体校正）
        }
        return new ArrayList<>();
    }
}
