package io.github.ismetdyn.agentkb.client.tui;

import java.util.List;

/**
 * One form field. {@code choices} are the schema's names it may pick from (fact keys, relation
 * types), else empty; ↑↓ pick among those that start with what was typed. A picked or typed value
 * is sent as it is; the server decides whether it is valid.
 */
final class Field {

    private final String name;
    private final List<String> choices;
    private String value;
    private String typed;
    private int pick = -1;

    Field(String name, String value, List<String> choices) {
        this.name = name;
        this.value = value;
        this.typed = value;
        this.choices = List.copyOf(choices);
    }

    String name() {
        return name;
    }

    String value() {
        return value;
    }

    /** The choices that start with what was typed, ASCII case ignored. */
    List<String> candidates() {
        return choices.stream()
                .filter(choice -> choice.regionMatches(true, 0, typed, 0, typed.length()))
                .toList();
    }

    void type(String text) {
        value = text;
        typed = text;
        pick = -1;
    }

    void pick(int step) {
        List<String> candidates = candidates();
        if (!candidates.isEmpty()) {
            pick = Math.floorMod(pick + step, candidates.size());
            value = candidates.get(pick);
        }
    }
}
