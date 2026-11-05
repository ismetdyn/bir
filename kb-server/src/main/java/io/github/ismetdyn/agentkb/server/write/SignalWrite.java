package io.github.ismetdyn.agentkb.server.write;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;

/**
 * ADR-0022 decision 7: the smallest Signal. One kind + key is one node, so the same signal again
 * only raises count and last_at. A signal is local (invariant 8), never deleted (invariant 5) and
 * carries no batch.
 */
final class SignalWrite {

    private static final Label SIGNAL = Label.label("Signal");
    private static final String KIND = "kind";
    private static final String KEY = "key";
    private static final String COUNT = "count";
    private static final String LAST_AT = "last_at";

    private SignalWrite() {}

    /**
     * @param at {@code Times.format} of when the signal was seen
     * @return the new signal, or null if one with this kind and key was raised again
     */
    static @Nullable Node raise(
            GraphWrite graph, String kind, String key, String payload, String at) {
        try (ResourceIterator<Node> found = graph.tx.findNodes(SIGNAL, KIND, kind, KEY, key)) {
            if (found.hasNext()) {
                Node signal = found.next();
                graph.set(signal, COUNT, ((Number) signal.getProperty(COUNT)).longValue() + 1);
                // Times.format has a fixed length, so text order is time order.
                if (at.compareTo((String) signal.getProperty(LAST_AT)) > 0) {
                    graph.set(signal, LAST_AT, at);
                }
                return null;
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(KIND, kind);
        props.put(KEY, key);
        props.put("payload", payload);
        props.put("at", at);
        props.put(LAST_AT, at);
        props.put(COUNT, 1L);
        props.put("status", "open");
        return graph.localNode(Ulid.next(), props, SIGNAL.name());
    }
}
