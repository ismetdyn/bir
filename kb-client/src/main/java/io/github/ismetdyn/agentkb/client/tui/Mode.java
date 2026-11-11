package io.github.ismetdyn.agentkb.client.tui;

import org.jspecify.annotations.Nullable;

/**
 * What has the keys, what an overlay returns to, the message line and whether the server answered
 * last time.
 */
final class Mode {

    /**
     * Letters are commands only in LIST and DETAIL; in SEARCH, FORM and PALETTE they are text; a
     * dialog (FORM, CONFIRM, HELP, PALETTE) takes every key.
     */
    enum Focus {
        LIST,
        SEARCH,
        DETAIL,
        FORM,
        CONFIRM,
        HELP,
        PALETTE
    }

    private Focus focus = Focus.LIST;
    private Focus back = Focus.LIST;
    private @Nullable String message;
    private boolean error;
    private boolean serverUp = true;

    Focus focus() {
        return focus;
    }

    /** The list or the detail, where an overlay returns to and which it is drawn over. */
    Focus back() {
        return back;
    }

    @Nullable String message() {
        return message;
    }

    boolean isError() {
        return error;
    }

    boolean isServerUp() {
        return serverUp;
    }

    boolean isOverlay() {
        return focus == Focus.FORM
                || focus == Focus.CONFIRM
                || focus == Focus.HELP
                || focus == Focus.PALETTE;
    }

    /** Moves the keys between the list, the search and the detail. */
    void go(Focus next) {
        focus = next;
    }

    /** Opens a dialog over the list or the detail. */
    void overlay(Focus dialog) {
        back = focus;
        focus = dialog;
    }

    /** Closes a dialog. */
    void close() {
        focus = back;
    }

    void say(String text, boolean isError) {
        message = text;
        error = isError;
    }

    void reach(boolean answered) {
        serverUp = answered;
    }
}
