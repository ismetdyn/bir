package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The accept of a {@code kind=schema} proposal (ADR-0025 decision 12, SPEC §7 rule 11): its content
 * is one facts.yaml entry, {@code rack_slot: {multi: false}}. The entry is parsed and written again
 * from checked parts, never copied, because an agent wrote it: a key in the facts.yaml form, rules
 * kb knows and plain words as values. The line goes after the last entry of the {@code facts:}
 * block with the indentation of its entries, so the comments of the file stay. The new text is read
 * with the schema loader before it replaces the file and must hold the old keys and exactly the new
 * one (lead decision CP3 S6). RepoSchema reads the files on every call, so the key applies at once.
 *
 * <p>The file is written before the graph commits the accept. A key already there with the same
 * rules is left alone, so an accept whose commit failed can be repeated (CP3 J3). A link at schema/
 * or facts.yaml, which a sync remote can commit, is refused, never written through. The temp file
 * next to facts.yaml is never committed: {@code kb sync} stages only the snapshot and the document
 * store (SyncGit).
 */
final class SchemaAccept {

    private static final String FACTS_FILE = "facts.yaml";
    private static final String FACTS_BLOCK = "facts:";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Set<String> RULES = Set.of("validator", "multi", "kind", "target", "enum");
    private static final String HINT =
            "Reject it; a schema proposal is one facts.yaml entry such as rack_slot: {multi:"
                    + " false}, with the rules validator, multi, kind, target or enum.";
    private static final String HAND_HINT =
            "Add the key to schema/facts.yaml by hand, then reject the proposal.";
    private static final String LINK_HINT =
            "Make schema/ a directory and schema/facts.yaml a file of the repo, not links, then"
                    + " accept again.";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private SchemaAccept() {}

    /**
     * @throws WriteRejectedException if the content is no well-formed entry, its key is there with
     *     other rules, or facts.yaml would not read back with it
     * @throws UncheckedIOException if facts.yaml cannot be read or written
     */
    static void add(RepoSchema repo, String content) {
        Schema schema = repo.load();
        Map.Entry<String, JsonNode> fact = parsed(content);
        String key = fact.getKey();
        Map<String, String> rules = rules(fact.getValue());
        Optional<FactKey> known =
                schema.factKeys().stream().filter(other -> other.key().equals(key)).findFirst();
        if (known.isPresent()) {
            if (known.get().rules().equals(loaded(rules))) {
                return;
            }
            throw new WriteRejectedException(
                    "the key is already in the schema with other rules",
                    "Reject it; the key is there to use.");
        }
        Path file = repo.directory().resolve(FACTS_FILE);
        // A sync remote is untrusted (ADR-0020): it can commit schema/ or facts.yaml as a link.
        if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WriteRejectedException(
                    "schema/ or schema/" + FACTS_FILE + " is a link or no plain file", LINK_HINT);
        }
        String line =
                String.join(
                        ", ",
                        rules.entrySet().stream()
                                .map(rule -> rule.getKey() + ": " + rule.getValue())
                                .toList());
        try {
            String text = withEntry(Files.readString(file), key + ": {" + line + "}");
            requireOneMoreKey(schema.factKeys(), text, new FactKey(key, loaded(rules)));
            replace(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write schema/" + FACTS_FILE, e);
        }
    }

