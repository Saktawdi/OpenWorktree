package gate.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit enforcement of the hexagonal dependency rules (架构落地执行文档 §4.2).
 *
 * <p>These are not documentation — they are the mechanism that keeps the fail-closed and
 * "swap-the-engine-for-free" designs true over time. If {@code domain} ever imports Spring, or
 * {@code application} ever reaches into a concrete adapter, that is a regression the type system
 * cannot catch, so it is caught here.
 */
class ArchitectureTest {

    private static final JavaClasses GATE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("gate");

    @Test
    void domain_depends_only_on_jdk() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "org.eclipse.jgit..",
                        "java.sql..",
                        "javax.sql..",
                        "org.flywaydb..",
                        "org.sqlite..",
                        "gate.ports..",
                        "gate.application..",
                        "gate.adapters..",
                        "gate.cli..")
                .because("domain is pure JDK: no Spring, no JGit, no java.sql, and no outward layer (§4.2)");
        rule.check(GATE);
    }

    @Test
    void application_does_not_depend_on_any_adapter() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.application..")
                .should().dependOnClassesThat().resideInAnyPackage("gate.adapters..", "gate.cli..")
                .because("application depends on domain + ports only, never on a concrete adapter (§4.2)");
        rule.check(GATE);
    }

    @Test
    void application_is_free_of_spring() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.application..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("application carries zero Spring: DI is a wiring detail confined to adapters/cli (ADR-8)");
        rule.check(GATE);
    }

    @Test
    void ports_depend_only_on_domain() {
        ArchRule rule = noClasses().that().resideInAPackage("gate.ports..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "gate.application..", "gate.adapters..", "gate.cli..",
                        "org.springframework..", "org.eclipse.jgit..")
                .because("ports are interfaces over domain types only (§4.2)");
        rule.check(GATE);
    }

    @Test
    void adapters_do_not_depend_on_each_other() {
        // Each adapter technology package must not reach into a sibling; they interact only through
        // ports. git / store / hook / approval / audit / blob / lock / clock / engine / preflight /
        // process / config are the sibling packages.
        String[] siblings = {
                "gate.adapters.git", "gate.adapters.store", "gate.adapters.hook", "gate.adapters.approval",
                "gate.adapters.audit", "gate.adapters.blob", "gate.adapters.lock", "gate.adapters.clock",
                "gate.adapters.engine", "gate.adapters.process", "gate.adapters.config"
        };
        for (String self : siblings) {
            String[] others = java.util.Arrays.stream(siblings)
                    .filter(s -> !s.equals(self))
                    // preflight legitimately composes git+approval+hook, and io is shared util; those
                    // are not in the sibling list. Everything here is a leaf technology adapter.
                    .map(s -> s + "..")
                    .toArray(String[]::new);
            ArchRule rule = noClasses().that().resideInAPackage(self + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .because(self + " must interact with other adapters only through ports (§4.2)");
            rule.check(GATE);
        }
    }

    @Test
    void nothing_depends_on_cli() {
        ArchRule rule = noClasses().that().resideInAnyPackage(
                        "gate.domain..", "gate.ports..", "gate.application..", "gate.adapters..")
                .should().dependOnClassesThat().resideInAPackage("gate.cli..")
                .because("cli is a driver adapter; no inner layer may depend on it (§4.2)");
        rule.check(GATE);
    }

    @Test
    void spring_only_in_adapters_and_cli() {
        // GOV-BOOT-001: bootstrap may use Spring internally (unique composition root) and web is a driver;
        // the invariant is that bootstrap does NOT expose framework types (see bootstrap_does_not_expose_spring_types).
        ArchRule rule = noClasses().that().resideOutsideOfPackages(
                        "gate.adapters..", "gate.cli..", "gate.bootstrap..", "gate.web..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("Spring usage is confined to adapters, bootstrap (internal), cli and web drivers (production-architecture §5, GOV-BOOT-001)");
        rule.check(GATE);
    }

    @Test
    void bootstrap_does_not_expose_spring_types() {
        // GOV-BOOT-001: bootstrap may use Spring internally but must not expose framework types in public API.
        // Lightweight check: ensure GateRuntime has no public method/field returning Spring type.
        com.tngtech.archunit.core.domain.JavaClass gateRuntime = GATE.get(gate.bootstrap.GateRuntime.class);
        gateRuntime.getMethods().stream()
                .filter(m -> m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
                .forEach(m -> {
                    String ret = m.getRawReturnType().getName();
                    if (ret.startsWith("org.springframework.")) {
                        throw new AssertionError("GOV-BOOT-001 violation: GateRuntime public method " + m.getName() + " exposes Spring type " + ret);
                    }
                    m.getRawParameterTypes().forEach(p -> {
                        if (p.getName().startsWith("org.springframework.")) {
                            throw new AssertionError("GOV-BOOT-001 violation: GateRuntime public method " + m.getName() + " has Spring parameter " + p.getName());
                        }
                    });
                });
        gateRuntime.getFields().stream()
                .filter(f -> f.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
                .forEach(f -> {
                    if (f.getRawType().getName().startsWith("org.springframework.")) {
                        throw new AssertionError("GOV-BOOT-001 violation: GateRuntime public field " + f.getName() + " exposes Spring type " + f.getRawType().getName());
                    }
                });
    }
}
