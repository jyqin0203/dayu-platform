package cn.edu.fudan.dayu.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 使用 ArchUnit 固定七个业务模块的依赖方向和分层边界。
 */
class ArchitectureTest {
    private static final String BASE = "cn.edu.fudan.dayu.";
    private static final Set<String> MODULES = Set.of(
            "catalog", "assetindex", "discovery", "identity", "download", "operations", "copilot");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "catalog", Set.of(),
            "identity", Set.of(),
            "assetindex", Set.of("catalog"),
            "discovery", Set.of("catalog", "assetindex"),
            "download", Set.of("assetindex"),
            "copilot", Set.of("catalog", "discovery"),
            "operations", Set.of("catalog", "assetindex", "discovery", "identity", "download"));

    private final JavaClasses classes = new ClassFileImporter().importPackages("cn.edu.fudan.dayu");

    /** 验证顶层包之间不存在循环依赖。 */
    @Test
    void topLevelPackagesAreFreeOfCycles() {
        slices().matching("cn.edu.fudan.dayu.(*)..").should().beFreeOfCycles().check(classes);
    }

    /** 验证跨业务模块只能依赖允许的目标模块 API 包。 */
    @Test
    void crossModuleDependenciesUseOnlyTargetApiAndFollowMatrix() {
        classes().that().resideInAnyPackage(MODULES.stream().map(m -> BASE + m + "..").toArray(String[]::new))
                .should(new ArchCondition<>("depend only on allowed module API packages") {
                    @Override
                    public void check(JavaClass source, ConditionEvents events) {
                        String sourceModule = moduleOf(source.getPackageName());
                        for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                            String targetPackage = dependency.getTargetClass().getPackageName();
                            if (!targetPackage.startsWith(BASE)) continue;

                            String targetTopLevel = topLevelOf(targetPackage);
                            if (targetTopLevel.equals(sourceModule) || targetTopLevel.equals("shared")) continue;

                            String targetModule = moduleOf(targetPackage);
                            boolean allowed = targetModule != null
                                    && ALLOWED.getOrDefault(sourceModule, Set.of()).contains(targetModule)
                                    && targetPackage.startsWith(BASE + targetModule + ".api");
                            if (!allowed) {
                                events.add(SimpleConditionEvent.violated(source,
                                        source.getName() + " has forbidden dependency on " + dependency.getTargetClass().getName()));
                            }
                        }
                    }
                }).check(classes);

        // Shared Kernel 是最底层公共概念，不能反向依赖任何业务模块。
        noClasses().that().resideInAPackage(BASE + "shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        MODULES.stream().map(m -> BASE + m + "..").toArray(String[]::new))
                .check(classes);
    }

    /** 验证领域层不依赖 Spring、数据库或 Redis 等具体框架。 */
    @Test
    void domainsRemainFrameworkIndependent() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..", "org.springframework..", "org.mybatis..", "org.apache.ibatis..",
                        "io.lettuce..", "redis.clients..");
        rule.check(classes);
    }

    /** 验证 REST 接入层不能越过 API 访问业务模块内部实现。 */
    @Test
    void restAdaptersCannotReachModuleInternals() {
        classes().that().resideInAPackage("..interfaces..")
                .should(new ArchCondition<>("access business modules through API packages") {
                    @Override
                    public void check(JavaClass source, ConditionEvents events) {
                        for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                            String targetPackage = dependency.getTargetClass().getPackageName();
                            String targetModule = moduleOf(targetPackage);
                            if (targetModule != null && !targetPackage.startsWith(BASE + targetModule + ".api")) {
                                events.add(SimpleConditionEvent.violated(source,
                                        source.getName() + " reaches module internal class "
                                                + dependency.getTargetClass().getName()));
                            }
                        }
                    }
                }).check(classes);
    }

    /** Copilot 禁止特权模块；模型适配器只实现本模块端口，不被应用层反向引用。 */
    @Test
    void copilotCannotDependOnDownloadOperationsOrInfrastructure() {
        noClasses().that().resideInAPackage("..copilot..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..download..", "..operations..")
                .check(classes);
        // 其他模块 infrastructure 仍由 crossModuleDependenciesUseOnlyTargetApiAndFollowMatrix 全面禁止。
        noClasses().that().resideInAPackage("..copilot.application..")
                .should().dependOnClassesThat().resideInAPackage("..copilot.infrastructure..")
                .check(classes);
    }

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(BASE)) return null;
        String candidate = topLevelOf(packageName);
        return MODULES.contains(candidate) ? candidate : null;
    }

    private static String topLevelOf(String packageName) {
        String remainder = packageName.substring(BASE.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }
}
