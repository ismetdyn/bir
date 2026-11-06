package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The accept of a {@code kind=schema} proposal writes one facts.yaml entry (ADR-0025 decision 12).
 * Lead decisions CP3 S6 and J3: the entry takes the indentation of the file, the new text is read
 * with the schema loader before it replaces facts.yaml and must hold exactly the one new key; any
 * other proposal is refused and leaves the file as it was, without a temp file. A key already there
 * with the same rules is a no-op, so an accept whose graph commit failed can be repeated.
 */
class SchemaAcceptTest {

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final String KEY = "rack_slot";

    @TempDir Path dir;

    private RepoSchema repo;
    private Path facts;

    @BeforeEach
    void copySeedSchema() throws IOException {
        Path schema = Files.createDirectories(dir.resolve("schema"));
        for (String file : new String[] {"types.yaml", "relations.yaml", "facts.yaml"}) {
            Files.copy(SEED_SCHEMA.resolve(file), schema.resolve(file));
        }
        repo = new RepoSchema(dir.toString());
        facts = schema.resolve("facts.yaml");
    }

    private Map<String, String> rulesOf(String key) {
        return repo.load().factKeys().stream()
                .filter(known -> known.key().equals(key))
                .map(FactKey::rules)
                .findFirst()
                .orElseThrow();
    }

    static Stream<Arguments> refusedProposals() {
        return Stream.of(
                arguments("unknown rule", KEY + ": {color: red}"),
                arguments("unknown validator", KEY + ": {validator: mac-address}"),
                arguments("two entries", KEY + ": {multi: false}\nshelf: {multi: true}"),
                arguments("nested value", KEY + ": {multi: {deep: true}}"),
                arguments("quoted } injection", KEY + ": {kind: \"text}, validator: ip\"}"),
                arguments(
                        "quoted line injection",
                        KEY + ": {kind: \"text}\\n  evil: {multi: true\"}"),
                arguments("existing key with other rules", "hostname: {multi: true}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedProposals")
    void a_proposal_that_is_no_new_entry_is_refused_and_leaves_the_file(String what, String content)
            throws IOException {
        String before = Files.readString(facts);

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class, () -> SchemaAccept.add(repo, content));

        assertThat(rejected).as(what).isNotNull();
        assertThat(rejected.hint()).as(what).isNotBlank();
        assertThat(Files.readString(facts)).as(what).isEqualTo(before);
        try (Stream<Path> files = Files.list(facts.getParent())) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .as(what)
                    .containsExactlyInAnyOrder("types.yaml", "relations.yaml", "facts.yaml");
        }
    }

    @Test
    void the_entry_takes_the_indentation_of_a_four_space_file() throws IOException {
        Files.writeString(facts, Files.readString(facts).replace("\n  ", "\n    "));

        SchemaAccept.add(repo, KEY + ": {validator: hostname, multi: false}");

        assertThat(Files.readString(facts)).contains("\n    " + KEY + ": {");
        assertThat(rulesOf(KEY)).isEqualTo(Map.of("validator", "hostname", "multi", "false"));
    }

    @Test
    void the_entry_is_written_after_the_last_entry_and_read_back_with_its_rules()
            throws IOException {
        String before = Files.readString(facts);

        SchemaAccept.add(repo, KEY + ": {enum: [a1, b2], multi: true}");

        assertThat(rulesOf(KEY)).isEqualTo(Map.of("enum", "a1, b2", "multi", "true"));
        assertThat(
                        Files.readString(facts)
                                .replace("  " + KEY + ": {enum: [a1, b2], multi: true}\n", ""))
                .as("only the one line is new")
                .isEqualTo(before);
    }

    /** A loader that does not read the new key back (here: a second facts block wins). */
    @Test
    void a_file_whose_loaded_schema_does_not_gain_the_key_is_left_as_it_was() throws IOException {
        Files.writeString(
                facts, Files.readString(facts) + "\nfacts:\n  hostname: {validator: hostname}\n");
        String before = Files.readString(facts);

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> SchemaAccept.add(repo, KEY + ": {multi: false}"));

        assertThat(rejected).isNotNull();
        assertThat(Files.readString(facts)).isEqualTo(before);
    }

    @Test
    void a_key_already_there_with_the_same_rules_is_left_alone() throws IOException {
        SchemaAccept.add(repo, KEY + ": {multi: false, validator: ip}");
        String once = Files.readString(facts);

        SchemaAccept.add(repo, KEY + ": {validator: ip, multi: false}");

        assertThat(Files.readString(facts)).isEqualTo(once);
    }
}
