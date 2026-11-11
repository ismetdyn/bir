package io.github.ismetdyn.agentkb.client;

/**
 * A failure shown to the user as one line; the message never carries a secret value. Public for kb
 * tui, which shows it in place.
 */
public final class KbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    KbException(String message) {
        this(message, Kb.FAILED);
    }

    /** {@code exitCode} as docs/CLIENTS.md gives it, e.g. {@link Kb#UNREACHABLE}. */
    KbException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }

    /** Whether the server could not be reached (exit 3), not that it refused. */
    public boolean isUnreachable() {
        return exitCode == Kb.UNREACHABLE;
    }
}
