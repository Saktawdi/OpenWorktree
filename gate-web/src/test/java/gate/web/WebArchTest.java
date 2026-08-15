package gate.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit enforcement for gate-web (执行文档-后端-web §2.1).
 *
 * <p>gate-web is a driver adapter on the same level as gate-cli. The load-bearing rule: {@code
 * gate.web} must NOT depend on {@code gate.cli} (the two are peers; a dependency would invert the
 * hexagon). gate-cli classes are not even on gate-web's classpath, so this also fails loudly if
 * someone adds the dependency by mistake.
 */
class WebArchTest {

    private static final JavaClasses WEB = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("gate.web");

    @Test
    void web_does_not_depend_on_cli() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.web..")
                .should().dependOnClassesThat().resideInAPackage("gate.cli..")
                .because("gate-web and gate-cli are peer driver adapters; web must never depend on cli (§2.1)");
        rule.check(WEB);
    }

    @Test
    void web_does_not_pull_jgit() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.web..")
                .should().dependOnClassesThat().resideInAPackage("org.eclipse.jgit..")
                .because("the authoritative git path is the real git binary (ADR-1); web adds no JGit");
        rule.check(WEB);
    }
}
