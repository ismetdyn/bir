package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import io.github.ismetdyn.agentkb.client.Json.Fields;

/**
 * The plan.md H6 answers read field by field into their kb-api records, by name and without
 * reflection (ADR-0025 decision 6); Json.READERS lists them. A @Nullable component is read with
 * optionalText, every other one must be there.
 */
final class AnswerReaders {

    private AnswerReaders() {}

    static SearchAnswer search(Fields answer) {
        return new SearchAnswer(
                answer.list(
                        "hits",
                        hit ->
                                new SearchAnswer.Hit(
                                        hit.text("id"),
                                        hit.text("type"),
                                        hit.text("tier"),
                                        hit.flag("archived"),
                                        hit.optionalText("snippet"))),
                answer.count("more"));
    }

    static EntityAnswer entity(Fields answer) {
        return new EntityAnswer(
                answer.text("id"),
                answer.text("type"),
                answer.text("status"),
                answer.optionalText("summary"),
                answer.texts("aliases"),
                answer.list(
                        "facts",
                        fact ->
                                new EntityAnswer.Fact(
                                        fact.text("id"),
                                        fact.text("key"),
                                        fact.text("value"),
                                        fact.text("env"),
                                        fact.text("source"),
                                        fact.text("status"),
                                        fact.text("validFrom"),
                                        fact.text("observedAt"),
                                        fact.optionalText("validTo"),
                                        fact.optionalText("target"),
                                        fact.optionalText("origin"))),
                answer.list(
                        "relations",
                        relation ->
                                new EntityAnswer.Relation(
                                        relation.optionalText("id"),
                                        relation.text("type"),
                                        relation.flag("outgoing"),
                                        relation.text("other"),
                                        relation.optionalText("env"),
                                        relation.text("source"),
                                        relation.text("validFrom"),
                                        relation.text("observedAt"),
                                        relation.optionalText("validTo"))),
                answer.list(
                        "secrets",
                        secret ->
                                new EntityAnswer.Secret(
                                        secret.text("id"),
                                        secret.text("name"),
                                        secret.text("env"),
                                        secret.text("placeholder"))));
    }

    static WriteAnswer write(Fields answer) {
        return new WriteAnswer(
                answer.text("entity"),
                answer.flag("created"),
                answer.text("batch"),
                answer.texts("added"),
                answer.texts("refreshed"),
                answer.texts("closed"),
                answer.texts("conflicts"));
    }

    static CypherAnswer cypher(Fields answer) {
        return new CypherAnswer(
                answer.texts("columns"),
                answer.list("rows", row -> new CypherAnswer.Row(row.texts("cells"))),
                answer.flag("truncated"));
    }

    static ConflictsAnswer conflicts(Fields answer) {
        return new ConflictsAnswer(
                answer.list(
                        "conflicts",
                        conflict ->
                                new ConflictsAnswer.Conflict(
                                        conflict.text("id"),
                                        conflict.text("entity"),
                                        conflict.text("key"),
                                        conflict.text("env"),
                                        conflict.text("value"),
                                        conflict.text("source"),
                                        conflict.text("validFrom"),
                                        conflict.texts("current"))),
                answer.count("more"));
    }

    static ProposalsAnswer proposals(Fields answer) {
        return new ProposalsAnswer(
                answer.list(
                        "proposals",
                        proposal ->
                                new ProposalsAnswer.Proposal(
                                        proposal.text("id"),
                                        proposal.text("kind"),
                                        proposal.text("status"),
                                        proposal.text("by"),
                                        proposal.optionalText("target"),
                                        proposal.text("content"),
                                        proposal.optionalText("evidence"),
                                        proposal.optionalText("reviewNote"))),
                answer.count("more"));
    }

    static ActivityAnswer activity(Fields answer) {
        return new ActivityAnswer(
                answer.list(
                        "batches",
                        batch ->
                                new ActivityAnswer.Batch(
                                        batch.text("batch"),
                                        batch.text("at"),
                                        batch.texts("sources"),
                                        batch.count("opened"),
                                        batch.count("closed"),
                                        batch.count("revised"),
                                        batch.texts("entities"))),
                answer.count("more"));
    }

    /** No value is read: SecretsAnswer has no place for one (invariant 2). */
    static SecretsAnswer secrets(Fields answer) {
        return new SecretsAnswer(
                answer.list(
                        "secrets",
                        secret ->
                                new SecretsAnswer.Secret(
                                        secret.text("id"),
                                        secret.text("name"),
                                        secret.text("env"),
                                        secret.text("placeholder"),
                                        secret.flag("hasValue"))),
                answer.count("more"));
    }

    static ScanAnswer scan(Fields answer) {
        return new ScanAnswer(
                answer.text("project"),
                answer.text("repo"),
                answer.text("batch"),
                answer.count("files"),
                answer.texts("newEntities"),
                answer.count("added"),
                answer.count("refreshed"),
                answer.count("closed"),
                answer.count("conflicts"),
                answer.texts("secrets"),
                answer.texts("findings"),
                answer.texts("skipped"));
    }
}
