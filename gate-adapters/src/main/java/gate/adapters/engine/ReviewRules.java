package gate.adapters.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目级审查规则（{@code .gate/rules.json}）：glob → 审查文本 / 跳过声明。
 *
 * <p>OCR 的经验：单一通用 prompt 无法按语言/路径执行差异化标准，而纯 prompt 叙述的规则路由
 * 又不稳定——glob 匹配这种"不允许出错"的步骤必须由代码完成，prompt 只消费结果。gate 对应
 * 的配置面就是克隆里的 {@code .gate/rules.json}（与 {@code .gate/config.toml} 同目录），格式：
 *
 * <pre>{@code
 * {
 *   "rules": [
 *     {"glob": "**\/*.sql", "rule": "重点检查 SQL 注入与迁移可回滚性"},
 *     {"glob": "src/generated/**", "skip": true}
 *   ]
 * }
 * }</pre>
 *
 * <p><b>fail-open 是刻意的</b>：rules.json 是加固项而非安全控制——安全控制（覆盖度、判决、
 * 证据链）全部在引擎之外且 fail-closed。若因它解析失败就拒绝整轮审查，任何能写这个文件的
 * 进程（包括被审查的 Agent 自己）就都获得了拒绝服务门禁的能力。解析失败按无规则继续，由
 * blob 与日志留痕。
 *
 * <p>glob 语义：{@code **} 跨目录、{@code *} 不跨目录、{@code ?} 单字符，自实现以避免
 * {@code java.nio} glob 在不同文件系统上对根级文件的匹配差异；路径一律 '/' 分隔。
 */
final class ReviewRules {

    record Rule(String glob, String text, boolean skip) {
    }

    static final ReviewRules EMPTY = new ReviewRules(List.of());

    private static final String RULES_REL_PATH = ".gate/rules.json";

    private final List<Rule> rules;

    private ReviewRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** 从克隆工作区加载 {@code .gate/rules.json}；不存在或任何解析问题都安静地退回 EMPTY。 */
    static ReviewRules load(Path cloneRepo) {
        if (cloneRepo == null) {
            return EMPTY;
        }
        Path file = cloneRepo.resolve(RULES_REL_PATH);
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return EMPTY;
        }
        return parse(json);
    }

    static ReviewRules parse(String json) {
        Map<String, Object> root;
        try {
            root = PrismJson.parseObjectMap(json);
        } catch (Exception e) {
            return EMPTY;
        }
        Object rulesRaw = root.get("rules");
        if (!(rulesRaw instanceof List<?> list)) {
            return EMPTY;
        }
        List<Rule> rules = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rm = (Map<String, Object>) item;
            String glob = stringOrNull(rm.get("glob"));
            if (glob == null || glob.isBlank()) {
                continue;
            }
            String text = stringOrNull(rm.get("rule"));
            boolean skip = Boolean.TRUE.equals(rm.get("skip"));
            if (text == null && !skip) {
                continue;   // 既无文本又不跳过的规则没有意义
            }
            rules.add(new Rule(glob, text, skip));
        }
        return rules.isEmpty() ? EMPTY : new ReviewRules(rules);
    }

    /** 命中该路径的全部规则文本，按声明顺序；无命中为空。 */
    List<String> ruleTexts(String path) {
        List<String> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.skip() && r.text() != null && matches(r.glob(), path)) {
                out.add(r.text());
            }
        }
        return out;
    }

    /** 项目规则是否显式跳过该路径。 */
    boolean skip(String path) {
        for (Rule r : rules) {
            if (r.skip() && matches(r.glob(), path)) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 把规则按"规则集相同"聚成分块渲染成 prompt 片段——OCR 的 {@code <rules for="...">} 惯例：
     * 一组文件命中不同语言规则时，裸拼接无法表达哪段规则管哪个文件，标注归属消除歧义；
     * 全组共用一套规则时按裸文本输出（字节级与旧形态一致，前缀缓存友好）。
     */
    String renderFor(Set<String> paths) {
        Map<String, List<String>> byText = new LinkedHashMap<>();
        List<String> orderPaths = new ArrayList<>(paths);
        orderPaths.sort(String::compareTo);
        for (String p : orderPaths) {
            for (String t : ruleTexts(p)) {
                byText.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
            }
        }
        if (byText.isEmpty()) {
            return "";
        }
        if (byText.size() == 1
                && byText.values().iterator().next().size() == paths.size()) {
            // 全组命中同一套规则：按裸文本输出，与无标注形态字节一致
            return byText.keySet().iterator().next();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byText.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("<rules for=\"").append(String.join(", ", distinct(e.getValue()))).append("\">\n");
            sb.append(e.getKey());
            sb.append("\n</rules>");
        }
        return sb.toString();
    }

    private static List<String> distinct(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    static boolean matches(String glob, String path) {
        if (glob == null || path == null) {
            return false;
        }
        return compile(glob).matcher(path).matches();
    }

    private static final Map<String, Pattern> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern compile(String glob) {
        return CACHE.computeIfAbsent(glob, g -> Pattern.compile(toRegex(g)));
    }

    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        char[] c = glob.toCharArray();
        for (int i = 0; i < c.length; i++) {
            switch (c[i]) {
                case '*' -> {
                    if (i + 1 < c.length && c[i + 1] == '*') {
                        i++;
                        // `**/` → 任意目录前缀（含根级）；裸 `**` → 任意字符
                        if (i + 1 < c.length && c[i + 1] == '/') {
                            i++;
                            sb.append("(?:.*/)?");
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '\\' -> {
                    if (i + 1 < c.length) {
                        i++;
                        sb.append(Pattern.quote(String.valueOf(c[i])));
                    }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c[i])));
            }
        }
        return sb.toString();
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
