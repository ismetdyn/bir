package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ADR-0022 decision 31 (e): the validators of seed/schema/facts.yaml (hostname, ip, port-spec,
 * enum, jdbc-url-without-password, url) apply to new MCP and REST writes. Import never rejects or
 * changes a record (invariant 11), so a snapshot with values they refuse still loads as it is.
 */
class FactValidatorTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final String AT = "2026-01-15T09:00:00Z";
    private static final String HOST = "zz-valid";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @ParameterizedTest(name = "{0} accepts {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "hostname | zz-app-01",
                "hostname | zz-app-01.ornek.example",
                "ip | 192.0.2.21",
                "ip | 2001:db8::1",
                "port | 1521",
                "port | 8443/https",
                "port | 5432/tcp",
                "url | https://zz-lb.ornek.example/health",
                "url | http://192.0.2.21:8080/",
                "jdbc_url | jdbc:postgresql://zz-db.ornek.example:5432/app",
                "jdbc_url | jdbc:oracle:thin:@//zz-db.ornek.example:1521/ORCL",
                "engine | postgresql",
                "engine | redis"
            })
    void a_valid_value_is_written(String key, String value) {
        graph.write(request(Source.USER, HOST, T1, fact(key, value, PROD)));

        assertThat(graph.openFacts(HOST, key, PROD))
                .singleElement()
                .satisfies(f -> assertThat(f).containsEntry("value", value));
    }

    /** Values a secret check alone lets through, so only the validator can refuse them. */
    @ParameterizedTest(name = "{0} refuses {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "hostname | zz bad host",
                "hostname | -zz-bad.ornek.example",
                "hostname | zz..bad.ornek.example",
                "hostname | zz-bad!.ornek.example",
                "ip | 192.0.2.256",
                "ip | 192.0.2.21.7",
                "ip | zz-not-an-ip",
                "port | 70000/tcp",
                "port | zz/tcp",
                "port | -5432/tcp",
                "url | zz not a url",
                "url | zz-lb.ornek.example/health",
                "jdbc_url | postgresql://zz-db.ornek.example:5432/app",
                // Oracle thin user/password@host: a password without :// or password=.
                "jdbc_url | jdbc:oracle:thin:zzuser/Zz7Tiger9Pw@zz-db.ornek.example:1521:ORCL",
                "engine | postgres",
                "engine | sqlite"
            })
    void a_new_write_with_a_value_its_validator_refuses_is_rejected_and_writes_nothing(
            String key, String value) {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> graph.write(request(Source.USER, HOST, T1, fact(key, value, PROD))));

        assertThat(rejected).as(key + " " + value).isNotNull();
        assertThat(rejected.hint()).isNotBlank();
        assertThat(rejected.getMessage() + rejected.hint()).doesNotContain(value);
        assertThat(graph.state()).isEqualTo(before);
    }

    /** The scanner writes through the same path; a scan is a new write too. */
    @Test
    void a_scan_write_is_validated_as_well() {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () ->
                                graph.write(
                                        request(
                                                Source.SCAN,
                                                HOST,
                                                T1,
                                                fact("ip", "zz-not-an-ip", PROD))));

        assertThat(rejected).isNotNull();
        assertThat(graph.state()).isEqualTo(before);
    }

    @Test
    void import_keeps_values_the_validators_refuse() {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(
                new Node(
                        HOST,
                        List.of("Entity", "Server"),
                        Map.of("aliases", List.of(), "status", "current", "updated_at", AT)));
        List<List<String>> refused =
                List.of(
                        List.of("hostname", "zz bad host"),
                        List.of("ip", "zz-not-an-ip"),
                        List.of("port", "70000/tcp"),
                        List.of("url", "zz not a url"),
                        List.of("jdbc_url", "postgresql://zz-db/app"),
                        List.of("engine", "sqlite"));
        for (int i = 0; i < refused.size(); i++) {
            String id = "01ZZFACT0000000000000000" + (10 + i);
            nodes.add(
                    new Node(
                            id,
                            List.of("Fact"),
                            Map.of(
                                    "env",
                                    PROD,
                                    "key",
                                    refused.get(i).get(0),
                                    "value",
                                    refused.get(i).get(1),
                                    "source",
                                    "user",
                                    "status",
                                    "ok",
                                    "valid_from",
                                    AT,
                                    "observed_at",
                                    AT,
                                    "updated_at",
                                    AT)));
            edges.add(
                    new Edge(
                            "01ZZEDGE0000000000000000" + (10 + i),
                            "HAS_FACT",
                            HOST,
                            id,
                            Map.of("updated_at", AT)));
        }
        Snapshot input = new Snapshot(nodes, edges);

        graph.writes.maintenance().importRecords(input);

        Snapshot exported = new SnapshotExporter(graph.store).export();
        assertThat(exported.nodes()).containsExactlyInAnyOrderElementsOf(input.nodes());
        assertThat(exported.edges()).containsExactlyInAnyOrderElementsOf(input.edges());
    }
}
