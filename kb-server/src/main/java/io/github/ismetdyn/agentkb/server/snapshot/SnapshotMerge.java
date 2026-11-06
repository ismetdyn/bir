package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The record merge of {@code kb sync} (SPEC §10, ADR-0022 decisions 41-42): rows by id, nothing
 * dropped (invariant 11) but what a reverted batch opened. Of two versions of one id a user close
 * (closed_batch of a batch not reverted) wins over an open one, else the one with the later {@code
 * updated_at}; with an equal one and other content, the larger canonical row wins, so every machine
 * picks the same, and the id is reported as a tie. A {@code RevertedBatch} row (id = the batch)
 * keeps the rows stamped with that batch from coming back, with the edges that lose an endpoint. A
 * node of a later batch stays even when such an edge was its anchor (a fact another machine added
 * to an entity of the batch before it saw the revert): it is not of the batch, so it is kept and
 * {@code kb check} names it (CheckService). Pure: the caller applies the result to the graph.
 */
public final class SnapshotMerge {

    public static final String REVERTED_BATCH = "RevertedBatch";

    private static final String BATCH = "batch";
    private static final String UPDATED_AT = "updated_at";
    private static final String CLOSED_BATCH = "closed_batch";

    /** A tie of record {@code id}; {@code about} is the node a signal about it points to. */
    public record Tie(String id, String about) {}

    /** The merged rows in id order, and the ties among them. */
    public record Merged(Snapshot snapshot, List<Tie> ties) {}

    private SnapshotMerge() {}

    public static Merged merge(Snapshot mine, Snapshot theirs) {
        List<Tie> ties = new ArrayList<>();
        Set<String> revertedBefore =
                Stream.concat(mine.nodes().stream(), theirs.nodes().stream())
                        .filter(SnapshotMerge::isTombstone)
                        .map(Node::id)
                        .collect(Collectors.toSet());
        Map<String, Node> nodes = byId(mine.nodes(), Node::id);
        for (Node node : theirs.nodes()) {
            nodes.merge(
                    node.id(),
                    node,
                    (kept, other) ->
                            winner(
                                    kept,
                                    other,
                                    Node::props,
                                    SnapshotFormat::row,
                                    revertedBefore,
                                    () -> ties.add(new Tie(node.id(), node.id()))));
        }
        Map<String, Edge> edges = byId(mine.edges(), Edge::id);
        for (Edge edge : theirs.edges()) {
            edges.merge(
                    edge.id(),
                    edge,
                    (kept, other) ->
                            winner(
                                    kept,
                                    other,
                                    Edge::props,
                                    SnapshotFormat::row,
                                    revertedBefore,
                                    () -> ties.add(new Tie(edge.id(), edge.from()))));
        }
        Set<String> reverted =
                nodes.values().stream()
                        .filter(SnapshotMerge::isTombstone)
                        .map(Node::id)
                        .collect(Collectors.toSet());
        nodes.values()
                .removeIf(node -> !isTombstone(node) && reverted.contains(batchOf(node.props())));
        edges.values()
                .removeIf(
                        edge ->
                                reverted.contains(batchOf(edge.props()))
                                        || !nodes.containsKey(edge.from())
                                        || !nodes.containsKey(edge.to()));
        ties.removeIf(tie -> !nodes.containsKey(tie.about()) || !has(nodes, edges, tie.id()));
        return new Merged(
                new Snapshot(List.copyOf(nodes.values()), List.copyOf(edges.values())),
                List.copyOf(ties));
    }

    private static <T> Map<String, T> byId(List<T> rows, Function<T, String> id) {
        Map<String, T> byId = new TreeMap<>();
        rows.forEach(row -> byId.put(id.apply(row), row));
        return byId;
    }

    /**
     * Decision 41: the later updated_at, else the larger canonical row; mine if both are equal. A
     * user close wins first (lead decision CP3 J6, ADR-0025 decision 16): of a version with
     * closed_batch and one without, the closed one, whatever their updated_at, unless the close was
     * reverted.
     */
    private static <T> T winner(
            T mine,
            T theirs,
            Function<T, Map<String, Object>> props,
            Function<T, String> row,
            Set<String> reverted,
            Runnable tie) {
        boolean mineClosed = isUserClosed(props.apply(mine), reverted);
        if (mineClosed != isUserClosed(props.apply(theirs), reverted)) {
            return mineClosed ? mine : theirs;
        }
        // Times.format has a fixed length, so text order is time order; no updated_at is oldest.
        int byTime = updatedAt(props.apply(mine)).compareTo(updatedAt(props.apply(theirs)));
        if (byTime != 0) {
            return byTime > 0 ? mine : theirs;
        }
        int byRow = row.apply(mine).compareTo(row.apply(theirs));
        if (byRow == 0) {
            return mine;
        }
        tie.run();
        return byRow > 0 ? mine : theirs;
    }

    private static boolean has(Map<String, Node> nodes, Map<String, Edge> edges, String id) {
        return nodes.containsKey(id) || edges.containsKey(id);
    }

    private static boolean isTombstone(Node node) {
        return node.labels().contains(REVERTED_BATCH);
    }

    private static boolean isUserClosed(Map<String, Object> props, Set<String> reverted) {
        return props.get(CLOSED_BATCH) instanceof String batch && !reverted.contains(batch);
    }

    private static String updatedAt(Map<String, Object> props) {
        return props.get(UPDATED_AT) instanceof String at ? at : "";
    }

    private static Object batchOf(Map<String, Object> props) {
        return props.getOrDefault(BATCH, "");
    }
}
