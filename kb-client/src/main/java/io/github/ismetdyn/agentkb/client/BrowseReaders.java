package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.BatchAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.client.Json.Fields;

/**
 * The answers kb tui browses with (plan.md H8a: the schema, the entity list and one batch) read
 * field by field into their kb-api records, as AnswerReaders does for H6: a @Nullable component is
 * read as optional, every other one must be there.
 */
final class BrowseReaders {

    private BrowseReaders() {}

    static SchemaAnswer schema(Fields answer) {
        return new SchemaAnswer(
                answer.list(
                        "entityTypes",
                        type ->
                                new SchemaAnswer.EntityType(
                                        type.text("key"),
                                        type.text("label"),
                                        type.flag("operational"))),
                answer.list(
                        "factKeys",
                        key ->
                                new SchemaAnswer.FactKey(
                                        key.text("key"),
                                        key.flag("multi"),
                                        key.optionalText("validator"),
                                        key.texts("allowed"),
                                        key.optionalText("kind"),
                                        key.flag("target"))),
                answer.list(
                        "relationTypes",
                        type ->
                                new SchemaAnswer.RelationType(
                                        type.text("type"),
                                        type.text("description"),
                                        type.texts("synonyms"))));
    }

    static EntityListAnswer entities(Fields answer) {
        return new EntityListAnswer(
                answer.list(
                        "entities",
                        entity ->
                                new EntityListAnswer.Entity(
                                        entity.text("id"),
                                        entity.text("type"),
                                        entity.text("status"),
                                        entity.optionalText("summary"),
                                        entity.text("updatedAt"))),
                answer.count("more"));
    }

    /** A secret change carries its name in key and never a value (invariant 2). */
    static BatchAnswer batch(Fields answer) {
        return new BatchAnswer(
                answer.text("batch"),
                answer.text("at"),
                answer.texts("sources"),
                answer.flag("revertible"),
                answer.texts("blockedBy"),
                answer.list(
                        "changes",
                        change ->
                                new BatchAnswer.Change(
                                        change.text("change"),
                                        change.text("kind"),
                                        change.optionalText("id"),
                                        change.optionalText("entity"),
                                        change.optionalText("key"),
                                        change.optionalText("env"),
                                        change.optionalText("value"),
                                        change.text("source"))),
                answer.count("more"));
    }
}
