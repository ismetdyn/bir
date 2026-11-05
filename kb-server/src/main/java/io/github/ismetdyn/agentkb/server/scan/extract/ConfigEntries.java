package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** A Spring config file flattened to dotted keys with the line of each value. */
final class ConfigEntries {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ConfigEntries() {}

    /**
     * A flattened key ({@code spring.datasource.url}), its relaxed form for matching (lower case,
     * no {@code -} or {@code _}, as Spring's relaxed binding), value, line (from 1) and the YAML
     * document ({@code ---} section, from 0) it is in.
     */
    record Entry(String key, String canonical, String value, int line, int document) {

        Entry(String key, String value, int line, int document) {
            this(
                    key,
                    Ids.normalize(key).replace("-", "").replace("_", ""),
                    value.strip(),
                    line,
                    document);
        }
    }

    /**
     * Scalars by dotted path; a list item is {@code key[i]}.
     *
     * @throws tools.jackson.core.JacksonException if the YAML cannot be parsed
     */
    static List<Entry> yaml(String text) {
        List<Entry> entries = new ArrayList<>();
        int document = -1;
        try (JsonParser parser = YAML.createParser(text)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                TokenStreamContext context = parser.streamReadContext();
                // Each --- document is one more value at the root of the stream.
                if ((token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY)
                        && context.getParent() != null
                        && context.getParent().inRoot()) {
                    document++;
                }
                String path = path(context);
                if (token.isScalarValue() && token != JsonToken.VALUE_NULL && !path.isEmpty()) {
                    entries.add(
                            new Entry(
                                    path,
                                    parser.getString(),
                                    parser.currentTokenLocation().getLineNr(),
                                    document));
                }
            }
        }
        return entries;
    }

    private static String path(TokenStreamContext context) {
        Deque<String> parts = new ArrayDeque<>();
        for (TokenStreamContext at = context; at != null && !at.inRoot(); at = at.getParent()) {
            if (at.inArray()) {
                parts.addFirst("[" + at.getCurrentIndex() + "]");
            } else if (at.currentName() != null) {
                parts.addFirst("." + at.currentName());
            }
        }
        String path = String.join("", parts);
        return path.startsWith(".") ? path.substring(1) : path;
    }

    /**
     * One entry per logical line, parsed by java.util.Properties itself, so escapes and the {@code
     * key value} and {@code key: value} forms read as Spring reads them.
     *
     * @throws IllegalArgumentException if a line holds a malformed unicode escape
     */
    static List<Entry> properties(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> lines = text.lines().toList();
        int index = 0;
        while (index < lines.size()) {
            int first = index;
            StringBuilder logical = new StringBuilder(lines.get(index));
            while (continues(lines.get(index)) && index + 1 < lines.size()) {
                index++;
                logical.append('\n').append(lines.get(index));
            }
            index++;
            Properties one = new Properties();
            try {
                one.load(new StringReader(logical.toString()));
            } catch (IOException e) {
                // A StringReader does not fail; this only satisfies the signature.
                throw new UncheckedIOException(e);
            }
            one.forEach(
                    (key, value) ->
                            entries.add(new Entry((String) key, (String) value, first + 1, 0)));
        }
        return entries;
    }

    /** An odd number of trailing backslashes continues a line that is not a comment. */
    private static boolean continues(String line) {
        String stripped = line.strip();
        if (stripped.startsWith("#") || stripped.startsWith("!")) {
            return false;
        }
        int backslashes = 0;
        for (int i = stripped.length() - 1; i >= 0 && stripped.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