    /**
     * A temp file of a new random name (O_EXCL, so no planted link is followed), then ATOMIC_MOVE;
     * the temp file is removed on any failure.
     */
    private static void replace(Path file, String text) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "." + FACTS_FILE + "-", ".tmp");
        try {
            Files.writeString(temp, text);
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The one {@code key: {rules}} entry of {@code content}, a YAML map of exactly one entry, its
     * key in the facts.yaml form.
     */
    private static Map.Entry<String, JsonNode> parsed(String content) {
        JsonNode root;
        try {
            root = YAML.readTree(content);
        } catch (JacksonException e) {
            throw refused();
        }
        if (root == null || !root.isObject() || root.size() != 1) {
            throw refused();
        }
        return root.properties().stream()
                .filter(fact -> KEY.matcher(fact.getKey()).matches())
                .filter(fact -> fact.getValue().isObject())
                .findFirst()
                .orElseThrow(SchemaAccept::refused);
    }

    /**
     * {@code validator -> ip, enum -> [a, b]}: known rules, a validator kb has, plain values, in
     * the facts.yaml form.
     */
    private static Map<String, String> rules(JsonNode rules) {
        Map<String, String> written = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> rule : rules.properties()) {
            String value = value(rule.getValue());
            boolean unknownValidator =
                    "validator".equals(rule.getKey()) && !FactValues.VALIDATORS.contains(value);
            if (!RULES.contains(rule.getKey()) || unknownValidator) {
                throw refused();
            }
            written.put(rule.getKey(), value);
        }
        return written;
    }

    /** The rules as the schema loader reads them back: a list without its brackets (Schema). */
    private static Map<String, String> loaded(Map<String, String> rules) {
        Map<String, String> loaded = new LinkedHashMap<>();
        // A plain value has no bracket, so only a list starts with one.
        rules.forEach(
                (rule, value) ->
                        loaded.put(
                                rule,
                                value.startsWith("[")
                                        ? value.substring(1, value.length() - 1)
                                        : value));
        return loaded;
    }

    /**
     * The new text, read by the schema loader, holds the old keys as they were and {@code added}.
     */
    private static void requireOneMoreKey(List<FactKey> before, String text, FactKey added) {
        List<FactKey> after;
        try {
            after = Schema.factKeys(text);
        } catch (IllegalStateException e) {
            throw new WriteRejectedException(
                    "schema/facts.yaml would not read back with the new key", HAND_HINT);
        }
        Map<String, Map<String, String>> expected = new LinkedHashMap<>();
        before.forEach(key -> expected.put(key.key(), key.rules()));
        expected.put(added.key(), added.rules());
        Map<String, Map<String, String>> read = new LinkedHashMap<>();
        after.forEach(key -> read.put(key.key(), key.rules()));
        if (after.size() != before.size() + 1 || !read.equals(expected)) {
            throw new WriteRejectedException(
                    "schema/facts.yaml would not read back with exactly the new key", HAND_HINT);
        }
    }

    /**
     * {@code entry} after the last indented line of the facts block, with the indentation of the
     * block's first entry.
     */
    private static String withEntry(String text, String entry) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        int block = lines.indexOf(FACTS_BLOCK);
        if (block < 0) {
            throw new WriteRejectedException(
                    "schema/facts.yaml has no facts: block to add to", HAND_HINT);
        }
        // The block ends at the first line that is neither blank nor indented.
        int end = block + 1;
        while (end < lines.size()
                && (lines.get(end).isBlank() || Character.isWhitespace(lines.get(end).charAt(0)))) {
            end++;
        }
        int last =
                IntStream.range(block + 1, end)
                        .filter(i -> !lines.get(i).isBlank())
                        .max()
                        .orElse(block);
        String indent =
                lines.subList(block + 1, last + 1).stream()
                        .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
                        .findFirst()
                        .map(
                                line ->
                                        line.substring(
                                                0, line.length() - line.stripLeading().length()))
                        .orElseThrow(
                                () ->
                                        new WriteRejectedException(
                                                "schema/facts.yaml has no entry in its facts: block",
                                                HAND_HINT));
        lines.add(last + 1, indent + entry);
        return String.join("\n", lines);
    }

    private static String value(JsonNode value) {
        if (value.isArray()) {
            return "["
                    + String.join(", ", value.valueStream().map(SchemaAccept::plain).toList())
                    + "]";
        }
        return plain(value);
    }

    private static String plain(JsonNode value) {
        String text = value.isValueNode() ? value.asString() : "";
        if (!PLAIN.matcher(text).matches()) {
            throw refused();
        }
        return text;
    }

    private static WriteRejectedException refused() {
        return new WriteRejectedException("the proposal is no facts.yaml entry", HINT);
    }
}
