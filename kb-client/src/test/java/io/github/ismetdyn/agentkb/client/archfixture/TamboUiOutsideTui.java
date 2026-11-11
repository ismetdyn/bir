package io.github.ismetdyn.agentkb.client.archfixture;

import dev.tamboui.tui.event.KeyEvent;

/** ClientArchitectureTest: a kb-client class outside client.tui that uses TamboUI. */
public final class TamboUiOutsideTui {

    private TamboUiOutsideTui() {}

    public static boolean isQuit(KeyEvent key) {
        return key.isQuit();
    }
}
