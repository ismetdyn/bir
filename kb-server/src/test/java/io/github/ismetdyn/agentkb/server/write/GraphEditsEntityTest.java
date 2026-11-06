package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.RETIRED;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T2;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.EntityView;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code PATCH /entities/{id}} and {@code kb archive} (CLIENTS §1): aliases and summary are entity
 * fields (SPEC §6.1, §6.3), an omitted one stays; archive is a status, nothing of the entity is
 * hidden or deleted (invariant 12). Aliases are normalized in one place (Ids.normalize), so a
 * Turkish alias is found in any case. A lower-trust write never undoes the user's archive (SPEC
 * §7).
 */
class GraphEditsEntityTest {

    private static final int SUMMARY_LIMIT = 300;

    @TempDir Path dir;

    private EditFixture fixture;
    private KnowledgeQueries queries;

    @BeforeEach
    void openGraph() throws IOException {
        fixture = EditFixture.open(dir);
        queries = new KnowledgeQueries(fixture.graph.store);
    }

    @AfterEach
    void closeGraph() {
        fixture.close();
    }

    @Test
    void editing_sets_aliases_and_summary_and_leaves_facts_and_relations_alone() {
        Map<String, Object> before = withoutHost(fixture.graph.state());

        fixture.edits().editEntity(HOST, List.of("zz-web-1", "zz-web-2"), "Kbtest web server.");

        EntityView view = view(HOST);
        assertThat(view.aliases()).containsExactlyInAnyOrder("zz-web-1", "zz-web-2");
        assertThat(view.summary()).isEqualTo("Kbtest web server.");
        assertThat(view.status()).isEqualTo("current");
        assertThat(withoutHost(fixture.graph.state())).isEqualTo(before);
    }

    @Test
    void an_edited_turkish_alias_finds_the_entity_in_any_case() {
        fixture.edits().editEntity(HOST, List.of("ZZ-İZMİR"), null);

        assertThat(queries.entity("zz-izmir")).map(EntityView::id).contains(HOST);
        assertThat(queries.entity("ZZ-IZMIR")).map(EntityView::id).contains(HOST);
        assertThat(queries.entity("zz-ızmır")).map(EntityView::id).contains(HOST);
    }

    @Test
    void a_field_left_out_of_the_edit_keeps_its_value() {
        fixture.edits().editEntity(HOST, List.of("zz-web-1"), "first summary");

        fixture.edits().editEntity(HOST, null, "second summary");
        assertThat(view(HOST).aliases()).containsExactly("zz-web-1");

        fixture.edits().editEntity(HOST, List.of("zz-web-2"), null);
        assertThat(view(HOST).summary()).isEqualTo("second summary");
    }

    /** SPEC §6.1: summary is at most 300 characters; a two-byte letter counts as one. */
    @Test
    void a_summary_of_exactly_300_characters_is_kept() {
        String summary = "ş".repeat(SUMMARY_LIMIT);

        fixture.edits().editEntity(HOST, null, summary);

        assertThat(view(HOST).summary()).isEqualTo(summary);
    }

    @Test
    void archive_and_unarchive_change_only_the_status() {
        Map<String, Object> before = withoutHost(fixture.graph.state());

        fixture.edits().archive(HOST);
        assertThat(view(HOST).status()).isEqualTo("archived");
        assertThat(view(HOST).facts()).isNotEmpty();

        fixture.edits().unarchive(RETIRED);
        assertThat(view(RETIRED).status()).isEqualTo("current");
        assertThat(view(RETIRED).summary()).isEqualTo("kbtest retired box");
        assertThat(withoutHost(fixture.graph.state())).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_later_lower_trust_write_leaves_the_archived_entity_archived(Source source) {
        fixture.edits().archive(HOST);

        fixture.graph.write(request(source, HOST, T2, fact("os", "rhel 10", PROD)));

        assertThat(view(HOST).status()).isEqualTo("archived");
    }

    private EntityView view(String id) {
        return queries.entity(id).orElseThrow();
    }

    // The graph without the edited entity node and the RETIRED one, which the edits may change,
    // and without the Revision rows the edits leave (ADR-0025 decision 15).
    private static Map<String, Object> withoutHost(Map<String, Object> state) {
        state.remove("node " + HOST);
        state.remove("node " + RETIRED);
        return EditFixture.withoutRevisions(state);
    }
}
