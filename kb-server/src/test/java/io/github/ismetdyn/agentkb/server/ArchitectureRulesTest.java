package io.github.ismetdyn.agentkb.server;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.ismetdyn.agentkb.api.archfixture.ApiPlainClass;
import io.github.ismetdyn.agentkb.api.archfixture.ApiRecord;
import io.github.ismetdyn.agentkb.api.archfixture.ApiRecordUsingServer;
import io.github.ismetdyn.agentkb.api.archfixture.ApiRecordUsingSpring;
import io.github.ismetdyn.agentkb.core.archfixture.CoreFixtures;
import io.github.ismetdyn.agentkb.server.api.archfixture.ApiControllers;
import io.github.ismetdyn.agentkb.server.docs.archfixture.DocsReachesApi;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.archfixture.GraphReachesHooks;
import io.github.ismetdyn.agentkb.server.mcp.archfixture.McpFixtures;
import io.github.ismetdyn.agentkb.server.scan.archfixture.ScanReachesMcp;
import io.github.ismetdyn.agentkb.server.signals.archfixture.SignalsReachesHooks;
import io.github.ismetdyn.agentkb.server.snapshot.archfixture.SnapshotReachesApi;
import io.github.ismetdyn.agentkb.server.snapshot.archfixture.SnapshotReachesWrite;
import io.github.ismetdyn.agentkb.server.write.SecretWrite;
import io.github.ismetdyn.agentkb.server.write.archfixture.WriteFixtures;
import io.github.ismetdyn.agentkb.server.write.archfixture.WriteUsesApiRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ARCHITECTURE §3 and ADR-0022 decision 2. A rule that matches nothing passes on clean code, so
 * each one also has to fail on the classes under an {@code archfixture} package. Those live in the
 * real package names because the rules select by package, and {@link
 * ImportOption.DoNotIncludeTests} keeps them out of the production check.
 */
class ArchitectureRulesTest {

    private static final String ROOT = "io.github.ismetdyn.agentkb";
    private static final String CORE = ROOT + ".core..";
    private static final String SERVER = ROOT + ".server.";
    private static final String GRAPH = SERVER + "graph..";
    private static final String WRITE = SERVER + "write..";
    private static final String MCP = SERVER + "mcp..";
    private static final String SECRETS = SERVER + "secrets..";
    // kb-api (ADR-0025 decision 6): the wire records kb and the server share.
    private static final String API = ROOT + ".api..";
    private static final String SERVER_API = SERVER + "api..";
    private static final String[] ENTRY = {MCP, SERVER + "api..", SERVER + "hooks.."};
    private static final String[] GRAPH_USERS = {
        WRITE,
        GRAPH,
        SERVER + "scan..",
        SERVER + "snapshot..",
        SERVER + "signals..",
        SERVER + "docs.."
    };
    // A compile error if the store is renamed; a rule on a stale name would otherwise go quiet.
    private static final String STORE = EmbeddedNeo4jStore.class.getSimpleName();
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages(ROOT);

