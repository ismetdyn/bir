package io.github.ismetdyn.agentkb.server.graph;

import java.nio.file.Path;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.fleetmanagement.FleetManagementSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedded Neo4j Community, opened on bean creation and shut down with the Spring context.
 * Cypher runs through {@link GraphDatabaseService#executeTransactionally} on {@link #database()}.
 */
@Component
public class EmbeddedNeo4jStore implements AutoCloseable {

    private static final String GRAPH_DIRECTORY = "graph";

    private final DatabaseManagementService management;
    private final GraphDatabaseService database;

    public EmbeddedNeo4jStore(@Value("${agentkb.data-dir}") Path dataDir) {
        // Invariant 3 (CLAUDE.md): the only listener is the Spring server and nothing leaves this machine.
        this.management = new DatabaseManagementServiceBuilder(dataDir.resolve(GRAPH_DIRECTORY))
                .setConfig(BoltConnector.enabled, false)
                .setConfig(HttpConnector.enabled, false)
                .setConfig(HttpsConnector.enabled, false)
                .setConfig(GraphDatabaseSettings.routing_enabled, false)
                .setConfig(GraphDatabaseSettings.udc_enabled, false)
                .setConfig(GraphDatabaseInternalSettings.udc_network_enabled, false)
                .setConfig(FleetManagementSettings.fleet_manager_enabled, false)
                .build();
        this.database = management.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
    }

    public GraphDatabaseService database() {
        return database;
    }

    @Override
    public void close() {
        management.shutdown();
    }
}
