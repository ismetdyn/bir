package io.github.ismetdyn.agentkb.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies a child's output and replaces each secret form with {@code ****} (docs/SECRETS.md §3).
 * Works on bytes, so every other byte passes unchanged whatever its encoding. A tail that could
 * still grow into a form is held back until the next read decides, so a value split across writes
 * is masked too; at the end of the stream the tail goes out as it is.
 */
final class OutputMasker {

    static final byte[] MASK = "****".getBytes(StandardCharsets.US_ASCII);
    private static final int READ_SIZE = 8192;

    private final List<byte[]> forms;
    private final OutputStream to;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** {@code forms} longest first, so a longer form is not cut by a shorter one inside it. */
    OutputMasker(List<byte[]> forms, OutputStream to) {
        this.forms = forms;
        this.to = to;
    }

    /** Each value as it is, base64 and URL-encoded (with + and with %20 for a space). */
    static List<byte[]> forms(List<String> values) {
        Set<String> texts = new LinkedHashSet<>();
        for (String value : values) {
            String urlEncoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            texts.add(value);
            texts.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
            texts.add(urlEncoded);
            texts.add(urlEncoded.replace("+", "%20"));
        }
        return texts.stream()
                .filter(text -> !text.isEmpty())
                .map(text -> text.getBytes(StandardCharsets.UTF_8))
                .sorted(Comparator.comparingInt((byte[] form) -> form.length).reversed())
                .toList();
    }

    void copy(InputStream from) throws IOException {
        byte[] chunk = new byte[READ_SIZE];
        for (int read = from.read(chunk); read >= 0; read = from.read(chunk)) {
            pending.write(chunk, 0, read);
            drain(false);
        }
        drain(true);
    }

    /** Writes out everything decided; keeps a possible start of a form unless {@code atEnd}. */
    private void drain(boolean atEnd) throws IOException {
        byte[] buffer = pending.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(buffer.length);
        int at = 0;
        while (at < buffer.length) {
            // A longer form may still complete here, so it is waited for before a shorter match.
            if (!atEnd && couldStartForm(buffer, at)) {
                break;
            }
            int match = matchLength(buffer, at);
            if (match > 0) {
                out.write(MASK);
                at += match;
            } else {
                out.write(buffer[at++]);
            }
        }
        pending.reset();
        pending.write(buffer, at, buffer.length - at);
        out.writeTo(to);
        to.flush();
    }

    /** The length of the longest form at {@code at}, 0 if none is there. */
    private int matchLength(byte[] buffer, int at) {
        for (byte[] form : forms) {
            if (at + form.length <= buffer.length
                    && Arrays.equals(buffer, at, at + form.length, form, 0, form.length)) {
                return form.length;
            }
        }
        return 0;
    }

    /** The rest of the buffer from {@code at} is a proper start of some form. */
    private boolean couldStartForm(byte[] buffer, int at) {
        int rest = buffer.length - at;
        for (byte[] form : forms) {
            if (rest < form.length && Arrays.equals(buffer, at, buffer.length, form, 0, rest)) {
                return true;
            }
        }
        return false;
    }
}
