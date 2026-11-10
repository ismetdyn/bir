package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Batch;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Conflict;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.Proposal;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.SecretEntry;
import java.util.List;

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
