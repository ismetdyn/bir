package io.github.ismetdyn.agentkb.server.graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.GraphDatabaseSettings.LogQueryLevel;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.fleetmanagement.FleetManagementSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.kernel.api.security.StaticAccessMode;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedded Neo4j Community, opened on bean creation and shut down with the Spring context.
 *
 * <p>{@link #write} runs one transaction under the single writer lock and is called only from the
 * write package (ArchUnit). {@link #read} runs a read-only transaction, so a write path cannot hide
 * inside it.
 */
@Component
public class EmbeddedNeo4jStore implements AutoCloseable {

    private static final String GRAPH_DIRECTORY = "graph";
    // plan.md "Riskler": keeps the tests and the server within the measured memory limit.
    private static final long PAGE_CACHE_BYTES = 128L * 1024 * 1024;
    private static final String LOG_CONFIG_RESOURCE = "/neo4j-server-logs.xml";
    private static final String LOG_CONFIG_FILE = "conf/server-logs.xml";
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    private final DatabaseManagementService management;
    private final GraphDatabaseService database;
    private final ReentrantLock writerLock = new ReentrantLock();

    public EmbeddedNeo4jStore(@Value("${agentkb.data-dir}") Path dataDir) {
        // Invariant 3 (CLAUDE.md): the only listener is the Spring server and nothing leaves this
        // machine. The query log stays off: query text and parameters may carry secret values.
        Path home = ownerOnly(dataDir).resolve(GRAPH_DIRECTORY);
        this.management =
                new DatabaseManagementServiceBuilder(home)
                        .setConfig(BoltConnector.enabled, false)
                        .setConfig(HttpConnector.enabled, false)
                        .setConfig(HttpsConnector.enabled, false)
                        .setConfig(GraphDatabaseSettings.routing_enabled, false)
                        .setConfig(GraphDatabaseSettings.udc_enabled, false)
                        .setConfig(GraphDatabaseInternalSettings.udc_network_enabled, false)
                        .setConfig(FleetManagementSettings.fleet_manager_enabled, false)
                        .setConfig(GraphDatabaseSettings.log_queries, LogQueryLevel.OFF)
                        .setConfig(GraphDatabaseSettings.pagecache_memory, PAGE_CACHE_BYTES)
                        .setConfig(
                                GraphDatabaseSettings.server_logging_config_path, logConfig(home))
                        .build();
        this.database = management.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
    }

    /**
     * docs/SECRETS.md §5: the graph holds secret values in plain text, so the data directory is
     * 0700 before Neo4j writes into it. An existing wider one is tightened, not refused: installs
     * made before G8 created it with the umask.
     */
    private static Path ownerOnly(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Files.setPosixFilePermissions(dataDir, OWNER_ONLY);
            return dataDir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make " + dataDir + " owner-only", e);
        }
    }

    /**
     * Invariant 10: writes the debug.log config that keeps query text out of Neo4j's own logs (see
     * the resource). Written on every start, so the file always matches this build; set explicitly,
     * so a missing file fails the start instead of falling back to Neo4j's default.
     */
    private static Path logConfig(Path home) {
        Path target = home.resolve(LOG_CONFIG_FILE);
        try (InputStream config =
                EmbeddedNeo4jStore.class.getResourceAsStream(LOG_CONFIG_RESOURCE)) {
            if (config == null) {
                throw new IllegalStateException("missing resource " + LOG_CONFIG_RESOURCE);
            }
            Files.createDirectories(target.getParent());
            Files.copy(config, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    /** Runs {@code fn} in a read-only transaction; any write inside it fails. */
    public <T> T read(Function<Transaction, T> fn) {
        try (Transaction tx = beginReadOnly()) {
            T result = fn.apply(tx);
            tx.commit();
            return result;
        }
    }

    /**
     * Like {@link #read}, but Neo4j terminates the transaction after {@code timeout}; there is no
     * default timeout (G6 probe). The monitor looks every few seconds, so the end is late by up to
     * that.
     */
    public <T> T read(Duration timeout, Function<Transaction, T> fn) {
        try (Transaction tx = beginReadOnly(timeout)) {
            T result = fn.apply(tx);
            tx.commit();
            return result;
        }
    }

    /**
     * Like {@link #read}, but holds the writer lock, so no write commits while {@code fn} runs.
     * Neo4j reads are read-committed; a snapshot export needs nodes and edges from one state.
     */
    public <T> T readUnderWriteLock(Function<Transaction, T> fn) {
        writerLock.lock();
        try {
            return read(fn);
        } finally {
            writerLock.unlock();
        }
    }

    /** Runs {@code fn} in one transaction under the writer lock; rolls back if it throws. */
    public <T> T write(Function<Transaction, T> fn) {
        writerLock.lock();
        try (Transaction tx = database.beginTx()) {
            T result = fn.apply(tx);
            tx.commit();
            return result;
        } finally {
            writerLock.unlock();
        }
    }

    public GraphDatabaseService database() {
        return database;
    }

    // The public API has no per-transaction read-only mode; this internal one is pinned by
    // StoreReadWriteTest, which fails if an upgrade lets a write through.
    private Transaction beginReadOnly() {
        return ((GraphDatabaseAPI) database)
                .beginTransaction(
                        KernelTransaction.Type.EXPLICIT,
                        SecurityContext.AUTH_DISABLED.withMode(StaticAccessMode.READ));
    }

    private Transaction beginReadOnly(Duration timeout) {
        return ((GraphDatabaseAPI) database)
                .beginTransaction(
                        KernelTransaction.Type.EXPLICIT,
                        SecurityContext.AUTH_DISABLED.withMode(StaticAccessMode.READ),
                        ClientConnectionInfo.EMBEDDED_CONNECTION,
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        management.shutdown();
    }
}
