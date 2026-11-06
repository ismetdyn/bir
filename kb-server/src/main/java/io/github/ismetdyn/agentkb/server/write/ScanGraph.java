package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;

/**
 * The records a scan keeps besides facts and relations (SPEC §6.2, ARCHITECTURE §4): the project
 * entity, Repo, SourceFile with IN, FROM {line, commit}, and the closing of values that left a file
 * (WritePolicy.closedByRescan). Each one is found before it is created, so an unchanged rescan
 * creates nothing.
 */
final class ScanGraph {

    static final Label ENTITY = Label.label("Entity");
    static final Label REPO = Label.label("Repo");
    private static final Label SOURCE_FILE = Label.label("SourceFile");
    private static final String FROM = "FROM";
    private static final String LINE = "line";
    // FROM.commit, and Repo.commit: the HEAD the last scan of the repository read (SPEC S1).
    static final String COMMIT = "commit";
    private static final String VALID_TO = "valid_to";
    private static final String PROJECT_TYPE = "project";
    static final String HAS_REPO = "HAS_REPO";
    // ScannerService names a repository without a remote local/<folder>.
    private static final String LOCAL_PREFIX = "local/";
    // closedByRescan looks at one file at a time, so its name only has to differ from null.
    private static final String THIS_FILE = "this file";

    private ScanGraph() {}

