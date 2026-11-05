package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;

/**
 * Applies a WritePolicy decision to the graph (SPEC §7 rules 1-3, ADR-0022 decisions 22-23). A fact
 * node only ever gets a later observed_at or a valid_to; every other change is a new fact linked to
 * what it replaces with SUPERSEDES.
 */
final class FactWrites {

    private static final String FACT = "Fact";
    private static final RelationshipType HAS_FACT = RelationshipType.withName("HAS_FACT");
    private static final RelationshipType TARGET = RelationshipType.withName("TARGET");
    private static final String SUPERSEDES = "SUPERSEDES";
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String ENV = "env";
    private static final String SOURCE = "source";
    private static final String VALID_FROM = "valid_from";
    private static final String OBSERVED_AT = "observed_at";
    private static final String VALID_TO = "valid_to";
    private static final String STATUS = "status";
    private static final String EVIDENCE = "evidence";

    private final GraphWrite graph;
    private final ConflictSignals signals;
    final List<String> added = new ArrayList<>();
    final List<String> refreshed = new ArrayList<>();
    final List<String> closed = new ArrayList<>();
    final List<String> conflicts = new ArrayList<>();

    FactWrites(GraphWrite graph) {
        this.graph = graph;
        this.signals = new ConflictSignals(graph);
    }

    /**
     * With a target, the target is part of the value (ADR-0022 decision 30): only the open facts of
     * the same value compete, as one slot, and one that points elsewhere counts as another value.
     * Without one, the value alone decides, but it confirms no target: a raise of a fact whose
     * target a lower source set is only a refresh, so the target keeps the trust of who set it.
     *
     * @return the fact node that holds the incoming value after the write
     */
    Node write(Node entity, CheckedFact input, Fact incoming, @Nullable Node target) {
        Map<String, Node> open = openFacts(entity, input);
        if (target != null) {
            open.values().removeIf(node -> !input.value().equals(node.getProperty(VALUE)));
        }
        List<Sourced> current = new ArrayList<>();
        open.values().forEach(node -> current.add(new Sourced(fact(node, target), null)));
        boolean multi = input.multi() && target == null;
        return switch (WritePolicy.decide(new Sourced(incoming, null), current, multi)) {
            case WritePolicy.Refresh refresh -> refresh(entity, open, refresh.refreshed());
            case WritePolicy.Add add -> create(entity, add.added(), target, null);
            case WritePolicy.CloseAndAdd change
                    when liftsUnconfirmedTarget(target, open.get(change.closed().id()), change) ->
                    refresh(entity, open, observed(change.closed(), incoming));
            case WritePolicy.CloseAndAdd change -> {
                Node old = close(open, change.closed());
                Node added = create(entity, change.added(), target, old);
                graph.edge(added, SUPERSEDES, old, Map.of());
                yield added;
            }
            case WritePolicy.Conflict conflict -> {
                Node added = create(entity, conflict.added(), target, null);
                conflicts.add(conflict.added().id());
                signals.raise(entity, conflict.added(), conflict.current());
                yield added;
            }
            case WritePolicy.Resolve resolve -> {
                Node waiting = close(open, resolve.closedWaiting());
                Node holder = close(open, resolve.closedCurrent());
                Node resolved = create(entity, resolve.added(), target, waiting);
                graph.edge(resolved, SUPERSEDES, waiting, Map.of());
                graph.edge(resolved, SUPERSEDES, holder, Map.of());
                yield resolved;
            }
        };
    }

    /** Open facts of the entity in the key and env of {@code input}, by id. */
    private static Map<String, Node> openFacts(Node entity, CheckedFact input) {
        Map<String, Node> open = new HashMap<>();
        try (ResourceIterable<Relationship> edges =
                entity.getRelationships(Direction.OUTGOING, HAS_FACT)) {
            for (Relationship edge : edges) {
                Node node = edge.getEndNode();
                if (!node.hasProperty(VALID_TO)
                        && input.key().equals(node.getProperty(KEY, null))
                        && input.env().equals(node.getProperty(ENV, Fact.DEFAULT_ENV))) {
                    open.put((String) node.getProperty(GraphWrite.ID), node);
                }
            }
        }
        return open;
    }

