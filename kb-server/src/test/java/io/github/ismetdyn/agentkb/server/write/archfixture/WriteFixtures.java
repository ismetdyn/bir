package io.github.ismetdyn.agentkb.server.write.archfixture;

import io.github.ismetdyn.agentkb.server.graph.archfixture.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.archfixture.McpFixtures;
import io.github.ismetdyn.agentkb.server.snapshot.archfixture.SnapshotReachesWrite;

/** Classes under the write package for ArchitectureRulesTest. Only their class files are read. */
public final class WriteFixtures {

    private WriteFixtures() {}

    public static final class CallsStoreWrite {
        void call(EmbeddedNeo4jStore store) {
            store.write();
        }
    }

    public static final class WriteReachesMcp {
        McpFixtures.McpEntry entry;
    }

    /** One half of a write <-> snapshot cycle; the other half is SnapshotReachesWrite. */
    public static final class WriteReachesSnapshot {
        SnapshotReachesWrite snapshot;
    }
}
