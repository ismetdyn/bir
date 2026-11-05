package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.ScannedFile;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * SPEC §7 for one scan (ADR-0022, lead decision A): several values of a single-valued key for one
 * entity and env in one scan are reduced to one, the first by repo-relative path, then line. Every
 * file reporting that value keeps it, so each gets its FROM edge; the other values are dropped and
 * named by file:line in one {@link Dispute}. Without this each file replaced the value in turn and
 * every unchanged rescan added and closed it again.
 */
final class ScanSlots {

    private ScanSlots() {}

    /** One slot: the entity hint (null is the project), the fact key and the mapped env. */
    record Slot(@Nullable String entity, String key, String env) {}

    /** The slot, where its kept value was found first and where the other values are. */
    record Dispute(Slot slot, String keptPath, String keptAt, List<String> others) {}

    record Reduced(ScanWrite scan, List<Dispute> disputes) {}

    private record Seen(String path, int line, String value) {
        String at() {
            return path + ":" + line;
        }
    }

    static Reduced reduce(ScanWrite scan, Schema schema) {
        Map<Found, CheckedFact> single = new HashMap<>();
        Map<Slot, List<Seen>> slots = new LinkedHashMap<>();
        for (ScannedFile file : scan.files()) {
            for (Found found : file.facts()) {
                CheckedFact fact = singleValued(scan, schema, found);
                if (fact != null) {
                    single.put(found, fact);
                    slots.computeIfAbsent(slot(found, fact), slot -> new ArrayList<>())
                            .add(new Seen(file.path(), found.line(), fact.value()));
                }
            }
        }
        Map<Slot, String> kept = new HashMap<>();
        List<Dispute> disputes = new ArrayList<>();
        slots.forEach(
                (slot, seen) -> {
                    seen.sort(Comparator.comparing(Seen::path).thenComparingInt(Seen::line));
                    Seen first = seen.getFirst();
                    String value = first.value();
                    List<String> others =
                            seen.stream()
                                    .filter(other -> !other.value().equals(value))
                                    .map(Seen::at)
                                    .toList();
                    if (!others.isEmpty()) {
                        kept.put(slot, value);
                        disputes.add(new Dispute(slot, first.path(), first.at(), others));
                    }
                });
        if (disputes.isEmpty()) {
            return new Reduced(scan, List.of());
        }
        List<ScannedFile> files = new ArrayList<>();
        for (ScannedFile file : scan.files()) {
            List<Found> facts =
                    file.facts().stream()
                            .filter(found -> isKept(found, single.get(found), kept))
                            .toList();
            files.add(new ScannedFile(file.path(), facts, file.links(), file.secrets()));
        }
        ScanWrite reduced =
                new ScanWrite(
                        scan.project(),
                        scan.repo(),
                        scan.remote(),
                        scan.commit(),
                        files,
                        scan.skipped(),
                        scan.at());
        return new Reduced(reduced, disputes);
    }

    private static boolean isKept(Found found, @Nullable CheckedFact fact, Map<Slot, String> kept) {
        if (fact == null) {
            return true;
        }
        String value = kept.get(slot(found, fact));
        return value == null || value.equals(fact.value());
    }

    private static Slot slot(Found found, CheckedFact fact) {
        return new Slot(found.entity(), fact.key(), fact.env());
    }

    /**
     * {@code found} as the write path checks it, if its key is single-valued. A target makes it
     * part of the value (ADR-0022 decision 30), so such a fact never competes. A refused fact is
     * left to its file, which skips and reports it.
     */
    private static @Nullable CheckedFact singleValued(ScanWrite scan, Schema schema, Found found) {
        WriteRequest request =
                new WriteRequest(
                        Source.SCAN,
                        Objects.requireNonNullElse(found.entity(), scan.project()),
                        null,
                        List.of(
                                new FactInput(
                                        found.key(),
                                        found.value(),
                                        schema.environment(found.env()),
                                        found.target())),
                        List.of(),
                        null,
                        null,
                        scan.at());
        try {
            CheckedFact fact = WriteCheck.check(request, schema).facts().getFirst();
            return fact.multi() || fact.target() != null ? null : fact;
        } catch (WriteRejectedException e) {
            return null;
        }
    }
}
