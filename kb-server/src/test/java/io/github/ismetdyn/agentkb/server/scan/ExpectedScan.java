package io.github.ismetdyn.agentkb.server.scan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * fixtures/scanner/expected.yaml (the referee file, read as it is), whole: every extractor of
 * ARCHITECTURE §6 (SpringConfig, DotEnv, BuildFile, Compose, K8s, Ansible) and the record derived
 * across files (the prod port of ornek-app-01, {@code target}: ornek-proje).
 */
final class ExpectedScan {

    static final JsonNode EXPECTED =
            YAMLMapper.builder()
                    .build()
                    .readTree(Path.of("..", "fixtures", "scanner", "expected.yaml").toFile());
    static final String REPO = EXPECTED.at("/repo/id").asString();
    static final String PROJECT = EXPECTED.at("/project/id").asString();
    static final String DEFAULT_ENV = "default";

    private ExpectedScan() {}

    /** {@code src/main/x.yml:10} → {@code src/main/x.yml}. */
    static String fileOf(String from) {
        return from.substring(0, from.lastIndexOf(':'));
    }

    static int lineOf(String from) {
        return Integer.parseInt(from.substring(from.lastIndexOf(':') + 1));
    }

    /** Project and entity facts as (entity id, fact). */
    static Stream<Arguments> facts() {
        List<Arguments> facts = new ArrayList<>();
        EXPECTED.at("/project/facts").forEach(fact -> facts.add(Arguments.of(PROJECT, fact)));
        EXPECTED.get("entities")
                .forEach(
                        entity ->
                                entity.get("facts")
                                        .forEach(
                                                fact ->
                                                        facts.add(
                                                                Arguments.of(
                                                                        entity.get("id").asString(),
                                                                        fact))));
        return facts.stream();
    }

    /** As (secret id, secret). */
    static Stream<Arguments> secrets() {
        return EXPECTED.at("/project/secrets")
                .valueStream()
                .map(secret -> Arguments.of(secret.get("id").asString(), secret));
    }

    /** As (type, target, env). */
    static Stream<Arguments> relations() {
        return EXPECTED.at("/project/relations")
                .valueStream()
                .map(
                        relation ->
                                Arguments.of(
                                        relation.get("type").asString(),
                                        relation.get("target").asString(),
                                        relation.path("env").asString(DEFAULT_ENV)));
    }

    /** As (id, type). */
    static Stream<Arguments> entities() {
        return EXPECTED.get("entities")
                .valueStream()
                .map(
                        entity ->
                                Arguments.of(
                                        entity.get("id").asString(),
                                        entity.get("type").asString()));
    }

    /**
     * A fact as {@code <entity> <key>@<env>[ -> <target>] <file>:<line>}, the shape the golden test
     * compares the whole graph in; the value is checked fact by fact.
     */
    static String shape(String entity, String key, String env, String target, String from) {
        return entity
                + " "
                + key
                + "@"
                + env
                + (target == null ? "" : " -> " + target)
                + " "
                + from;
    }

    /** Every expected fact in {@link #shape}. */
    static List<String> factShapes() {
        return facts().map(
                        args -> {
                            JsonNode fact = (JsonNode) args.get()[1];
                            return shape(
                                    (String) args.get()[0],
                                    fact.get("key").asString(),
                                    fact.path("env").asString(DEFAULT_ENV),
                                    fact.has("target") ? fact.get("target").asString() : null,
                                    fact.get("from").asString());
                        })
                .toList();
    }
}
