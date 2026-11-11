package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.Ids;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code <repo>/schema/*.yaml} (SPEC §6.5): entity types, the relation vocabulary and the fact
 * keys. The vocabulary is data, not code, so CypherGuard and kb_schema read it from the files
 * (invariant 7).
 */
public record Schema(
        List<EntityType> entityTypes,
        List<RelationType> relations,
        List<FactKey> factKeys,
        List<String> entityStatuses,
        List<String> decisionStatuses,
        List<String> decisionScopes,
        Map<String, List<String>> environments) {

    /** SPEC §6.1 labels: Entity, then the records that are not entities (types.yaml header). */
    public static final List<String> LABELS =
            List.of(
                    "Entity",
                    "Fact",
                    "Decision",
                    "Lesson",
                    "Secret",
                    "SourceFile",
                    "Repo",
                    "Document",
                    "Stack",
                    "Topic",
                    "Proposal",
                    "Signal",
                    "Episode",
                    "Revision");

    /** SPEC §6.2 fixed edges; they are not in relations.yaml. */
    public static final List<String> FIXED_EDGE_TYPES =
            List.of(
                    "HAS_FACT",
                    "HAS_SECRET",
                    "SUPERSEDES",
                    "FROM",
                    "IN",
                    "HAS_REPO",
                    "TARGET",
                    "APPLIES_TO",
                    "ABOUT",
                    "PRODUCED",
                    "REVISES");

    private static final String TYPES = "types.yaml";
    private static final String RELATIONS = "relations.yaml";
    private static final String FACTS = "facts.yaml";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** {@code label} is the graph label next to Entity; kb_stale looks at operational types. */
    public record EntityType(String key, String label, boolean operational) {}

    /**
     * {@code type} and {@code synonyms} in graph case: relations.yaml keys in upper case. A synonym
     * is written as its type (SPEC §6.2).
     */
    public record RelationType(String type, String description, List<String> synonyms) {}

    /** A fact key and its rules as written in facts.yaml ({@code validator}, {@code kind}, …). */
    public record FactKey(String key, Map<String, String> rules) {

        /** A text key is always multi-valued (schema/facts.yaml header). */
        public boolean multi() {
            return "true".equals(rules.get("multi")) || "text".equals(rules.get("kind"));
        }

        /** The values of {@code enum}, the only ones the key takes; empty if it has none. */
        public List<String> allowed() {
            String allowed = rules.get("enum");
            return allowed == null ? List.of() : List.of(allowed.split(",\\s*"));
        }

        /** Whether a value may point to an entity with a TARGET edge ({@code target: optional}). */
        public boolean takesTarget() {
            return rules.containsKey("target");
        }
    }

    /**
     * @throws IllegalStateException naming the file if one is missing, broken or lacks its section
     */
    public static Schema load(Path dir) {
        JsonNode types = read(dir, TYPES);
        JsonNode relations = section(read(dir, RELATIONS), "relations", RELATIONS);
        JsonNode factsFile = read(dir, FACTS);
        JsonNode facts = section(factsFile, "facts", FACTS);
        JsonNode decision = types.path("decision");
        Map<String, List<String>> environments = new LinkedHashMap<>();
        factsFile
                .path("environments")
                .properties()
                .forEach(env -> environments.put(env.getKey(), strings(env.getValue())));
        return new Schema(
                entityTypes(section(types, "entity_types", TYPES)),
                relationTypes(relations),
                factKeys(facts),
                strings(types.path("entity_status")),
                strings(decision.path("status")),
                strings(decision.path("scope")),
                Map.copyOf(environments));
    }

    /**
     * The fact keys of a facts.yaml text, read as {@link #load} reads the file, so a change to it
     * can be checked before it is written (ADR-0025 decision 12).
     *
     * @throws IllegalStateException if the text is no YAML or lacks the facts section
     */
    public static List<FactKey> factKeys(String factsYaml) {
        JsonNode root;
        try {
            root = YAML.readTree(factsYaml);
        } catch (JacksonException e) {
            throw new IllegalStateException("facts.yaml text is no YAML", e);
        }
        if (root == null) {
            throw new IllegalStateException("facts.yaml text is empty");
        }
        return factKeys(section(root, "facts", FACTS));
    }

    /**
     * The environment a profile, file suffix or namespace name stands for, by the synonyms of
     * facts.yaml#environments; a name without one is kept, normalized (ARCHITECTURE §6).
     */
    public String environment(String name) {
        String wanted = Ids.normalize(name.strip());
        return environments.entrySet().stream()
                .filter(
                        env ->
                                env.getKey().equals(wanted)
                                        || env.getValue().stream()
                                                .anyMatch(
                                                        synonym ->
                                                                Ids.normalize(synonym)
                                                                        .equals(wanted)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(wanted);
    }

    public List<String> relationTypes() {
        return relations.stream().map(RelationType::type).toList();
    }

    /** The graph type of a relation type or synonym in any case, if the vocabulary has it. */
    public Optional<String> relationType(String typeOrSynonym) {
        String wanted = typeOrSynonym.strip().toUpperCase(Locale.ROOT);
        return relations.stream()
                .filter(
                        relation ->
                                relation.type().equals(wanted)
                                        || relation.synonyms().contains(wanted))
                .map(RelationType::type)
                .findFirst();
    }

    public List<String> operationalLabels() {
        return entityTypes.stream().filter(EntityType::operational).map(EntityType::label).toList();
    }

    private static JsonNode read(Path dir, String file) {
        Path path = dir.resolve(file);
        try {
            return YAML.readTree(path);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot read schema file " + path, e);
        }
    }

    private static JsonNode section(JsonNode root, String name, String file) {
        JsonNode section = root.path(name);
        if (!section.isObject() || section.isEmpty()) {
            throw new IllegalStateException(file + " has no '" + name + "' map");
        }
        return section;
    }

    private static List<EntityType> entityTypes(JsonNode types) {
        List<EntityType> all = new ArrayList<>();
        types.properties()
                .forEach(
                        type ->
                                all.add(
                                        new EntityType(
                                                type.getKey(),
                                                type.getValue().path("label").asString(""),
                                                type.getValue()
                                                        .path("operational")
                                                        .asBoolean(false))));
        return List.copyOf(all);
    }

    // Relation types are ASCII words; ROOT keeps a Turkish default locale from changing an i.
    private static List<RelationType> relationTypes(JsonNode relations) {
        List<RelationType> all = new ArrayList<>();
        relations
                .properties()
                .forEach(
                        relation ->
                                all.add(
                                        new RelationType(
                                                relation.getKey().toUpperCase(Locale.ROOT),
                                                relation.getValue()
                                                        .path("description")
                                                        .asString(""),
                                                strings(relation.getValue().path("synonyms"))
                                                        .stream()
                                                        .map(
                                                                synonym ->
                                                                        synonym.toUpperCase(
                                                                                Locale.ROOT))
                                                        .toList())));
        return List.copyOf(all);
    }

    private static List<FactKey> factKeys(JsonNode facts) {
        List<FactKey> all = new ArrayList<>();
        facts.properties()
                .forEach(
                        fact -> {
                            Map<String, String> rules = new LinkedHashMap<>();
                            fact.getValue()
                                    .properties()
                                    .forEach(
                                            rule ->
                                                    rules.put(
                                                            rule.getKey(), text(rule.getValue())));
                            all.add(new FactKey(fact.getKey(), rules));
                        });
        return List.copyOf(all);
    }

    // engine: {enum: [oracle, …]} reads as "oracle, …".
    private static String text(JsonNode value) {
        return value.isArray() ? String.join(", ", strings(value)) : value.asString();
    }

    private static List<String> strings(JsonNode list) {
        return list.valueStream().map(JsonNode::asString).toList();
    }
}
