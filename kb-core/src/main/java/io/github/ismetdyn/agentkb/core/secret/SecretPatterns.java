package io.github.ismetdyn.agentkb.core.secret;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPEC §7 rule 8 and docs/SECRETS.md: values that look like a secret. Known token shapes, private
 * keys, Basic credentials, a password inside a URL, a webhook URL with its token in the path and a
 * {@code name = value} assignment whose name says secret, also URL-encoded (ADR-0022 decision 31).
 * A reference such as {@code ${DB_PASSWORD}} holds no value and is not one. No pattern lower-cases
 * the input, so the default locale cannot change a match (CLAUDE.md).
 */
public final class SecretPatterns {

    // Names that mark the value after them as a secret. UNICODE_CASE folds ş/Ş and İ, ı against i
    // by character, without a locale, so Şifre, ŞİFRE and ŞIFRE all match.
    private static final String SECRET_NAME =
            "(?:password|passwd|passphrase|pwd|parola|şifre|sifre|secret|token|credentials?"
                    + "|api[_-]?key|access[_-]?key|private[_-]?key)";

    // Every pattern stays linear on agent input (ReDoS): a run is taken possessively (*+, ++) when
    // the next token cannot be one of its characters, a minimum length is checked without a
    // trailing open-ended run ({16} where find() needs no more), and a pattern that could start
    // anywhere in a long run either may start only at the beginning of a run (a lookbehind) or
    // has a bounded run.
    private static final List<Pattern> PATTERNS =
            List.of(
                    // AWS access key id.
                    Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
                    // GitHub tokens (ghp_, gho_, ghu_, ghs_, ghr_) and fine-grained ones.
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36}"),
                    Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22}"),
                    // GitLab personal access, deploy and runner tokens.
                    Pattern.compile("\\bgl(?:pat|dt|rt)-[A-Za-z0-9_-]{20}"),
                    // Slack tokens, refresh tokens (xoxe) and app-level tokens (xapp).
                    Pattern.compile("\\b(?:xox[abposre]|xapp)-[A-Za-z0-9-]{10}"),
                    // Anthropic (sk-ant-) and OpenAI (sk-, sk-proj-, sk-admin-) keys. Mixed case,
                    // a digit and 32 characters keep host names such as sk-prod-db01 out.
                    Pattern.compile(
                            "(?<![A-Za-z0-9_-])sk-(?=[a-z0-9_-]*+[A-Z])(?=[A-Za-z_-]*+[0-9])"
                                    + "[A-Za-z0-9_-]{32}"),
                    // Legacy OpenAI keys: sk- and only lower-case hex, 32 characters or more. A git
                    // SHA or a UUID has no sk- prefix.
                    Pattern.compile("(?<![A-Za-z0-9_-])sk-[0-9a-f]{32,}+(?![A-Za-z0-9_-])"),
                    // OpenSSH, RSA, EC, PKCS#8, armored PGP ... private keys; SSH2 (RFC 4716)
                    // frames them with four dashes and spaces. PuTTY files.
                    Pattern.compile("----[- ]BEGIN [A-Z0-9 ]{0,64}PRIVATE KEY[A-Z ]{0,16}----"),
                    Pattern.compile("\\bPuTTY-User-Key-File-[0-9]++:"),
                    // HTTP Basic credentials: base64 of user:password in an Authorization header,
                    // also as a JSON key, a dict entry or an assignment.
                    Pattern.compile(
                            "\\bAuthorization[\"']?\\s*+[:=]\\s*+[\"']?Basic\\s++[A-Za-z0-9+/]{4}",
                            Pattern.CASE_INSENSITIVE),
                    // JWT: header.payload.signature, both JSON parts base64url of '{"'.
                    Pattern.compile(
                            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]++\\.eyJ[A-Za-z0-9_-]++\\."
                                    + "[A-Za-z0-9_-]"),
                    // [user]:password@host in a URL (redis://:pw@host has no user); a ${...}
                    // reference is no value. A scheme has at most 32 characters.
                    Pattern.compile(
                            "[A-Za-z][A-Za-z0-9+.-]{0,31}+://[^/\\s:@]*+:(?!\\$\\{)[^/\\s@]++@"),
                    // Webhook URLs whose path is the credential (lead decision C; shapes from the
                    // vendor docs, 2026-11-05): Slack incoming webhooks and workflow triggers,
                    // hooks.slack.com/{services|triggers}/<team>/<id>/<token>; Discord
                    // execute-webhook, /api[/v<n>]/webhooks/<id>/<token>. Telegram bot API
                    // (Context7 /websites/core_telegram_bots_api, 2026-11-05):
                    // api.telegram.org/bot<token>/METHOD_NAME and /file/bot<token>/<file_path>,
                    // the token <bot id>:<30 to 40 of [A-Za-z0-9_-]> (lead decision b; the docs
                    // example has 34). find() needs only the first 30, so a longer token still
                    // counts; there is no end check. The bare hosts and doc
                    // pages carry no token and stay ordinary. A host name is case-insensitive
                    // (DNS), so only the host is matched with (?i:...), ASCII-only and so without a
                    // locale; the path and token stay case-sensitive.
                    Pattern.compile(
                            "\\b(?i:hooks\\.slack\\.com)/(?:services|triggers)/[A-Za-z0-9]++/"
                                    + "[A-Za-z0-9]++/[A-Za-z0-9]{8}"),
                    Pattern.compile(
                            "\\b(?i:discord(?:app)?\\.com)/api(?:/v[0-9]{1,3})?/webhooks/[0-9]++/"
                                    + "[A-Za-z0-9_-]{16}"),
                    Pattern.compile(
                            "\\b(?i:api\\.telegram\\.org)/(?:file/)?bot[0-9]++:[A-Za-z0-9_-]{30,40}"),
                    // An opaque bearer token, as in an Authorization header.
                    Pattern.compile(
                            "\\bBearer\\s++[A-Za-z0-9._~+/-]{16}", Pattern.CASE_INSENSITIVE),
                    // password=..., DB_PASSWORD=..., client_secret: ..., "api_key": "...". At most
                    // 64 name characters follow the secret word.
                    Pattern.compile(
                            SECRET_NAME
                                    + "[A-Za-z0-9_.-]{0,64}+[\"']?\\s*+[:=]\\s*+[\"']?(?!\\$\\{)"
                                    + "[^\\s\"']{3}",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));

    private static final Pattern PERCENT_ESCAPES = Pattern.compile("(?:%[0-9A-Fa-f]{2})+");

    private SecretPatterns() {}

    /** The value as it is and, if it holds percent escapes, URL-decoded once. */
    public static boolean looksLikeSecret(String value) {
        return matches(value) || value.indexOf('%') >= 0 && matches(decodeOnce(value));
    }

    private static boolean matches(String value) {
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    /**
     * Only well-formed %XX runs are decoded, as UTF-8, so a bare percent sign (100%, fe80::1%eth0)
     * stays as it is instead of failing the way URLDecoder does.
     */
    private static String decodeOnce(String value) {
        return PERCENT_ESCAPES
                .matcher(value)
                .replaceAll(
                        escapes -> {
                            String run = escapes.group();
                            byte[] bytes = new byte[run.length() / 3];
                            for (int i = 0; i < bytes.length; i++) {
                                bytes[i] =
                                        (byte) HexFormat.fromHexDigits(run, i * 3 + 1, i * 3 + 3);
                            }
                            return Matcher.quoteReplacement(
                                    new String(bytes, StandardCharsets.UTF_8));
                        });
    }
}
