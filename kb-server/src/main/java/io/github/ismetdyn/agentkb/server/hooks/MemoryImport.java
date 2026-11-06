package io.github.ismetdyn.agentkb.server.hooks;

import io.github.ismetdyn.agentkb.server.write.StoredSecretMask;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code kb import-memories} on the server (docs/CURATOR.md §2, ADR-0022 decision 3): each memory
 * of Claude Code auto memory or Codex Memories becomes a raw {@code imported-memory} Signal. Its
 * key is the SHA-256 of the masked content, so the same content again, from any file, opens no
 * second signal (SPEC §15). What looks like a secret is masked as {@code ****} line by line, so the
 * rest of a memory stays (docs/SECRETS.md); a private key block is masked whole. Nothing of a
 * memory is logged (invariant 10).
 */
@Component
public class MemoryImport {

    static final String KIND = "imported-memory";
    private static final Pattern KEY_START =
            Pattern.compile("BEGIN [A-Z0-9 ]{0,64}PRIVATE KEY|PuTTY-User-Key-File-");
    private static final Pattern KEY_END =
            Pattern.compile("END [A-Z0-9 ]{0,64}PRIVATE KEY|Private-MAC:");

    /** One memory file as the client read it; {@code path} is relative to the user's home. */
    public record Memory(String source, String path, String text) {}

    private final WriteService writes;
    private final StoredSecretMask stored;

    public MemoryImport(WriteService writes, StoredSecretMask stored) {
        this.writes = writes;
        this.stored = stored;
    }

    /**
     * @return how many signals opened; a memory whose content is already a signal opens none
     */
    public int importAll(List<Memory> memories) {
        Instant at = Instant.now();
        int opened = 0;
        for (Memory memory : memories) {
            if (memory.text().isBlank()) {
                continue;
            }
            String text = masked(stored.mask(memory.text()));
            String payload =
                    memory.source() + " " + CaptureService.mask(memory.path()) + "\n" + text;
            if (writes.raiseSignal(KIND, sha256(text), payload, at)) {
                opened++;
            }
        }
        return opened;
    }

    /** Each line as CaptureService masks it; every line of a private key block as a whole. */
    static String masked(String text) {
        StringJoiner out = new StringJoiner("\n");
        boolean inKey = false;
        for (String line : text.split("\n", -1)) {
            if (KEY_START.matcher(line).find()) {
                inKey = true;
            }
            out.add(inKey ? WriteService.MASK : CaptureService.mask(line));
            if (inKey && KEY_END.matcher(line).find()) {
                inKey = false;
            }
        }
        return out.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has SHA-256 (MessageDigest javadoc).
            throw new IllegalStateException(e);
        }
    }
}
