package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The edit (e), fact (a) and relation (r) forms, each with the id Forms gives it. */
final class FormTemplates {

    private FormTemplates() {}

    /**
     * One field per alias, one for a new alias, then the summary, as the entity has them now. An
     * alias may hold a comma or anything else the server takes, so none is split or joined: an
     * unchanged form sends the same list back; an emptied field drops its alias.
     */
    static Form edit(long form, EntityAnswer entity) {
        String id = entity.id();
        List<Field> fields = new ArrayList<>();
        entity.aliases().forEach(alias -> fields.add(new Field("alias", alias, List.of())));
        fields.add(new Field("new alias", "", List.of()));
        fields.add(
                new Field("summary", entity.summary() == null ? "" : entity.summary(), List.of()));
        return new Form(
                form,
                "Edit " + id,
                f ->
                        new Intent.Edit(
                                id,
                                f.subList(0, f.size() - 1).stream()
                                        .map(Field::value)
                                        .filter(alias -> !alias.isBlank())
                                        .toList(),
                                f.getLast().value(),
                                form),
                fields);
    }

    /** Key (picked from the schema's fact keys), value and an optional env. */
    static Form fact(long form, String id, @Nullable SchemaAnswer schema) {
        List<String> keys =
                schema == null
                        ? List.of()
                        : schema.factKeys().stream().map(SchemaAnswer.FactKey::key).toList();
        return new Form(
                form,
                "Add a fact to " + id,
                f ->
                        new Intent.SetFact(
                                id,
                                f.get(0).value(),
                                f.get(1).value(),
                                f.get(2).value().isBlank() ? null : f.get(2).value(),
                                form),
                List.of(
                        new Field("key", "", keys),
                        new Field("value", "", List.of()),
                        new Field("env (optional)", "", List.of())));
    }

    /** Type (picked from the schema's relation types) and target. */
    static Form relation(long form, String id, @Nullable SchemaAnswer schema) {
        List<String> types =
                schema == null
                        ? List.of()
                        : schema.relationTypes().stream()
                                .map(SchemaAnswer.RelationType::type)
                                .toList();
        return new Form(
                form,
                "Add a relation from " + id,
                f -> new Intent.AddRelation(id, f.get(0).value(), f.get(1).value(), form),
                List.of(new Field("type", "", types), new Field("target", "", List.of())));
    }
}