    /**
     * Only observed_at moves (decision 10: no batch); a waiting value seen again counts. A named
     * target is never lost here: another target is another value (see write).
     */
    private Node refresh(Node entity, Map<String, Node> open, Fact fact) {
        Node node = open.get(fact.id());
        String observedAt = Times.format(fact.observedAt());
        if (!observedAt.equals(node.getProperty(OBSERVED_AT))) {
            graph.set(node, OBSERVED_AT, observedAt);
        }
        refreshed.add(fact.id());
        if (fact.status() == FactStatus.CONFLICT) {
            conflicts.add(fact.id());
            signals.raise(entity, fact, null);
        }
        return node;
    }

    /** ADR-0022 decision 30: a write without a target confirms the value, not the target. */
    private static boolean liftsUnconfirmedTarget(
            @Nullable Node target, Node replaced, WritePolicy.CloseAndAdd change) {
        return target == null
                && change.added().value().equals(change.closed().value())
                && replaced.hasRelationship(Direction.OUTGOING, TARGET)
                && change.closed().source().trust() < change.added().source().trust();
    }

    /** The kept fact with observed_at moved to the write if later; refresh reads no valid_to. */
    private static Fact observed(Fact kept, Fact incoming) {
        return incoming.observedAt().isAfter(kept.observedAt())
                ? kept.withObservedAt(incoming.observedAt())
                : kept;
    }

    private Node close(Map<String, Node> open, Fact fact) {
        Node node = open.get(fact.id());
        graph.set(node, VALID_TO, Times.format(fact.validTo()));
        closed.add(fact.id());
        return node;
    }

    /**
     * A new version of the same value keeps the TARGET of the fact it replaces when the write names
     * none, so raising a port does not drop where it points. Only an equal or higher source gets
     * here with one (see liftsUnconfirmedTarget).
     */
    private Node create(Node entity, Fact fact, @Nullable Node target, @Nullable Node replaced) {
        Node node = graph.node(fact.id(), props(fact), FACT);
        graph.edge(entity, HAS_FACT.name(), node, Map.of());
        Node pointsTo = target;
        if (pointsTo == null
                && replaced != null
                && fact.value().equals(replaced.getProperty(VALUE))) {
            Relationship old = replaced.getSingleRelationship(TARGET, Direction.OUTGOING);
            pointsTo = old == null ? null : old.getEndNode();
        }
        if (pointsTo != null) {
            graph.edge(node, TARGET.name(), pointsTo, Map.of());
        }
        added.add(fact.id());
        return node;
    }

    /** The seed form of a fact (SPEC §6.1); id, batch and updated_at come from GraphWrite. */
    private static Map<String, Object> props(Fact fact) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(KEY, fact.key());
        props.put(VALUE, fact.value());
        props.put(ENV, fact.env());
        props.put(SOURCE, Ids.normalize(fact.source().name()));
        props.put(VALID_FROM, Times.format(fact.validFrom()));
        props.put(OBSERVED_AT, Times.format(fact.observedAt()));
        props.put(STATUS, Ids.normalize(fact.status().name()));
        if (fact.evidence() != null) {
            props.put(EVIDENCE, fact.evidence());
        }
        return props;
    }

    /**
     * The fact as WritePolicy compares it. Against a named target, a fact that points elsewhere or
     * nowhere gets its target in its value, so it reads as another value; the text only goes to
     * WritePolicy and into a local conflict signal, never into a fact.
     */
    private static Fact fact(Node node, @Nullable Node target) {
        String value = (String) node.getProperty(VALUE);
        if (target != null) {
            Relationship edge = node.getSingleRelationship(TARGET, Direction.OUTGOING);
            if (edge == null || !edge.getEndNode().equals(target)) {
                value +=
                        edge == null
                                ? " -> nothing"
                                : " -> " + edge.getEndNode().getProperty(GraphWrite.ID);
            }
        }
        return fact(node, value);
    }

    // Enum names are ASCII; ROOT keeps a Turkish default locale from turning i into İ.
    static Fact fact(Node node, String value) {
        Object validTo = node.getProperty(VALID_TO, null);
        return new Fact(
                (String) node.getProperty(GraphWrite.ID),
                (String) node.getProperty(KEY),
                value,
                (String) node.getProperty(ENV, null),
                Source.valueOf(((String) node.getProperty(SOURCE)).toUpperCase(Locale.ROOT)),
                Instant.parse((String) node.getProperty(VALID_FROM)),
                Instant.parse((String) node.getProperty(OBSERVED_AT)),
                validTo == null ? null : Instant.parse((String) validTo),
                FactStatus.valueOf(((String) node.getProperty(STATUS)).toUpperCase(Locale.ROOT)),
                (String) node.getProperty(EVIDENCE, null),
                (String) node.getProperty(GraphWrite.BATCH, null));
    }
}
