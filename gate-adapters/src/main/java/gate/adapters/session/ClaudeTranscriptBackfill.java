package gate.adapters.session;

import gate.adapters.io.AdapterLog;
import gate.application.util.MiniJson;
import gate.domain.session.Role;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionUsage;
import gate.ports.infra.Clock;
import gate.ports.store.SessionRepository;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CLAUDE 链路的历史对账回填：把 CLI 自己转录里、门禁从未落库的助手正文补回历史。
 *
 * <p>对齐 opencode 的 {@code OpenCodeServeAdapter.backfillFromServe}（T-113 教训的同一条：
 * 「回合缓冲只在内存里、落库只在回合收尾那一下，中途出事整回合就没了」）。CLAUDE 侧丢内容
 * 的三条路径：进程猝死/断电（全丢）、网关 {@code result.is_error} 收尾（见
 * {@code ClaudeHeadlessAdapter#persistAssistantTexts}）、用户中断（T-120 规则本身不落盘）。
 * 这三条在 CLI 自己的转录里都留着完整记录，所以对账能把它们一起找回来。
 *
 * <p>数据源：{@code <claude 配置目录>/projects/<cwd 转义>/<cli_session_id>.jsonl}。目录转义
 * 是 CLI 的实现细节（非字母数字一律替换成 {@code -}，本机实测 {@code com.x.desktop} →
 * {@code com-x-desktop}、{@code wxid_a} → {@code wxid-a}），所以<b>推不出来就当没有</b>：
 * 文件不在只降级告警，绝不阻断回合。{@code CLAUDE_CONFIG_DIR} 与 CLI 同源，未设时取
 * {@code ~/.claude}。
 *
 * <p>两处与 opencode 有意不同的地方：
 * <ul>
 *   <li><b>只补 ASSISTANT，不补 USER。</b> gate 是会话里唯一的 user 消息写入方，缺 user 行
 *       意味着那条消息根本没经 gate 发出去，没有可补的东西；而转录里混着 CLI 自己合成的用户
 *       记录（{@code Continue from where you left off.} 之类），照单全收会把它们灌进历史。</li>
 *   <li><b>按内容去重、按转录原时间戳落库</b>，而不是 opencode 的「已有 ASSISTANT 行最新时间」
 *       截点。截点法在这里会漏：CLAUDE 每回合都新落 ASSISTANT 行（时间戳是当下），截点会被
 *       顶过还没补的旧空洞，那些洞就永远找不回来了。内容去重与顺序无关，幂等，且补回来的行
 *       按原时间戳回到时间线上的原位（{@code findMessages} 按 created_at 排序），不会堆到会话
 *       末尾。同一句话反复出现用多重集计数，不会误吞。</li>
 * </ul>
 *
 * <p>不吃 cumulativeUsage：会话累计用量在回合收尾时已按 {@code parsed.usage} 累加过（错误分支
 * 也累加），回填时再加一次就重复计数了；同理也不回写 ticket 的 token 统计。代价是猝死回合的
 * token 不进累计值——少算好过算重。
 */
final class ClaudeTranscriptBackfill {

    /** 与 opencode 侧同名的组件标签，便于两条链路在 adapters.log 里一起筛。 */
    private static final String COMPONENT = "claude";

    private final SessionRepository sessions;
    private final Clock clock;
    private final AdapterLog log;
    /** 转录根（{@code <这里>/projects/...}）；null = 按 {@code CLAUDE_CONFIG_DIR} / {@code ~/.claude} 现取。 */
    private Path configRoot;

    ClaudeTranscriptBackfill(SessionRepository sessions, Clock clock, AdapterLog log, Path configRoot) {
        this.sessions = sessions;
        this.clock = clock;
        this.log = log == null ? AdapterLog.noop() : log;
        this.configRoot = configRoot;
    }

    /** 测试钩子：把转录根指到临时目录（生产不调，走默认解析）。 */
    void useConfigRoot(Path root) {
        this.configRoot = root;
    }

    /**
     * 对账一次：把转录里缺的助手正文补落库。返回补落库的条数。
     *
     * <p>任何异常（文件读不动、行不是合法 JSON、时间戳解析不了）只降级告警，绝不阻断回合——
     * 与 opencode 的 backfill.failed 同语义。
     */
    int reconcile(String sessionId, Path cwd, String cliSessionId) {
        if (sessionId == null || cliSessionId == null || cliSessionId.isBlank() || cwd == null) {
            return 0;
        }
        try {
            Path file = transcriptFile(cwd, cliSessionId);
            if (!Files.isRegularFile(file)) {
                log.warn(COMPONENT, "backfill.unavailable", "sessionId", sessionId, "file", file.toString());
                return 0;
            }
            Map<String, Integer> alreadyPersisted = persistedAssistantTexts(sessionId);
            List<SessionMessage> missing = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    SessionMessage message = assistantMessage(line, sessionId, cliSessionId, alreadyPersisted);
                    if (message != null) {
                        missing.add(message);
                    }
                }
            }
            // 逐条落库：时间戳取转录原文，历史视图按 created_at 排序，补回来的行回到原位。
            for (SessionMessage message : missing) {
                sessions.insertMessage(message);
            }
            if (!missing.isEmpty()) {
                log.info(COMPONENT, "backfill.done", "sessionId", sessionId, "rows", missing.size(),
                        "file", file.toString());
            }
            return missing.size();
        } catch (Exception e) {
            log.warn(COMPONENT, "backfill.failed", "sessionId", sessionId, "error", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 转录里的一行 → 待补的助手消息；不是助手文本、或已经落过库的，返回 null（并从
     * {@code alreadyPersisted} 里核销一次，重复语句按出现次数配平）。
     */
    private SessionMessage assistantMessage(String line, String sessionId, String cliSessionId,
                                            Map<String, Integer> alreadyPersisted) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(line.trim());
        } catch (Exception notJson) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> record = ClaudeHeadlessAdapter.cast(raw);
        if (!"assistant".equals(String.valueOf(record.get("type")))) {
            return null;
        }
        // 同一个转录文件只装一个 CLI 会话，但记录自带 sessionId 且懒复活/分支场景下可能串过——
        // 不一致的行不认，宁可漏补也不串账。
        Object recordSessionId = record.get("sessionId");
        if (recordSessionId != null && !cliSessionId.equals(String.valueOf(recordSessionId))) {
            return null;
        }
        if (!(record.get("message") instanceof Map<?, ?> messageRaw)) {
            return null;
        }
        Map<String, Object> message = ClaudeHeadlessAdapter.cast(messageRaw);
        String text = ClaudeHeadlessAdapter.extractText(message, null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String key = dedupKey(text);
        Integer remaining = alreadyPersisted.get(key);
        if (remaining != null && remaining > 0) {
            alreadyPersisted.put(key, remaining - 1);
            return null;
        }
        SessionUsage usage = ClaudeHeadlessAdapter.extractUsage(message.get("usage"));
        String reportedModel = ClaudeHeadlessAdapter.strField(message, "model");
        String[] halves = reportedModel == null || reportedModel.isBlank()
                ? new String[]{null, null}
                : ClaudeHeadlessAdapter.splitModelHalves(reportedModel);
        // degraded 在领域里的含义就是「usage 没解出来」（见 SessionMessage javadoc），照实标。
        return new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT, text,
                List.of(), usage, usage == null, transcriptTimestamp(record), List.of(),
                halves[0], halves[1], null);
    }

    /** 已落库的助手正文 → 出现次数（空白归一化后比对，吸收换行/空格差异）。 */
    private Map<String, Integer> persistedAssistantTexts(String sessionId) {
        Map<String, Integer> counts = new HashMap<>();
        for (SessionMessage message : sessions.findMessages(sessionId)) {
            if (message.role() == Role.ASSISTANT) {
                counts.merge(dedupKey(message.content()), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 转录的时间戳是 CLI 自己写的 ISO-8601（UTC）；解不出来就退回当下，宁可位置不准也不丢内容。 */
    private Instant transcriptTimestamp(Map<String, Object> record) {
        String raw = ClaudeHeadlessAdapter.strField(record, "timestamp");
        if (raw != null) {
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                // 落到兜底
            }
        }
        return clock.now();
    }

    private Path transcriptFile(Path cwd, String cliSessionId) {
        return claudeConfigDir()
                .resolve("projects")
                .resolve(encodeProjectDir(cwd))
                .resolve(cliSessionId + ".jsonl");
    }

    private Path claudeConfigDir() {
        if (configRoot != null) {
            return configRoot;
        }
        String fromEnv = System.getenv("CLAUDE_CONFIG_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return Path.of(System.getProperty("user.home", "."), ".claude");
    }

    /**
     * CLI 的工作目录 → 转录目录名：非字母数字一律换成 {@code -}（{@code D:\a\b} →
     * {@code D--a-b}）。纯实现细节，认错了就当转录不存在。
     */
    static String encodeProjectDir(Path cwd) {
        return cwd.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
    }

    /** 去重键：空白归一化——转录与 stream 对多 text 块的拼接方式若有细微差异也不至于重复落库。 */
    private static String dedupKey(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
