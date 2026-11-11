package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.BatchAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.render.ResultList;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Batch;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Change;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Conflict;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.EntityEntry;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Proposal;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.SecretEntry;
import io.github.ismetdyn.agentkb.server.write.RevertCheck;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The review lists (ListQueries) as kb-api records (ADR-0025 decision 6), a page at a time. */
final class ListAnswers {

    private ListAnswers() {}

    static ConflictsAnswer conflicts(List<Conflict> all, int limit) {
        RequestChecks.Page<Conflict> page = RequestChecks.page(all, limit);
        return new ConflictsAnswer(
                page.items().stream()
                        .map(
                                c ->
                                        new ConflictsAnswer.Conflict(
                                                c.id(),
                                                c.entity(),
                                                c.key(),
                                                c.env(),
                                                c.value(),
                                                c.source(),
                                                c.validFrom(),
                                                c.current()))
                        .toList(),
                page.more());
    }

    static ProposalsAnswer proposals(List<Proposal> all, int limit) {
        RequestChecks.Page<Proposal> page = RequestChecks.page(all, limit);
        return new ProposalsAnswer(
                page.items().stream()
                        .map(
                                p ->
                                        new ProposalsAnswer.Proposal(
                                                p.id(),
                                                p.kind(),
                                                p.status(),
                                                p.by(),
                                                p.target(),
                                                p.content(),
                                                p.evidence(),
                                                p.reviewNote()))
                        .toList(),
                page.more());
    }

    static ActivityAnswer activity(List<Batch> all, int limit) {
        RequestChecks.Page<Batch> page = RequestChecks.page(all, limit);
        return new ActivityAnswer(
                page.items().stream()
                        .map(
                                b ->
                                        new ActivityAnswer.Batch(
                                                b.batch(),
                                                b.at(),
                                                b.sources(),
                                                b.opened(),
                                                b.closed(),
                                                b.revised(),
                                                b.entities()))
                        .toList(),
                page.more());
    }

    /**
     * One batch: its activity row, whether kb revert would undo it now, and a page of its changes,
     * each kind in lower case and each value one line cut at 300 characters and an ellipsis, as a
     * search snippet.
     */
    static BatchAnswer batch(Batch batch, RevertCheck revert, List<Change> all, int limit) {
        RequestChecks.Page<Change> page = RequestChecks.page(all, limit);
        return new BatchAnswer(
                batch.batch(),
                batch.at(),
                batch.sources(),
                revert.revertible(),
                revert.blockedBy(),
                page.items().stream()
                        .map(
                                c ->
                                        new BatchAnswer.Change(
                                                c.change(),
                                                Ids.normalize(c.kind()),
                                                c.id(),
                                                c.entity(),
                                                c.key(),
                                                c.env(),
                                                line(c.value()),
                                                c.source()))
                        .toList(),
                page.more());
    }

    static EntityListAnswer entities(List<EntityEntry> all, int limit) {
        RequestChecks.Page<EntityEntry> page = RequestChecks.page(all, limit);
        return new EntityListAnswer(
                page.items().stream()
                        .map(
                                e ->
                                        new EntityListAnswer.Entity(
                                                e.id(),
                                                e.type(),
                                                e.status(),
                                                line(e.summary()),
                                                e.updatedAt()))
                        .toList(),
                page.more());
    }

    private static @Nullable String line(@Nullable String text) {
        return text == null ? null : ResultList.snippet(text);
    }

    /** Names, env, placeholder and whether a value is here; there is no value to map. */
    static SecretsAnswer secrets(List<SecretEntry> all, int limit) {
        RequestChecks.Page<SecretEntry> page = RequestChecks.page(all, limit);
        return new SecretsAnswer(
                page.items().stream()
                        .map(
                                s ->
                                        new SecretsAnswer.Secret(
                                                s.id(),
                                                s.name(),
                                                s.env(),
                                                ApiAnswers.PLACEHOLDER + s.id(),
                                                s.hasValue()))
                        .toList(),
                page.more());
    }
}
