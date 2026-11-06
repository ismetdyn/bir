package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;

/**
 * A completed upload as a Document node (SPEC §6.2 and §6.7, ADR-0025 decisions 2, 4 and 9): {@code
 * doc:<first 12 of the sha256>}, source user, a batch of its own and no Episode. The same content
 * again opens no second node; a new {@code about} adds one ABOUT edge and {@code replaces} one
 * SUPERSEDES edge to the older Document, each only once. {@code name} and {@code origin} reach the
 * snapshot and git, so a secret-looking or too long one is refused (invariant 2); a refusal never
 * repeats the value.
 *
 * @param sha256 the lowercase hex sha256 of the content
 * @param about id or alias of the Entity the document is about
 * @param replaces id of the older Document this one replaces
 */
public record DocumentWrite(
        String sha256,
        @Nullable String name,
        @Nullable String about,
        @Nullable String origin,
        @Nullable String replaces) {

    private static final Label DOCUMENT = Label.label("Document");
    private static final String ABOUT = "ABOUT";
    private static final String SUPERSEDES = "SUPERSEDES";
    private static final String SHA256 = "sha256";
    // NAME_MAX of common file systems.
    private static final int NAME_LIMIT = 255;
    // A URL beyond this length is no source a person looks up.
    private static final int ORIGIN_LIMIT = 2_048;
    private static final int ID_HEX = 12;
    private static final String FIELD_HINT =
            "Give the file name and, if any, where the file came from, without credentials; store"
                    + " a secret with kb secret set.";
    private static final String ABOUT_HINT =
            "Give the id or alias of an existing entity as about (kb_search finds it).";
    private static final String REPLACES_HINT =
            "Give the id (doc:...) of an existing document as replaces (kb doc list shows them).";

    /** {@code doc:} and the first 12 hex digits of {@code sha256} (ARCHITECTURE §4). */
    public static String id(String sha256) {
        return "doc:" + sha256.substring(0, ID_HEX);
    }

    /** The blob's path in the knowledge repo, {@code blobs/<first 2>/<sha256>} (SPEC §6.7). */
    public static String path(String sha256) {
        return "blobs/" + sha256.substring(0, 2) + "/" + sha256;
    }

    /**
     * Checks name and origin without the graph, so a refusal comes before the blob is stored.
     *
     * @throws WriteRejectedException if one is empty, too long or looks like a secret
     */
    public void checkFields() {
        field("name", name, NAME_LIMIT);
        if (origin != null) {
            field("origin", origin, ORIGIN_LIMIT);
        }
    }

    /**
     * The targets of about and replaces, which must exist.
     *
     * @throws WriteRejectedException if one is missing or replaces names this document itself
     */
    private Targets targets(Transaction tx) {
        @Nullable Node aboutNode = null;
        if (about != null) {
            aboutNode =
                    KnowledgeQueries.resolve(tx, about)
                            .map(id -> tx.findNode(Label.label("Entity"), GraphWrite.ID, id))
                            .orElseThrow(
                                    () ->
                                            new WriteRejectedException(
                                                    "about names no entity", ABOUT_HINT));
        }
        @Nullable Node replacesNode = null;
        if (replaces != null) {
            if (replaces.strip().equals(id(sha256))) {
                throw new WriteRejectedException("a document cannot replace itself", REPLACES_HINT);
            }
            replacesNode = tx.findNode(DOCUMENT, GraphWrite.ID, replaces.strip());
            if (replacesNode == null) {
                throw new WriteRejectedException("replaces names no document", REPLACES_HINT);
            }
        }
        return new Targets(aboutNode, replacesNode);
    }

    private record Targets(@Nullable Node about, @Nullable Node replaces) {}

    /**
     * Opens the Document unless it exists, then links it.
     *
     * @return true if it opened the Document
     */
    boolean add(GraphWrite graph, long size) {
        SecretWrite.refuseStoredValues(graph.tx, Arrays.asList(name, origin));
        Targets targets = targets(graph.tx);
        String id = id(sha256);
        Node document = graph.tx.findNode(DOCUMENT, GraphWrite.ID, id);
        boolean created = document == null;
        if (created) {
            document = graph.node(id, props(size), DOCUMENT.name());
        } else if (!sha256.equals(document.getProperty(SHA256, null))) {
            throw new WriteRejectedException(
                    "another document has the same id",
                    "Two files share the first 12 digits of their sha256; this one cannot be"
                            + " added.");
        }
        link(graph, document, ABOUT, targets.about());
        link(graph, document, SUPERSEDES, targets.replaces());
        return created;
    }

    private Map<String, Object> props(long size) {
        Map<String, Object> props = new HashMap<>();
        props.put(SHA256, sha256);
        props.put("name", String.valueOf(name).strip());
        props.put("size", size);
        props.put("path", path(sha256));
        props.put("source", "user");
        props.put("added_at", Times.format(Instant.now()));
        if (origin != null) {
            props.put("origin", origin.strip());
        }
        return props;
    }

    /** One edge of {@code type} to {@code to}; an existing one is not doubled. */
    private static void link(GraphWrite graph, Node from, String type, @Nullable Node to) {
        if (to == null) {
            return;
        }
        for (Relationship edge :
                from.getRelationships(Direction.OUTGOING, RelationshipType.withName(type))) {
            if (edge.getEndNode().equals(to)) {
                return;
            }
        }
        graph.edge(from, type, to, Map.of());
    }

    private static void field(String what, @Nullable String value, int limit) {
        if (value == null || value.isBlank()) {
            throw new WriteRejectedException(what + " is empty", FIELD_HINT);
        }
        if (value.codePointCount(0, value.length()) > limit) {
            throw new WriteRejectedException(
                    what + " is longer than " + limit + " characters", FIELD_HINT);
        }
        if (SecretPatterns.looksLikeSecret(value)) {
            throw new WriteRejectedException(what + " looks like a secret value", FIELD_HINT);
        }
    }
}
