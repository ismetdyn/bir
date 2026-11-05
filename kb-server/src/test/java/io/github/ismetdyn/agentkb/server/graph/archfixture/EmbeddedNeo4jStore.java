package io.github.ismetdyn.agentkb.server.graph.archfixture;

/**
 * Stands in for the real store in ArchitectureRulesTest, which matches it by simple name: the
 * fixtures keep compiling whatever signature read and write settle on. Never run; only its class
 * file is read.
 */
public class EmbeddedNeo4jStore {

    public void write() {}

    public Object database() {
        return this;
    }

    public static final class Subclass extends EmbeddedNeo4jStore {}
}
