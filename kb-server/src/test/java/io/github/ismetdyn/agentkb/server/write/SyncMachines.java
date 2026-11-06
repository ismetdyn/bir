package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Machines for the G13 sync tests (SPEC §10, ARCHITECTURE §8), all local: a bare remote whose
 * {@code main} holds the seed commit, and per machine a clone of it as {@code ~/kb} plus its own
 * graph loaded with the seed. Nothing here reaches the network (invariant 3).
 */
final class SyncMachines implements AutoCloseable {

    static final String BRANCH = "main";
    static final String SNAPSHOT = "snapshot/";

    final Path remote;
    final String seedCommit;
    private final Path dir;
    private final List<Machine> opened = new ArrayList<>();

    private SyncMachines(Path dir, Path remote, String seedCommit) {
        this.dir = dir;
        this.remote = remote;
        this.seedCommit = seedCommit;
    }

    /** A bare remote {@code dir/remote.git} whose main is one commit with the seed snapshot. */
    static SyncMachines withSeedRemote(Path dir) {
        Path seed = SnapshotRepos.repo(dir.resolve("seed"), SnapshotRepos.seed());
        Path remote = dir.resolve("remote.git");
        ScanProjects.git(dir, "init", "--bare", "--quiet", remote.toString());
        ScanProjects.git(seed, "remote", "add", "origin", remote.toString());
        ScanProjects.git(seed, "push", "--quiet", "origin", BRANCH);
        return new SyncMachines(dir, remote, ScanProjects.git(seed, "rev-parse", "HEAD").strip());
    }

    /** No remote at all; {@link #open} takes a repo the test made. */
    static SyncMachines local(Path dir) {
        return new SyncMachines(dir, dir.resolve("no-remote"), "");
    }

    /** {@code name}'s ~/kb is a fresh clone of the remote; its graph holds the seed. */
    Machine clone(String name) throws IOException {
        Path kb = dir.resolve(name + "-kb");
        ScanProjects.git(dir, "clone", "--quiet", remote.toString(), kb.toString());
        return open(name, kb);
    }

    /** A machine on an existing {@code kb} repo; its graph holds the seed. */
    Machine open(String name, Path kb) throws IOException {
        WriteHarness graph = WriteHarness.open(dir.resolve(name));
        graph.writes.importRecords(SnapshotRepos.seed());
        Machine machine = new Machine(graph, kb);
        opened.add(machine);
        return machine;
    }

    /** A plain clone of the remote without a graph, as a third machine that only pushes. */
    Path plainClone(String name) {
        Path kb = dir.resolve(name + "-kb");
        ScanProjects.git(dir, "clone", "--quiet", remote.toString(), kb.toString());
        return kb;
    }

    String remoteHead() {
        return ScanProjects.git(remote, "rev-parse", BRANCH).strip();
    }

    String remoteFile(String file) {
        return ScanProjects.git(remote, "show", BRANCH + ":" + SNAPSHOT + file);
    }

    String remoteHistory() {
        return ScanProjects.git(remote, "log", "--patch", "--all");
    }

    /** {@code snapshot} as the two files hold it, nodes then edges (invariant 8). */
    String canonical(Snapshot snapshot) {
        try {
            Path out = Files.createTempDirectory(dir, "canonical-");
            SnapshotFormat.write(out, snapshot);
            return Files.readString(out.resolve(SnapshotFormat.NODES_FILE), StandardCharsets.UTF_8)
                    + Files.readString(
                            out.resolve(SnapshotFormat.EDGES_FILE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        opened.forEach(machine -> machine.graph.close());
    }

    /** One computer: its graph, its ~/kb and its SyncService on them. */
    static final class Machine {

        final WriteHarness graph;
        final Path repo;
        private final SyncService service;

        private Machine(WriteHarness graph, Path repo) {
            this.graph = graph;
            this.repo = repo;
            this.service = new SyncService(graph.store, graph.writes, repo.toString());
        }

        SyncResult sync() {
            return service.sync();
        }

        Snapshot export() {
            return new SnapshotExporter(graph.store).export();
        }

        String head() {
            return ScanProjects.git(repo, "rev-parse", "HEAD").strip();
        }

        /** Both snapshot files of the local {@code main}, nodes then edges. */
        String committed() {
            return committed(SnapshotFormat.NODES_FILE) + committed(SnapshotFormat.EDGES_FILE);
        }

        String committed(String file) {
            return ScanProjects.git(repo, "show", BRANCH + ":" + SNAPSHOT + file);
        }

        String history() {
            return ScanProjects.git(repo, "log", "--patch", "--all");
        }

        /** The Signal nodes of kind conflict ABOUT the record {@code id}. */
        List<Map<String, Object>> conflictSignalsAbout(String id) {
            return graph.rows(
                    "MATCH (s:Signal {kind: 'conflict'})-[:ABOUT]->({id: $id})"
                            + " RETURN s.id AS id",
                    Map.of("id", id));
        }
    }
}
