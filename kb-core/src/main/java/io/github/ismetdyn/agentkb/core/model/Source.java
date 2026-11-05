package io.github.ismetdyn.agentkb.core.model;

/** SPEC §7. The curator has no trust level there; 0 means it never outranks another source. */
public enum Source {
    USER(4),
    USER_STATED(3),
    SCAN(2),
    AGENT(1),
    CURATOR(0);

    private final int trust;

    Source(int trust) {
        this.trust = trust;
    }

    public int trust() {
        return trust;
    }
}
