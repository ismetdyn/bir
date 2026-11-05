package io.github.ismetdyn.agentkb.server.mcp.archfixture;

import io.github.ismetdyn.agentkb.server.graph.archfixture.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.secrets.archfixture.FakeSecretService;

/** Classes under the mcp package for ArchitectureRulesTest. Only their class files are read. */
public final class McpFixtures {

    private McpFixtures() {}

    public static final class McpEntry {}

    public static final class UsesSecretService {
        FakeSecretService secrets;
    }

    public static final class CallsStoreWrite {
        void call(EmbeddedNeo4jStore store) {
            store.write();
        }
    }

    public static final class ReferencesStoreWrite {
        Runnable reference(EmbeddedNeo4jStore store) {
            return store::write;
        }
    }

    public static final class CallsSubclassWrite {
        void call(EmbeddedNeo4jStore.Subclass store) {
            store.write();
        }
    }

    public static final class CallsStoreDatabase {
        Object call(EmbeddedNeo4jStore store) {
            return store.database();
        }
    }

    /** Same method name on another owner: the rule is about the store, not about every write. */
    public static final class CallsUnrelatedWrite {
        void call(java.io.Writer writer) throws java.io.IOException {
            writer.write(0);
        }
    }
}
