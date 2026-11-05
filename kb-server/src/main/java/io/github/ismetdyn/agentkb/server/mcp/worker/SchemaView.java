package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.graph.Schema.EntityType;
import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import io.github.ismetdyn.agentkb.server.graph.Schema.RelationType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * kb_schema as Markdown: labels, relation types with their meaning, fixed edges, fact keys,
 * decision fields and the example queries of docs/MCP-TOOLS.md, which the build copies into the jar
 * so the document stays their one place.
 */
final class SchemaView {

    private static final String DOC = "/docs/MCP-TOOLS.md";
    // The first fenced block after the section heading; the heading may go on in parentheses.
    private static final Pattern EXAMPLES =
            Pattern.compile("## Örnek sorgular[^\\n]*\\R.*?```cypher\\R(.*?)```", Pattern.DOTALL);
    private static final String ITEM = "- ";

    private SchemaView() {}

    /**
     * The example block of the document.
     *
     * @throws IllegalStateException if the jar lacks the document or the block
     */
    static String examples() {
        try (InputStream doc = SchemaView.class.getResourceAsStream(DOC)) {
            if (doc == null) {
                throw new IllegalStateException(DOC + " is not on the class path");
            }
            Matcher block =
                    EXAMPLES.matcher(new String(doc.readAllBytes(), StandardCharsets.UTF_8));
            if (!block.find()) {
                throw new IllegalStateException(DOC + " has no example query block");
            }
            return block.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String render(Schema schema, String examples) {
        StringBuilder out = new StringBuilder("# Şema\n\n## Etiketler\n");
        out.append(ITEM)
                .append("Entity ve tür etiketi (kb_stale operasyonel türlere bakar): ")
                .append(schema.entityTypes().stream().map(SchemaView::type).collect(comma()))
                .append('\n');
        out.append(ITEM)
                .append("Diğer kayıtlar: ")
                // LABELS starts with Entity, listed above.
                .append(String.join(", ", Schema.LABELS.subList(1, Schema.LABELS.size())))
                .append('\n');
        out.append(ITEM)
                .append("Entity status: ")
                .append(String.join(", ", schema.entityStatuses()))
                .append("\n\n## İlişki türleri (Entity → Entity)\n");
        schema.relations().forEach(relation -> out.append(relation(relation)));
        out.append("\n## Sabit kenarlar\n")
                .append(ITEM)
                .append(String.join(", ", Schema.FIXED_EDGE_TYPES))
                .append("\n\n## Fact anahtarları\n");
        schema.factKeys().forEach(key -> out.append(factKey(key)));
        out.append("\n## Decision\n")
                .append(ITEM)
                .append("status: ")
                .append(String.join(", ", schema.decisionStatuses()))
                .append('\n')
                .append(ITEM)
                .append("scope: ")
                .append(String.join(", ", schema.decisionScopes()))
                .append("\n\n## Örnek sorgular\n```cypher\n")
                .append(examples)
                .append("```\n");
        return out.toString();
    }

    private static String type(EntityType type) {
        return type.label() + (type.operational() ? " (operasyonel)" : "");
    }

    private static String relation(RelationType relation) {
        return ITEM + relation.type() + " — " + relation.description() + '\n';
    }

    private static String factKey(FactKey key) {
        String rules =
                key.rules().entrySet().stream()
                        .map(rule -> rule.getKey() + ": " + rule.getValue())
                        .collect(comma());
        return ITEM + key.key() + (rules.isEmpty() ? "" : " (" + rules + ")") + '\n';
    }

    private static Collector<CharSequence, ?, String> comma() {
        return Collectors.joining(", ");
    }
}
