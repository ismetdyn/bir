package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.ScannedFile;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * CP5 decision 1: every fact of one scan as the write path checks it (entity hint, key, env and
 * value) → the files that found it, built once per scan, so a file asks which of its values another
 * file still has (WritePolicy.closedByRescan) instead of walking every other file. Each distinct
 * fact is checked once, so SecretPatterns runs once per distinct value of an entity.
 */
final class ScanBacking {

    private record Backing(@Nullable String hint, String key, String env, String value) {}

    private record Unchecked(@Nullable String hint, FactInput input) {}

    private final Map<Backing, Set<String>> files = new HashMap<>();
    private final ScanWrites scan;

    ScanBacking(ScanWrites scan) {
        this.scan = scan;
        Map<Unchecked, Optional<CheckedFact>> checked = new HashMap<>();
        for (ScannedFile file : scan.scan.files()) {
            for (Found found : file.facts()) {
                checked.computeIfAbsent(
                                new Unchecked(found.entity(), input(scan.schema, found)),
                                this::check)
                        .ifPresent(
                                fact ->
                                        files.computeIfAbsent(
                                                        new Backing(
                                                                found.entity(),
                                                                fact.key(),
                                                                fact.env(),
                                                                fact.value()),
                                                        key -> new HashSet<>())
                                                .add(file.path()));
            }
        }
    }

    static FactInput input(Schema schema, Found found) {
        return new FactInput(
                found.key(), found.value(), schema.environment(found.env()), found.target());
    }

    /**
     * Whether a file of the scan other than {@code path} found {@code fact} (same key, env and
     * value) for the entity {@code hint} (null: the project).
     */
    boolean isElsewhere(@Nullable String hint, Fact fact, String path) {
        return files
                .getOrDefault(new Backing(hint, fact.key(), fact.env(), fact.value()), Set.of())
                .stream()
                .anyMatch(other -> !other.equals(path));
    }

    /**
     * The fact as the write path would write it for its entity; a refused fact backs nothing (its
     * own file skips and reports it). The type is left out: ScanFileWrite asks only for an entity
     * whose type the write path accepts.
     */
    private Optional<CheckedFact> check(Unchecked fact) {
        String entity = fact.hint() == null ? scan.projectId : fact.hint();
        WriteRequest request =
                new WriteRequest(
                        Source.SCAN,
                        entity,
                        null,
                        List.of(fact.input()),
                        List.of(),
                        null,
                        scan.batch,
                        scan.at);
        try {
            return Optional.of(WriteCheck.check(request, scan.schema).facts().getFirst());
        } catch (WriteRejectedException e) {
            return Optional.empty();
        }
    }
}
