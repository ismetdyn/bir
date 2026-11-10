package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0022 decision 9: the seed format wins. Keys recursively alphabetical, rows sorted by id, raw
 * UTF-8, "\n" line ends; separators are those of Python's json.dumps (", " and ": ") because
 * seed/make_seed.py writes the golden files. No Neo4j here: these pin the file format alone.
 */
class SnapshotFormatTest {

    static final Path SEED_SNAPSHOT = Path.of("..", "seed", "snapshot");
    static final String NODES = "nodes.jsonl";
    static final String EDGES = "edges.jsonl";

    @TempDir Path out;

    @Test
    void reading_the_seed_and_writing_it_back_is_byte_identical() throws IOException {
        SnapshotFormat.write(out, SnapshotFormat.read(SEED_SNAPSHOT));

        assertSameFiles(SEED_SNAPSHOT, out);
    }

    @Test
    void rows_are_sorted_by_id_and_keys_alphabetically_whatever_the_input_order()
            throws IOException {
        Map<String, Object> reversedProps = new LinkedHashMap<>();
        reversedProps.put("updated_at", "2026-10-01T09:00:00Z");
        reversedProps.put("summary", "Örnek sunucu.");
        reversedProps.put("aliases", List.of());
        Node second = new Node("b-node", List.of("Entity", "Server"), reversedProps);
        Node first = new Node("a-node", List.of("Topic"), Map.of("updated_at", "x"));
        Edge edge =
                new Edge(
                        "01EDGE",
                        "ABOUT",
                        "b-node",
                        "a-node",
                        Map.of("updated_at", "y", "line", 8L));

        SnapshotFormat.write(out, new Snapshot(List.of(second, first), List.of(edge)));

        assertThat(Files.readString(out.resolve(NODES), StandardCharsets.UTF_8))
                .isEqualTo(
                        """
                        {"id": "a-node", "labels": ["Topic"], "props": {"updated_at": "x"}}
                        {"id": "b-node", "labels": ["Entity", "Server"], "props": {"aliases": [], "summary": "Örnek sunucu.", "updated_at": "2026-10-01T09:00:00Z"}}
                        """);
        assertThat(Files.readString(out.resolve(EDGES), StandardCharsets.UTF_8))
                .isEqualTo(
                        """
                        {"from": "b-node", "id": "01EDGE", "props": {"line": 8, "updated_at": "y"}, "to": "a-node", "type": "ABOUT"}
                        """);
    }

    /** Neo4j does not keep label order; the seed puts Entity first (ornek-db: Entity, Database). */
    @Test
    void labels_keep_the_given_order_and_are_not_sorted() throws IOException {
        Node node = new Node("ornek-db", List.of("Entity", "Database"), Map.of());

        SnapshotFormat.write(out, new Snapshot(List.of(node), List.of()));

        assertThat(Files.readString(out.resolve(NODES), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"id\": \"ornek-db\", \"labels\": [\"Entity\", \"Database\"], \"props\": {}}\n");
    }

    @Test
    void quotes_backslashes_and_newlines_in_values_are_escaped_and_survive_a_round_trip()
            throws IOException {
        String text = "line \"one\"\nline two \\ İzmir";
        Node node = new Node("n", List.of("Lesson"), Map.of("body", text));

        SnapshotFormat.write(out, new Snapshot(List.of(node), List.of()));

        String written = Files.readString(out.resolve(NODES), StandardCharsets.UTF_8);
        assertThat(written)
                .isEqualTo(
                        "{\"id\": \"n\", \"labels\": [\"Lesson\"], \"props\": {\"body\": \"line \\\"one\\\"\\nline two \\\\ İzmir\"}}\n");
        assertThat(written.lines()).hasSize(1);
        assertThat(SnapshotFormat.read(out).nodes().getFirst().props()).containsEntry("body", text);
    }

    @Test
    void an_empty_snapshot_writes_two_empty_files() throws IOException {
        SnapshotFormat.write(out, new Snapshot(List.of(), List.of()));

        assertThat(out.resolve(NODES)).isEmptyFile();
        assertThat(out.resolve(EDGES)).isEmptyFile();
    }

    /**
     * ADR-0022 decision 43: the files are written to a temporary file and renamed. A write that
     * fails after nodes.jsonl is ready (here an edge prop with no JSON form) leaves the previous
     * complete pair, and no temporary file stays behind for kb sync's commit to pick up.
     */
    @Test
    void a_write_that_fails_on_the_edges_keeps_the_previous_pair_and_no_temporary_file()
            throws IOException {
        SnapshotFormat.write(out, SnapshotFormat.read(SEED_SNAPSHOT));
        Node newer = new Node("zz-atomic", List.of("Topic"), Map.of("updated_at", "x"));
        Edge broken =
                new Edge(
                        "01ZZATOMIC", "ABOUT", "zz-atomic", "zz-atomic", Map.of("p", new Object()));

        assertThatThrownBy(
                        () ->
                                SnapshotFormat.write(
                                        out, new Snapshot(List.of(newer), List.of(broken))))
                .as("precondition: the edge cannot be written")
                .isInstanceOf(IllegalArgumentException.class);

        assertSameFiles(SEED_SNAPSHOT, out);
        try (Stream<Path> files = Files.list(out)) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .containsExactlyInAnyOrder(NODES, EDGES);
        }
    }

    /**
     * A freshly cloned repo can hold {@code snapshot} as a link (CWE-59): nothing is written
     * through it, and the message names no path.
     */
    @Test
    void a_linked_snapshot_directory_is_refused_and_nothing_is_written_through_it(
            @TempDir Path elsewhere) throws IOException {
        Path snapshot = Files.createSymbolicLink(out.resolve("snapshot"), elsewhere);

        assertThatThrownBy(() -> SnapshotFormat.write(snapshot, new Snapshot(List.of(), List.of())))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining(elsewhere.toString())
                .hasMessageNotContaining(out.toString());

        try (Stream<Path> files = Files.list(elsewhere)) {
            assertThat(files).as("nothing written through the link").isEmpty();
        }
    }

    static void assertSameFiles(Path expectedDir, Path actualDir) throws IOException {
        for (String file : List.of(NODES, EDGES)) {
            // String comparison first: on failure it shows the differing line.
            assertThat(Files.readString(actualDir.resolve(file), StandardCharsets.UTF_8))
                    .as(file)
                    .isEqualTo(Files.readString(expectedDir.resolve(file), StandardCharsets.UTF_8));
            assertThat(actualDir.resolve(file))
                    .as(file)
                    .hasSameBinaryContentAs(expectedDir.resolve(file));
        }
    }
}
