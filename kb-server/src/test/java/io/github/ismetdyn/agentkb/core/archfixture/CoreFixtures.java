package io.github.ismetdyn.agentkb.core.archfixture;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import org.neo4j.graphdb.Node;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Violations for ArchitectureRulesTest, under kb-core's package name. Only their class files are
 * read.
 */
public final class CoreFixtures {

    private CoreFixtures() {}

    public static final class SpringField {
        ApplicationContext context;
    }

    // Outside the application's component scan, which starts at the server package.
    @Component
    public static final class SpringAnnotation {}

    public static final class Neo4jField {
        Node node;
    }

    public static final class ServerField {
        EmbeddedNeo4jStore store;
    }
}
