package gate.adapters.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ReviewRules} 的 glob 语义与 fail-open 边界：规则路由是"确定性工程"反哺的一部分，
 * 匹配错误 = 审查标准错位，因此 SQL 通配的根级/嵌套命中、skip 声明、坏 JSON 静默退回都要钉死。
 */
class ReviewRulesGlobTest {

    @Test
    void doubleStarGlobMatchesRootAndNestedPaths() {
        ReviewRules rs = ReviewRules.parse(
                "{\"rules\":[{\"glob\":\"**/*.sql\",\"rule\":\"SQL\"}]}");
        assertTrue(rs.ruleTexts("V1.sql").contains("SQL"), "**/*.sql 命中根级文件");
        assertTrue(rs.ruleTexts("db/migrations/V1.sql").contains("SQL"), "**/*.sql 命中嵌套路径");
        assertFalse(rs.ruleTexts("src/A.java").contains("SQL"));
        assertEquals(List.of(), rs.ruleTexts("src/B.java"));
    }

    @Test
    void singleStarDoesNotCrossDirectorySeparator() {
        ReviewRules rs = ReviewRules.parse(
                "{\"rules\":[{\"glob\":\"src/*.java\",\"rule\":\"SRC\"}]}");
        assertTrue(rs.ruleTexts("src/A.java").contains("SRC"));
        assertFalse(rs.ruleTexts("src/inner/A.java").contains("SRC"), "* 不跨目录");
    }

    @Test
    void trailingDoubleStarMatchesEverythingBelow() {
        ReviewRules rs = ReviewRules.parse(
                "{\"rules\":[{\"glob\":\"src/generated/**\",\"skip\":true}]}");
        assertTrue(rs.skip("src/generated/Gen.java"));
        assertTrue(rs.skip("src/generated/a/b/Gen.java"));
        assertFalse(rs.skip("src/generated"), "目录本身不算内部文件");
        assertFalse(rs.skip("src/Gen.java"));
    }

    @Test
    void multipleMatchingRulesApplyInDeclarationOrder() {
        ReviewRules rs = ReviewRules.parse(
                "{\"rules\":[{\"glob\":\"**/*.sql\",\"rule\":\"A\"},{\"glob\":\"db/**\",\"rule\":\"B\"}]}");
        assertEquals(List.of("A", "B"), rs.ruleTexts("db/V1.sql"), "声明顺序即应用顺序");
    }

    @Test
    void malformedJsonFailsOpenToEmptyRules() {
        assertEquals(ReviewRules.EMPTY, ReviewRules.parse("not json at all"));
        assertEquals(ReviewRules.EMPTY, ReviewRules.parse("{\"rules\":\"not-an-array\"}"));
        assertEquals(ReviewRules.EMPTY, ReviewRules.parse("[]"));
        assertEquals(ReviewRules.EMPTY, ReviewRules.parse("{\"rules\":[{\"glob\":\"\"}]}"),
                "无 glob 的规则没有意义");
        assertTrue(ReviewRules.parse("{\"rules\":[{\"glob\":\"**/*.x\",\"skip\":true}]}").skip("a/b.x"),
                "纯 skip 规则（无文本）合法");
    }

    @Test
    void renderForGroupsByRuleSetAndTagsFiles() {
        ReviewRules rs = ReviewRules.parse(
                "{\"rules\":[{\"glob\":\"**/*.sql\",\"rule\":\"SQLRULE\"},{\"glob\":\"**/*.java\",\"rule\":\"JAVARULE\"}]}");
        String tagged = rs.renderFor(Set.of("db/V1.sql", "src/A.java"));
        assertTrue(tagged.contains("<rules for="), "不同规则集需要标注归属");
        assertTrue(tagged.contains("SQLRULE") && tagged.contains("JAVARULE"));

        String bare = rs.renderFor(Set.of("db/V1.sql", "db/V2.sql"));
        assertEquals("SQLRULE", bare, "全组同一规则集 → 裸文本，与旧形态字节一致");
    }

    @Test
    void renderForEmptyWhenNoRulesMatch() {
        assertEquals("", ReviewRules.EMPTY.renderFor(Set.of("src/A.java")));
    }
}