    /**
     * The project entity: an existing one by id or alias, else a new one with status observed. An
     * existing project may span repositories (lead decision B: a second repository, or a local one
     * that later got a remote), so the scan's Repo joins it with one more HAS_REPO, within its
     * group only ({@link #refuseOtherGroup}). An existing entity of another type is refused: a
     * repository is not merged into it.
     *
     * @param created gets the id of a new entity
     */
    static Node project(GraphWrite graph, Schema schema, String hint, List<String> created) {
        String label =
                schema.entityTypes().stream()
                        .filter(type -> type.key().equals(PROJECT_TYPE))
                        .map(Schema.EntityType::label)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "types.yaml has no project entity type"));
        Optional<String> found = KnowledgeQueries.resolve(graph.tx, hint);
        if (found.isPresent()) {
            Node entity = graph.tx.findNode(ENTITY, GraphWrite.ID, found.get());
            if (!entity.hasLabel(Label.label(label))) {
                throw new WriteRejectedException(
                        "the project's name belongs to an entity that is not a project",
                        "Tell the user: kb_scan does not merge a repository into an entity of"
                                + " another type; rename that entity or the repository folder.");
            }
            return entity;
        }
        String id = Ids.normalize(WriteCheck.text("project id", hint, WriteCheck.ID_LIMIT));
        for (String other : Schema.LABELS) {
            if (graph.tx.findNode(Label.label(other), GraphWrite.ID, id) != null) {
                throw new WriteRejectedException(
                        "another record that is not an entity has the project's id",
                        "Rename the record with that id, or scan the project under another"
                                + " folder name.");
            }
        }
        created.add(id);
        return graph.node(id, Map.of("status", "observed"), ENTITY.name(), label);
    }

    static Node repo(GraphWrite graph, String id, @Nullable String remote) {
        Node repo = graph.tx.findNode(REPO, GraphWrite.ID, id);
        if (repo == null) {
            return graph.node(
                    WriteCheck.text("repo id", id, WriteCheck.ID_LIMIT),
                    remote == null ? Map.of() : Map.of("remote", remote),
                    REPO.name());
        }
        if (remote != null && !remote.equals(repo.getProperty("remote", null))) {
            graph.set(repo, "remote", remote);
        }
        return repo;
    }

    /** {@code <repo>:<path>}, IN its Repo. */
    static Node sourceFile(GraphWrite graph, Node repo, String path) {
        String repoId = (String) repo.getProperty(GraphWrite.ID);
        String id = repoId + ":" + path;
        Node file = graph.tx.findNode(SOURCE_FILE, GraphWrite.ID, id);
        if (file == null) {
            file = graph.node(id, Map.of("path", path, "repo", repoId), SOURCE_FILE.name());
        }
        if (!linked(file, "IN", repo)) {
            graph.edge(file, "IN", repo, Map.of());
        }
        return file;
    }

    /** FROM {line, commit}: one edge per record and file; a rescan only moves line or commit. */
    static void from(GraphWrite graph, Node record, Node file, int line, @Nullable String commit) {
        Long lineNumber = (long) line;
        try (ResourceIterable<Relationship> edges =
                record.getRelationships(Direction.OUTGOING, RelationshipType.withName(FROM))) {
            for (Relationship edge : edges) {
                if (edge.getEndNode().equals(file)) {
                    if (!lineNumber.equals(edge.getProperty(LINE, null))) {
                        graph.set(edge, LINE, lineNumber);
                    }
                    if (commit != null && !commit.equals(edge.getProperty(COMMIT, null))) {
                        graph.set(edge, COMMIT, commit);
                    }
                    return;
                }
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(LINE, lineNumber);
        if (commit != null) {
            props.put(COMMIT, commit);
        }
        graph.edge(record, FROM, file, props);
    }

    /**
     * SPEC §7 rules 4 and 5 once a scan of {@code file} wrote the facts of {@code entity}: the open
     * scan facts of that file whose value the scan no longer has close at {@code at}. A fact is
     * from the file when it has a FROM edge to it; a value another file of the scan reports ({@code
     * elsewhere} holds for an open fact of the same key, env and value) stays. Asking per open fact
     * keeps the step linear in the files of the scan (CP5 decision 1).
     *
     * @return the ids closed
     */
    static List<String> closeByRescan(
            GraphWrite graph,
            Node entity,
            Node file,
            List<CheckedFact> written,
            Predicate<Fact> elsewhere,
            Instant at) {
        Map<String, Node> open = new HashMap<>();
        List<Sourced> current = new ArrayList<>();
        try (ResourceIterable<Relationship> edges =
                entity.getRelationships(
                        Direction.OUTGOING, RelationshipType.withName("HAS_FACT"))) {
            for (Relationship edge : edges) {
                Node node = edge.getEndNode();
                if (!node.hasProperty(VALID_TO)) {
                    Fact fact = FactWrites.fact(node, (String) node.getProperty("value"));
                    open.put(fact.id(), node);
                    current.add(new Sourced(fact, linked(node, FROM, file) ? THIS_FILE : null));
                }
            }
        }
        // An open fact another file backs stands for that file's fact: same key, env and value.
        List<Fact> backed = current.stream().map(Sourced::fact).filter(elsewhere).toList();
        List<Fact> closed =
                WritePolicy.closedByRescan(THIS_FILE, facts(written, at), backed, at, current);
        closed.forEach(
                fact -> graph.set(open.get(fact.id()), VALID_TO, Times.format(fact.validTo())));
        return closed.stream().map(Fact::id).toList();
    }

    private static List<Fact> facts(List<CheckedFact> checked, Instant at) {
        return checked.stream()
                .map(
                        fact ->
                                new Fact(
                                        "",
                                        fact.key(),
                                        fact.value(),
                                        fact.env(),
                                        Source.SCAN,
                                        at,
                                        at,
                                        null,
                                        FactStatus.OK,
                                        null,
                                        null))
                .toList();
    }

    /**
     * The close step on {@code project} for each file of {@code repo} that this scan did not
     * report: deleted, or nothing left to extract from it. A file of {@code kept} (reported or
     * skipped) is left alone. With nothing scanned, WritePolicy closes only findings, and those
     * live on the project, so another project scanned from a subfolder of the same repo keeps its
     * own.
     *
     * @return the number of facts closed
     */
    static int closeUnreported(
            GraphWrite graph, Node project, Node repo, Set<String> kept, Instant at) {
        int closed = 0;
        for (Node file : files(repo)) {
            Object path = file.getProperty("path", null);
            // An imported SourceFile without a path is kept as it is (invariant 11).
            if (path != null && !kept.contains(path)) {
                closed += closeByRescan(graph, project, file, List.of(), fact -> false, at).size();
            }
        }
        return closed;
    }

    private static List<Node> files(Node repo) {
        List<Node> files = new ArrayList<>();
        try (ResourceIterable<Relationship> edges =
                repo.getRelationships(Direction.INCOMING, RelationshipType.withName("IN"))) {
            for (Relationship edge : edges) {
                if (edge.getStartNode().hasLabel(SOURCE_FILE)) {
                    files.add(edge.getStartNode());
                }
            }
        }
        return files;
    }

    /**
     * CP5 decision 2: an existing project joins the Repo {@code repoId} only when it has no Repo
     * yet or every Repo it has is in the group of {@code repoId} (the id up to its last '/'); a
     * same-named repository of another group is another team's project. A Repo whose id starts with
     * {@code local/} (scanned without a remote) has no group to compare, so it is exempt on either
     * side (lead decision a: a project first scanned as local/x joins its later remote repository).
     */
    static void refuseOtherGroup(Node project, String repoId) {
        if (repoId.startsWith(LOCAL_PREFIX)) {
            return;
        }
        UnaryOperator<String> groupOf = id -> id.substring(0, Math.max(0, id.lastIndexOf('/')));
        String group = groupOf.apply(repoId);
        try (ResourceIterable<Relationship> edges =
                project.getRelationships(Direction.OUTGOING, RelationshipType.withName(HAS_REPO))) {
            for (Relationship edge : edges) {
                String otherId = (String) edge.getEndNode().getProperty(GraphWrite.ID);
                if (!otherId.startsWith(LOCAL_PREFIX) && !groupOf.apply(otherId).equals(group)) {
                    throw new WriteRejectedException(
                            "a project of this name already has repositories of another group",
                            "Tell the user: kb_scan adds a repository to an existing project only"
                                    + " when the project's repositories are in its group. If it is"
                                    + " the same project, ask the user to link the two on purpose"
                                    + " (a part_of or related relation through kb_remember);"
                                    + " otherwise they are two projects with the same name.");
                }
            }
        }
    }

    static boolean linked(Node from, String type, Node to) {
        try (ResourceIterable<Relationship> edges =
                from.getRelationships(Direction.OUTGOING, RelationshipType.withName(type))) {
            for (Relationship edge : edges) {
                if (edge.getEndNode().equals(to)) {
                    return true;
                }
            }
        }
        return false;
    }
}