    private static final ArchRule CORE_IS_FRAMEWORK_FREE =
            noClasses()
                    .that()
                    .resideInAPackage(CORE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "org.neo4j..");
    private static final ArchRule CORE_DOES_NOT_KNOW_THE_SERVER =
            noClasses()
                    .that()
                    .resideInAPackage(CORE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SERVER + ".");
    private static final ArchRule GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS =
            noClasses()
                    .that()
                    .resideInAnyPackage(GRAPH_USERS)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(ENTRY);
    // SecretWrite hands out the stored values for the checks that refuse them.
    private static final ArchRule MCP_DOES_NOT_REACH_SECRET_VALUES =
            noClasses()
                    .that()
                    .resideInAPackage(MCP)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SECRETS)
                    .orShould()
                    .dependOnClassesThat()
                    .belongToAnyOf(SecretWrite.class);
    private static final ArchRule STORE_WRITE_ONLY_FROM_WRITE_PACKAGE =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(WRITE)
                    .should()
                    .accessTargetWhere(storeMethod("write"));
    // database() hands out a writable handle, so leaving it open elsewhere would bypass the rule
    // above.
    private static final ArchRule STORE_DATABASE_ONLY_FROM_GRAPH_PACKAGE =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(GRAPH)
                    .should()
                    .accessTargetWhere(storeMethod("database"));

    // kb-api is records only, so it carries no behaviour the client and the server could disagree
    // on.
    private static final ArchRule API_HOLDS_ONLY_RECORDS =
            classes().that().resideInAPackage(API).should().beRecords();
    // JDK + JSpecify only: kb is a native image without Spring or Jackson (ADR-0025 decision 6).
    private static final ArchRule API_DEPENDS_ONLY_ON_THE_JDK =
            classes()
                    .that()
                    .resideInAPackage(API)
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "org.jspecify..", API);
    // server.api is the one place that maps domain results to kb-api records.
    private static final ArchRule API_ONLY_FROM_SERVER_API =
            noClasses()
                    .that()
                    .resideInAnyPackage(CORE, SERVER + ".")
                    .and()
                    .resideOutsideOfPackage(SERVER_API)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(API);
    // A domain record on the wire would make every domain change a protocol change. Checks the raw
    // return type only: ResponseEntity<DomainRecord> passes; the contract tests (ApiContractTest)
    // catch a wire shape that differs from the kb-api record.
    private static final ArchRule REST_ANSWERS_ARE_NOT_DOMAIN_TYPES =
            noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage(SERVER_API)
                    .and()
                    .areMetaAnnotatedWith(RequestMapping.class)
                    .should()
                    .haveRawReturnType(JavaClass.Predicates.resideInAnyPackage(CORE, SERVER + "."));

    // A cycle makes two packages one unit that can only change together.
    private static final ArchRule SERVER_PACKAGES_ARE_FREE_OF_CYCLES =
            slices().matching(SERVER + "(*)..").should().beFreeOfCycles();

    private static DescribedPredicate<JavaAccess<?>> storeMethod(String method) {
        return DescribedPredicate.describe(
                STORE + "." + method,
                access ->
                        access.getTarget().getName().equals(method)
                                && access.getTargetOwner().isAssignableTo(simpleName(STORE)));
    }

    private static Stream<ArchRule> everyRule() {
        return Stream.of(
                CORE_IS_FRAMEWORK_FREE,
                CORE_DOES_NOT_KNOW_THE_SERVER,
                GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS,
                MCP_DOES_NOT_REACH_SECRET_VALUES,
                STORE_WRITE_ONLY_FROM_WRITE_PACKAGE,
                STORE_DATABASE_ONLY_FROM_GRAPH_PACKAGE,
                SERVER_PACKAGES_ARE_FREE_OF_CYCLES,
                API_HOLDS_ONLY_RECORDS,
                API_DEPENDS_ONLY_ON_THE_JDK,
                API_ONLY_FROM_SERVER_API,
                REST_ANSWERS_ARE_NOT_DOMAIN_TYPES);
    }

    private static Stream<Arguments> violations() {
        return Stream.of(
                arguments(CORE_IS_FRAMEWORK_FREE, CoreFixtures.SpringField.class),
                arguments(CORE_IS_FRAMEWORK_FREE, CoreFixtures.SpringAnnotation.class),
                arguments(CORE_IS_FRAMEWORK_FREE, CoreFixtures.Neo4jField.class),
                arguments(CORE_DOES_NOT_KNOW_THE_SERVER, CoreFixtures.ServerField.class),
                arguments(
                        GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, WriteFixtures.WriteReachesMcp.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, GraphReachesHooks.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, ScanReachesMcp.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, SnapshotReachesApi.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, SignalsReachesHooks.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, DocsReachesApi.class),
                arguments(MCP_DOES_NOT_REACH_SECRET_VALUES, McpFixtures.UsesSecretService.class),
                arguments(MCP_DOES_NOT_REACH_SECRET_VALUES, McpFixtures.UsesSecretWrite.class),
                arguments(STORE_WRITE_ONLY_FROM_WRITE_PACKAGE, McpFixtures.CallsStoreWrite.class),
                arguments(
                        STORE_WRITE_ONLY_FROM_WRITE_PACKAGE,
                        McpFixtures.ReferencesStoreWrite.class),
                arguments(
                        STORE_WRITE_ONLY_FROM_WRITE_PACKAGE, McpFixtures.CallsSubclassWrite.class),
                arguments(
                        STORE_DATABASE_ONLY_FROM_GRAPH_PACKAGE,
                        McpFixtures.CallsStoreDatabase.class),
                arguments(API_HOLDS_ONLY_RECORDS, ApiPlainClass.class),
                arguments(API_DEPENDS_ONLY_ON_THE_JDK, ApiRecordUsingSpring.class),
                arguments(API_DEPENDS_ONLY_ON_THE_JDK, ApiRecordUsingServer.class),
                arguments(API_ONLY_FROM_SERVER_API, WriteUsesApiRecord.class),
                arguments(
                        REST_ANSWERS_ARE_NOT_DOMAIN_TYPES,
                        ApiControllers.ReturnsDomainRecord.class));
    }

    private static Stream<Arguments> permitted() {
        return Stream.of(
                arguments(STORE_WRITE_ONLY_FROM_WRITE_PACKAGE, WriteFixtures.CallsStoreWrite.class),
                arguments(
                        STORE_WRITE_ONLY_FROM_WRITE_PACKAGE, McpFixtures.CallsUnrelatedWrite.class),
                arguments(GRAPH_USERS_DO_NOT_KNOW_ENTRY_POINTS, McpFixtures.McpEntry.class),
                arguments(API_HOLDS_ONLY_RECORDS, ApiRecord.class),
                arguments(API_DEPENDS_ONLY_ON_THE_JDK, ApiRecord.class),
                arguments(API_ONLY_FROM_SERVER_API, ApiControllers.UsesApiRecord.class),
                arguments(REST_ANSWERS_ARE_NOT_DOMAIN_TYPES, ApiControllers.UsesApiRecord.class));
    }

    @ParameterizedTest
    @MethodSource("everyRule")
    void production_code_follows_the_rule(ArchRule rule) {
        rule.check(PRODUCTION);
    }

    @ParameterizedTest
    @MethodSource("violations")
    void the_rule_fails_on_a_violating_class(ArchRule rule, Class<?> violator) {
        JavaClasses classes = new ClassFileImporter().importClasses(violator);

        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violator.getName());
    }

    @ParameterizedTest
    @MethodSource("permitted")
    void the_rule_does_not_flag_what_it_permits(ArchRule rule, Class<?> permittedClass) {
        rule.allowEmptyShould(true).check(new ClassFileImporter().importClasses(permittedClass));
    }

    /** A cycle needs both of its halves in the import, unlike the single-class violations above. */
    @Test
    void the_cycle_rule_fails_on_two_packages_that_reach_each_other() {
        JavaClasses classes =
                new ClassFileImporter()
                        .importClasses(
                                WriteFixtures.WriteReachesSnapshot.class,
                                SnapshotReachesWrite.class);

        assertThatThrownBy(() -> SERVER_PACKAGES_ARE_FREE_OF_CYCLES.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(WriteFixtures.WriteReachesSnapshot.class.getName())
                .hasMessageContaining(SnapshotReachesWrite.class.getName());
    }

    @Test
    void the_cycle_rule_does_not_flag_a_one_way_dependency() {
        JavaClasses classes =
                new ClassFileImporter()
                        .importClasses(
                                WriteFixtures.WriteReachesMcp.class, McpFixtures.McpEntry.class);

        SERVER_PACKAGES_ARE_FREE_OF_CYCLES.check(classes);
    }

    /**
     * Without this the production check could pass over an import that missed kb-core or kb-api, or
     * swallowed the fixtures.
     */
    @Test
    void the_production_import_holds_every_module_and_no_fixture() {
        assertThat(PRODUCTION.containPackage(ROOT + ".core")).isTrue();
        // Without kb-api on the server's class path the kb-api rules above would check nothing.
        assertThat(PRODUCTION.containPackage(ROOT + ".api")).isTrue();
        assertThat(PRODUCTION.contain(EmbeddedNeo4jStore.class)).isTrue();
        assertThat(PRODUCTION.stream().map(javaClass -> javaClass.getPackageName()))
                .noneMatch(name -> name.contains("archfixture"));
    }
}
