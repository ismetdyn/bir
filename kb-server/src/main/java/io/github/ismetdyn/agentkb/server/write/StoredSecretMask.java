package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import org.springframework.stereotype.Component;

/**
 * Masks the values stored on Secrets in a text as {@link WriteService#MASK} (docs/SECRETS.md,
 * invariant 2). A stored value is only found whole, so a caller that joins lines or cuts a text
 * before {@link WriteService#raiseSignal} masks the raw text with this first.
 */
@Component
public class StoredSecretMask {

    private final EmbeddedNeo4jStore store;

    public StoredSecretMask(EmbeddedNeo4jStore store) {
        this.store = store;
    }

    public String mask(String text) {
        return store.read(tx -> SecretWrite.maskStoredValues(tx, text, WriteService.MASK));
    }
}
