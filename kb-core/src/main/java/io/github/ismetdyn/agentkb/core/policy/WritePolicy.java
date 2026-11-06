package io.github.ismetdyn.agentkb.core.policy;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * SPEC §7 rules 1 to 5, CLAUDE.md invariants 5 and 6, ADR-0022 decisions 22 and 23 and ADR-0025
 * decision 16. Decides only; the caller does the write. An existing fact never changes in place: it
 * only gets a later observed_at or is closed, never before its valid_from. Anything else is a new
 * fact, so {@code kb revert --batch} undoes a batch by deleting what it opened and reopening what
 * it closed.
 */
public final class WritePolicy {

    // The scanner's plain-text password key (seed/schema/facts.yaml, multi-valued).
    private static final String FINDING = "finding";

    private WritePolicy() {}

    /**
     * A fact and, for scanned ones, the repo and file it came from (SPEC §7 rule 4). {@code
     * closedByUser}: a user edit closed it (closed_batch, ADR-0025 decision 16; UserClosed).
     */
    public record Sourced(Fact fact, @Nullable String sourceFile, boolean closedByUser) {

        public Sourced(Fact fact, @Nullable String sourceFile) {
            this(fact, sourceFile, false);
        }
    }

    public sealed interface Decision {}

    /** Only observed_at moved forward. */
    public record Refresh(Fact refreshed) implements Decision {}

    public record Add(Fact added) implements Decision {}

    /** The caller links {@code added} to {@code closed} with SUPERSEDES. */
    public record CloseAndAdd(Fact closed, Fact added) implements Decision {}

    /**
     * {@code current} is the value it disputes: the current one, or the value the user closed when
     * that is why it waits (ADR-0025 decision 16).
     */
    public record Conflict(Fact added, Fact current) implements Decision {}

    /**
     * A waiting conflict value wins over the current value: both close and one new current fact
     * opens. The caller links {@code added} to both closed facts with SUPERSEDES.
     */
    public record Resolve(Fact closedWaiting, Fact closedCurrent, Fact added) implements Decision {}

    /** {@link #decide(Sourced, List, boolean, boolean)} for a key whose values compare exactly. */
    public static Decision decide(Sourced incoming, List<Sourced> current, boolean multi) {
        return decide(incoming, current, multi, false);
    }

    /**
     * @param current facts of the record; those of another key or env are ignored, and closed ones
     *     unless a user edit closed them or the user opened them (UserClosed)
     * @param multi {@code multi} of this key in seed/schema/facts.yaml
     * @param caseInsensitive the key's values name the same thing in any case (a hostname, RFC
     *     4343); only the tombstone looks at it (UserClosed), every other rule compares exactly
     * @throws IllegalArgumentException if the incoming source is the curator, which produces no new
     *     information (SPEC §7)
     */
    public static Decision decide(
            Sourced incoming, List<Sourced> current, boolean multi, boolean caseInsensitive) {
        Fact fact = incoming.fact();
        if (fact.source() == Source.CURATOR) {
            throw new IllegalArgumentException("the curator cannot write a fact value");
        }
        List<Sourced> open =
                current.stream().filter(other -> isOpenInSameSlot(other.fact(), fact)).toList();
        // A conflict fact has no valid_to but is not the current value (rule 3).
        List<Sourced> holders =
                open.stream()
                        .filter(other -> other.fact().status() != FactStatus.CONFLICT)
                        .toList();
        // A current value is confirmed; a value the user closed is not reopened by a lower source.
        Optional<Decision> settled =
                withSameValue(holders, fact)
                        .map(existing -> confirm(existing, fact))
                        .or(() -> UserClosed.kept(fact, open, current, caseInsensitive));
        if (settled.isPresent()) {
            return settled.get();
        }
        var waitingSameValue = withSameValue(open, fact);
        if (waitingSameValue.isPresent()) {
            return resolveWaiting(waitingSameValue.get(), fact, holders, multi);
        }
        // On a multi-valued key a rescan closes nothing here; see closedByRescan.
        if (multi || holders.isEmpty()) {
            return new Add(fact.withStatus(statusOf(fact.source())));
        }
        // A rescanned holder has the incoming source (both scan), so the trust check passes for it.
        Fact holder =
                holders.stream()
                        .filter(other -> isRescan(incoming, other))
                        .map(Sourced::fact)
                        .findFirst()
                        .orElseGet(() -> strongest(holders));
        if (fact.source().trust() < holder.source().trust()) {
            return new Conflict(fact.withStatus(FactStatus.CONFLICT), holder);
        }
        return new CloseAndAdd(
                closedAt(holder, fact.validFrom()), fact.withStatus(statusOf(fact.source())));
    }

