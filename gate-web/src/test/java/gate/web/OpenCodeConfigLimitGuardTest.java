package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.service.OpenCodeConfigService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 模型节点 limit 完整性守卫（{@link OpenCodeConfigService} 写盘前的体检）。
 *
 * <p>opencode 的 schema 要求 limit 一旦出现就必须同时带正数的 context 与 output：缺一个时
 * opencode 拒绝整份配置，serve 启动即退出——本机所有会话一起失败，而错误文案只提端口。真实
 * 案例：模型编辑器只填上下文（1000000）写进 ttapi.qwen3.8-max，全部会话报
 * {@code opencode serve exited before becoming healthy (exit 1)}。
 */
class OpenCodeConfigLimitGuardTest {

    private Path configFile;
    private OpenCodeConfigService service;
    private String previousOverride;

    @BeforeEach
    void setUp() throws Exception {
        previousOverride = System.getProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY);
        configFile = Files.createTempDirectory("opencode-limit-guard-").resolve("opencode.jsonc");
        System.setProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY, configFile.toString());
        service = new OpenCodeConfigService();
    }

    @AfterEach
    void tearDown() {
        // 测试期间覆盖过配置路径，退出时必须还回去，否则后续测试会写进这份临时文件。
        if (previousOverride == null) {
            System.clearProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY);
        } else {
            System.setProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY, previousOverride);
        }
    }

    @Test
    void context_without_output_is_rejected_and_nothing_is_written() throws Exception {
        Files.writeString(configFile, "{\n  \"provider\" : { }\n}\n", StandardCharsets.UTF_8);
        String before = Files.readString(configFile, StandardCharsets.UTF_8);

        GateException e = assertThrows(GateException.class, () -> service.upsertProvider(
                "ttapi", provider("qwen3.8-max", modelWithLimit(1000000L, null))));

        assertEquals(GateErrorCode.USAGE, e.code());
        assertTrue(e.getMessage().contains("ttapi"), e.getMessage());
        assertTrue(e.getMessage().contains("qwen3.8-max"), e.getMessage());
        assertTrue(e.getMessage().contains("limit.output"), e.getMessage());
        assertEquals(before, Files.readString(configFile, StandardCharsets.UTF_8),
                "被拒的保存不得留下任何写入");
    }

    @Test
    void output_without_context_is_rejected() throws Exception {
        GateException e = assertThrows(GateException.class, () -> service.upsertProvider(
                "ttapi", provider("qwen3.8-max", modelWithLimit(null, 64000L))));
        assertTrue(e.getMessage().contains("limit.context"), e.getMessage());
    }

    @Test
    void non_numeric_limit_is_rejected() throws Exception {
        // 手写 JSON 的常见形状：数字写成了字符串，opencode 同样拒收整份配置。
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("limit", Map.of("context", "1000000", "output", 64000L));

        GateException e = assertThrows(GateException.class,
                () -> service.upsertProvider("ttapi", provider("qwen3.8-max", model)));
        assertTrue(e.getMessage().contains("positive integer"), e.getMessage());
    }

    @Test
    void paired_limits_are_written() throws Exception {
        service.upsertProvider("ttapi", provider("qwen3.8-max", modelWithLimit(1000000L, 64000L)));

        Map<?, ?> limit = limitOf(service, "ttapi", "qwen3.8-max");
        assertEquals(1000000L, ((Number) limit.get("context")).longValue());
        assertEquals(64000L, ((Number) limit.get("output")).longValue());
    }

    @Test
    void a_model_without_any_limit_is_accepted() throws Exception {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modalities", Map.of("input", List.of("text")));

        service.upsertProvider("ttapi", provider("qwen3.8-max", model));

        Map<?, ?> stored = modelOf(service, "ttapi", "qwen3.8-max");
        assertFalse(stored.containsKey("limit"), "不写 limit 是合法的：opencode 用它自己的默认");
    }

    /**
     * 只体检本次写入的节点，不做全文件校验——文件里既有的坏节点（手改的 / 旧版本写的）必须
     * 还能被删掉或改写救回来，否则用户只能自己去手改文件。
     */
    @Test
    void a_broken_neighbour_does_not_block_other_writes_or_deletes() throws Exception {
        Files.writeString(configFile, """
                {
                  "provider" : {
                    "legacy" : {
                      "models" : {
                        "broken" : { "limit" : { "context" : 1000000 } }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        service.upsertProvider("ttapi", provider("qwen3.8-max", modelWithLimit(1000000L, 64000L)));
        assertTrue(service.readProviders().containsKey("ttapi"));

        service.deleteProvider("legacy");
        assertFalse(service.readProviders().containsKey("legacy"),
                "坏节点所在的 provider 必须还能删——这是用户的救场路径");
    }

    // ---- helpers -------------------------------------------------------------

    /** 一个 provider 节点，内含单个模型。 */
    private static Map<String, Object> provider(String modelId, Map<String, Object> modelNode) {
        Map<String, Object> models = new LinkedHashMap<>();
        models.put(modelId, modelNode);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("name", "ttapi");
        provider.put("npm", "@ai-sdk/openai-compatible");
        provider.put("models", models);
        return provider;
    }

    /** 模型节点，limit 里按参数给键（null 表示不写该键）。 */
    private static Map<String, Object> modelWithLimit(Long context, Long output) {
        Map<String, Object> limit = new LinkedHashMap<>();
        if (context != null) {
            limit.put("context", context);
        }
        if (output != null) {
            limit.put("output", output);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("limit", limit);
        return model;
    }

    private static Map<?, ?> modelOf(OpenCodeConfigService service, String key, String modelId) {
        Map<?, ?> stored = (Map<?, ?>) service.readProviders().get(key);
        return (Map<?, ?>) ((Map<?, ?>) stored.get("models")).get(modelId);
    }

    private static Map<?, ?> limitOf(OpenCodeConfigService service, String key, String modelId) {
        return (Map<?, ?>) modelOf(service, key, modelId).get("limit");
    }
}