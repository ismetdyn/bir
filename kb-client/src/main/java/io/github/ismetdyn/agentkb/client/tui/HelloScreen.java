package io.github.ismetdyn.agentkb.client.tui;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.toolkit.element.Element;

/**
 * The first screen of {@code kb tui} (plan.md H1 spike): it names the product and how to leave. The
 * screens of H8 replace it.
 */
final class HelloScreen {

    private HelloScreen() {}

    static Element view() {
        return panel(
                        "agentkb",
                        text("agentkb: the local knowledge base.").bold(),
                        text("q: quit").dim())
                .rounded();
    }
}
