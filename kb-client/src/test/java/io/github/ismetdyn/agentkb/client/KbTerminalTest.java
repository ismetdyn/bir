package io.github.ismetdyn.agentkb.client;

import java.util.List;
import java.util.Map;

/** {@link KbTerminalScenarios} on the JVM, the way the jar launcher of install.sh runs kb. */
class KbTerminalTest extends KbTerminalScenarios {

    @Override
    List<String> launcher() {
        return KbProcess.jvm(Map.of());
    }
}
