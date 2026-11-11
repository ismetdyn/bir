package io.github.ismetdyn.agentkb.core.secret;

import io.github.ismetdyn.agentkb.core.Ids;
import java.util.Set;

/**
 * ADR-0026: well-known placeholder values a config file holds where a secret goes ({@code
 * POSTGRES_PASSWORD: postgres}), any value shorter than {@link #MIN_SECRET_CHARS} and a scanned
 * value of a single character class (letters only, digits only). As secret scanners do, they are
 * not secret values: the scanner keeps the Secret and its name without a value, so the word is
 * neither masked nor refused anywhere. Matched whole, in any case (Ids.normalize).
 */
public final class Placeholders {

    /** A value this short is no secret: never stored by a scan, never masked or refused. */
    public static final int MIN_SECRET_CHARS = 4;

    private static final Set<String> VALUES =
            Set.of(
                    "postgres",
                    "password",
                    "passwd",
                    "pass",
                    "changeme",
                    "change-me",
                    "change_me",
                    "secret",
                    "admin",
                    "root",
                    "user",
                    "guest",
                    "default",
                    "example",
                    "test",
                    "dummy",
                    "sample",
                    "qwerty",
                    "letmein",
                    "mysql",
                    "oracle",
                    "redis",
                    "123456",
                    "12345678",
                    "xxx",
                    "xxxx");

    private Placeholders() {}

    /**
     * Whether a scanned {@code value} is too short, one of the well-known placeholders (ignoring
     * case and outer space) or of a single character class.
     */
    public static boolean isPlaceholder(String value) {
        String plain = value.strip();
        return plain.length() < MIN_SECRET_CHARS
                || VALUES.contains(Ids.normalize(plain))
                || isOneClass(plain);
    }

    /**
     * F1: letters only (any script), digits only or neither: too little entropy for a value a scan
     * found, which is often a default left in a config file. A value set with PUT is not checked.
     */
    private static boolean isOneClass(String value) {
        return value.codePoints().allMatch(Character::isLetter)
                || value.codePoints().allMatch(Character::isDigit)
                || value.codePoints().noneMatch(Character::isLetterOrDigit);
    }
}
