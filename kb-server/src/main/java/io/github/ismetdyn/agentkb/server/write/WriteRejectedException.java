package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.SecretMask;

/**
 * A new write that breaks a rule (ARCHITECTURE §5 step 1); nothing of it was written. The message
 * says what is wrong and the hint how to go on (docs/MCP-TOOLS.md). Neither carries a value of the
 * request (invariant 2).
 */
public class WriteRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String hint;

    public WriteRejectedException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }

    /**
     * ADR-0026: a refusal may name a record whose id holds a value stored after it was written
     * (RequestWrite: more than one entity has this hostname). No cause: its message holds the id
     * unmasked.
     */
    WriteRejectedException masked(SecretMask mask) {
        return new WriteRejectedException(mask.text(getMessage()), mask.text(hint));
    }
}
