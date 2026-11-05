package io.github.ismetdyn.agentkb.client;

/** A failure shown to the user as one line; the message never carries a secret value. */
final class KbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    KbException(String message) {
        super(message);
    }
}
