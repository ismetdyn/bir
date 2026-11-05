package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * todo.md G6 "Açık": the loader for {@code <repo>/schema/*.yaml}. CypherGuard takes its relation
 * allowlist from here (invariant 7), so the list is data from relations.yaml, not code. Expected
 * values are copied from seed/schema/relations.yaml by hand.
 */
class SchemaTest {

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> FILES = List.of("types.yaml", "relations.yaml", "facts.yaml");
    // seed/schema/relations.yaml keys, in graph case ("grafta BÜYÜK HARF olur").
    private static final List<String> SEED_RELATIONS =
            List.of(
                    "RUNS",
                    "DEPLOYED_ON",
                    "DEPENDS_ON",
                    "BEHIND",
                    "ROUTES_TO",
                    "PART_OF",
                    "AUTHENTICATES_WITH",
                    "OWNED_BY",
                    "RELATED");

    @TempDir Path dir;

    @Test
    void the_seed_relation_types_are_loaded_in_upper_case() {
        assertThat(Schema.load(SEED_SCHEMA).relationTypes())
                .containsExactlyInAnyOrderElementsOf(SEED_RELATIONS);
    }

    /** Synonyms are turned into their type when written, so no edge in the graph carries one. */
    @Test
    void synonyms_are_not_relation_types() {
        assertThat(Schema.load(SEED_SCHEMA).relationTypes())
                .doesNotContain("HOSTS", "USES", "RUNS_ON", "PROXIED_BY", "MEMBER_OF");
    }

    /** The allowlist follows the file: a type the user adds is allowed without a code change. */
    @Test
    void a_relation_added_to_the_file_becomes_a_relation_type() throws IOException {
        copySeedSchema();
        Path relations = dir.resolve("relations.yaml");
        Files.writeString(
                relations,
                Files.readString(relations)
                        + "  monitors:\n    description: \"Watches the health of\"\n");

        assertThat(Schema.load(dir).relationTypes())
                .contains("MONITORS")
                .containsAll(SEED_RELATIONS);
    }

    /** best-practices "Açılışta geçersiz config": refuse with a message that names the file. */
    @ParameterizedTest
    @ValueSource(strings = {"relations.yaml", "types.yaml", "facts.yaml"})
    void a_missing_schema_file_fails_naming_the_file(String missing) throws IOException {
        copySeedSchema();
        Files.delete(dir.resolve(missing));

        Throwable failure = catchThrowable(() -> Schema.load(dir));

        assertThat(failure).as("loaded without " + missing).isNotNull();
        assertThat(messages(failure)).contains(missing);
    }

    @Test
    void a_broken_relations_file_fails_naming_the_file() throws IOException {
        copySeedSchema();
        Files.writeString(dir.resolve("relations.yaml"), "relations: [unclosed\n  runs: {");

        Throwable failure = catchThrowable(() -> Schema.load(dir));

        assertThat(failure).as("loaded a broken file").isNotNull();
        assertThat(messages(failure)).contains("relations.yaml");
    }

    private void copySeedSchema() throws IOException {
        for (String file : FILES) {
            Files.copy(SEED_SCHEMA.resolve(file), dir.resolve(file));
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
