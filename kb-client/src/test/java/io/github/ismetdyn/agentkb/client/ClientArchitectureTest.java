package io.github.ismetdyn.agentkb.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.ismetdyn.agentkb.client.archfixture.TamboUiOutsideTui;
import io.github.ismetdyn.agentkb.client.tui.KbTuiCommand;
import org.junit.jupiter.api.Test;

/**
 * ADR-0025 decision 8: TamboUI is used only inside client.tui, so leaving it (0.x API) touches one
 * package. As in kb-server's ArchitectureRulesTest, the rule must also fail on a fixture under
 * {@code archfixture}, which DoNotIncludeTests keeps out of the production check.
 */
class ClientArchitectureTest {

    private static final String CLIENT = "io.github.ismetdyn.agentkb.client";
    private static final ArchRule TAMBOUI_ONLY_IN_TUI =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(CLIENT + ".tui..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("dev.tamboui..")
                    .because("ADR-0025 decision 8 keeps TamboUI inside client.tui");

    private static final JavaClasses PRODUCTION =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages(CLIENT);

    @Test
    void tamboui_is_used_only_in_client_tui() {
        TAMBOUI_ONLY_IN_TUI.check(PRODUCTION);
    }

    @Test
    void the_rule_fails_on_a_class_outside_client_tui_that_uses_tamboui() {
        JavaClasses fixture = new ClassFileImporter().importClasses(TamboUiOutsideTui.class);

        assertThatThrownBy(() -> TAMBOUI_ONLY_IN_TUI.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(TamboUiOutsideTui.class.getName());
    }

    /** Without the TUI in the import the rule would check nothing that uses TamboUI. */
    @Test
    void the_production_import_holds_the_tui_and_no_fixture() {
        assertThat(PRODUCTION.contain(KbTuiCommand.class)).isTrue();
        assertThat(PRODUCTION.contain(Kb.class)).isTrue();
        assertThat(PRODUCTION.stream().map(javaClass -> javaClass.getPackageName()))
                .noneMatch(name -> name.contains("archfixture"));
    }
}
