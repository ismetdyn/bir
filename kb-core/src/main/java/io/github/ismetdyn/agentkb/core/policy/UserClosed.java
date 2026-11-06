package io.github.ismetdyn.agentkb.core.policy;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Conflict;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.util.List;
import java.util.Optional;

/**
 * ADR-0025 decision 16 (tombstone) for {@link WritePolicy#decide}: a value a user edit closed is
 * not made current by a lower source than the user. It waits as a conflict for the user or, already
 * waiting, is only seen again. Only the user opens it again, through the other rules; from then on
 * that close no longer counts (lead decision CP3 J5). A hostname matches its tombstone in any case
 * (RFC 4343, lead decision CP3 S5).
 */
final class UserClosed {

    private UserClosed() {}

    /**
     * @param open the open facts of the incoming fact's slot
     * @param current the facts of the record; those a user edit closed are tombstones, and a later
     *     fact of the same value the user opened lifts one
     * @param caseInsensitive the values compare in any case (WritePolicy#decide)
     * @return empty when the rule does not apply
     */
    static Optional<Decision> kept(
            Fact fact, List<Sourced> open, List<Sourced> current, boolean caseInsensitive) {
        if (fact.source() == Source.USER) {
            return Optional.empty();
        }
        return current.stream()
                .filter(Sourced::closedByUser)
                .map(Sourced::fact)
                .filter(
                        closed ->
                                isSameSlot(closed, fact)
                                        && sameValue(closed, fact, caseInsensitive)
                                        && !reopenedByUser(closed, current, caseInsensitive))
                .findFirst()
                .map(closed -> waitingOrConflict(closed, fact, open));
    }

    /** A user fact of the closed value that began at or after the close. */
    private static boolean reopenedByUser(
            Fact closed, List<Sourced> current, boolean caseInsensitive) {
        return current.stream()
                .map(Sourced::fact)
                .anyMatch(
                        other ->
                                other.source() == Source.USER
                                        && !other.id().equals(closed.id())
                                        && isSameSlot(other, closed)
                                        && sameValue(other, closed, caseInsensitive)
                                        && !other.validFrom().isBefore(closed.validTo()));
    }

    private static boolean sameValue(Fact a, Fact b, boolean caseInsensitive) {
        return caseInsensitive
                ? a.value().equalsIgnoreCase(b.value())
                : a.value().equals(b.value());
    }

    private static Decision waitingOrConflict(Fact closed, Fact fact, List<Sourced> open) {
        Optional<Fact> waiting = WritePolicy.withSameValue(open, fact);
        if (waiting.isPresent()) {
            return new Refresh(waiting.get().seenAt(fact.observedAt()));
        }
        return new Conflict(fact.withStatus(FactStatus.CONFLICT), closed);
    }

    static boolean isSameSlot(Fact other, Fact fact) {
        return other.key().equals(fact.key()) && other.env().equals(fact.env());
    }
}