    /**
     * SPEC §7 rules 4 and 5 for a whole file. {@link #decide} sees one fact at a time and cannot
     * tell a sibling value of the same scan from a value that left the file, so on a multi-valued
     * key it only adds. After every fact of one scan of {@code sourceFile} was decided and written,
     * the scanner calls this once per record.
     *
     * <p>A finding is the exception (docs/SECRETS.md): a scan reports every plain-text password of
     * the file, so a finding the scan no longer has closes even in a key and env the scan did not
     * report, also when {@code scanned} is empty (the file is gone or nothing was extracted).
     *
     * <p>One value can come from several files (one fact, a FROM edge to each). The same scan may
     * read the file that still has it before or after this one, so a value another file of the scan
     * reports does not close here, whatever the file order.
     *
     * @param scanned the facts of this record that this scan of the file produced
     * @param backedElsewhere the facts of this record that the other files of the same scan report
     * @param current the facts of the record as they are after those writes
     * @return the open scan facts of that file, in a key and env the scan reported, whose value
     *     neither the file nor another file of the scan still has, closed at {@code scannedAt}. A
     *     waiting conflict from the file counts too, so a stale one does not stay open. A key and
     *     env the scan did not report stays, except a finding.
     */
    public static List<Fact> closedByRescan(
            String sourceFile,
            List<Fact> scanned,
            List<Fact> backedElsewhere,
            Instant scannedAt,
            List<Sourced> current) {
        return current.stream()
                .filter(
                        other ->
                                other.fact().source() == Source.SCAN
                                        && sourceFile.equals(other.sourceFile()))
                .map(Sourced::fact)
                .filter(other -> other.validTo() == null)
                .filter(
                        other ->
                                FINDING.equals(other.key())
                                        || scanned.stream()
                                                .anyMatch(seen -> isOpenInSameSlot(other, seen)))
                .filter(
                        other ->
                                Stream.concat(scanned.stream(), backedElsewhere.stream())
                                        .noneMatch(
                                                seen ->
                                                        seen.value().equals(other.value())
                                                                && isOpenInSameSlot(other, seen)))
                .map(other -> closedAt(other, scannedAt))
                .toList();
    }

    static Optional<Fact> withSameValue(List<Sourced> facts, Fact fact) {
        return facts.stream()
                .map(Sourced::fact)
                .filter(other -> other.value().equals(fact.value()))
                .findFirst();
    }

    // More than one holder cannot come from this policy, but import is lossless (invariant 11).
    private static Fact strongest(List<Sourced> holders) {
        return holders.stream()
                .map(Sourced::fact)
                .max(Comparator.comparingInt(other -> other.source().trust()))
                .orElseThrow();
    }

    /** Rule 1: a higher source raises the value by opening a new version (decision 22). */
    private static Decision confirm(Fact existing, Fact fact) {
        if (fact.source().trust() <= existing.source().trust()) {
            return new Refresh(existing.seenAt(fact.observedAt()));
        }
        return new CloseAndAdd(
                closedAt(existing, fact.validFrom()), newVersion(fact, existing, fact.source()));
    }

    /**
     * Rule 1, decision 23. A multi-valued key has no single current value to compare with or to
     * close (rule 5), and with no current value nothing is left to dispute: only the waiting fact
     * closes.
     */
    private static Decision resolveWaiting(
            Fact waiting, Fact fact, List<Sourced> holders, boolean multi) {
        // Only an import can leave a waiting fact above the write that resolves it (invariant 6).
        Source source =
                fact.source().trust() > waiting.source().trust() ? fact.source() : waiting.source();
        Fact added = newVersion(fact, waiting, source);
        Fact closedWaiting = closedAt(waiting, fact.validFrom());
        if (multi || holders.isEmpty()) {
            return new CloseAndAdd(closedWaiting, added);
        }
        Fact holder = strongest(holders);
        return fact.source().trust() >= holder.source().trust()
                ? new Resolve(closedWaiting, closedAt(holder, fact.validFrom()), added)
                : new Refresh(waiting.seenAt(fact.observedAt()));
    }

    private static boolean isOpenInSameSlot(Fact other, Fact fact) {
        return other.validTo() == null && UserClosed.isSameSlot(other, fact);
    }

    private static boolean isRescan(Sourced incoming, Sourced other) {
        return incoming.fact().source() == Source.SCAN
                && other.fact().source() == Source.SCAN
                && incoming.sourceFile() != null
                && incoming.sourceFile().equals(other.sourceFile());
    }

    /**
     * The same value as {@code replaced} in a new fact of this write (its id, batch and evidence).
     * valid_from is the first sighting of the value and is carried over (invariant 5).
     */
    private static Fact newVersion(Fact fact, Fact replaced, Source source) {
        Instant observedAt =
                fact.observedAt().isAfter(replaced.observedAt())
                        ? fact.observedAt()
                        : replaced.observedAt();
        return new Fact(
                fact.id(),
                fact.key(),
                fact.value(),
                fact.env(),
                source,
                replaced.validFrom(),
                observedAt,
                null,
                statusOf(source),
                fact.evidence(),
                fact.batch());
    }

    /** A write or scan time before the fact began (late or skewed input) ends it at valid_from. */
    private static Fact closedAt(Fact fact, Instant at) {
        return fact.withValidTo(at.isBefore(fact.validFrom()) ? fact.validFrom() : at);
    }

    private static FactStatus statusOf(Source source) {
        return source == Source.AGENT ? FactStatus.UNVERIFIED : FactStatus.OK;
    }
}
