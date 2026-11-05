package io.github.ismetdyn.agentkb.core.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC §7 rule 8 and docs/SECRETS.md: kb_remember rejects a secret-looking value, so a false
 * positive blocks ordinary knowledge (hostnames, ids, addresses) and a false negative puts a secret
 * into the snapshot and git. Tokens are put together from parts so that no full token sits in the
 * source for a secret scanner to find; none of them is real.
 */
class SecretPatternsTest {

    // A key body in the base64url alphabet; no real key.
    private static final String LONG_TAIL =
            "Xy7Kp2Qm9Lw4Rt8Vn3Bc6Hd1Fg5Js0Za-Xy7Kp2Qm9Lw4Rt8Vn3_Bc6Hd1Fg5Js0ZaQm9Lw4Rt8Vn3Bc6Hd1Fg5";

    // SPEC §6.1 body limit (WriteCheck.BODY_LIMIT) and the time one check may take on it.
    private static final int WORST_CASE_LENGTH = 4_000;
    private static final Duration WORST_CASE_LIMIT = Duration.ofMillis(50);

    private Locale defaultLocale;

    @BeforeEach
    void rememberLocale() {
        defaultLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @ParameterizedTest(name = "secret: {0}")
    @MethodSource("secrets")
    void a_secret_looking_value_is_detected(String value) {
        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isTrue();
    }

    /** CLAUDE.md: under a Turkish default locale toLowerCase turns the I of API_KEY into ı. */
    @ParameterizedTest(name = "secret under tr-TR: {0}")
    @MethodSource("secrets")
    void detection_does_not_depend_on_the_default_locale(String value) {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isTrue();
    }

    @ParameterizedTest(name = "not a secret: {0}")
    @MethodSource("ordinaryValues")
    void ordinary_knowledge_is_not_taken_for_a_secret(String value) {
        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isFalse();
    }

    /**
     * kb_remember runs every pattern on agent input, so a pattern that backtracks badly is a denial
     * of service. Each worst case is as long as the longest value WriteCheck lets through.
     */
    @ParameterizedTest(name = "linear on: {0}")
    @MethodSource("worstCases")
    void every_check_stays_linear_on_long_adversarial_input(String unit) {
        String value = unit.repeat(WORST_CASE_LENGTH / unit.length() + 1);
        value = value.substring(0, WORST_CASE_LENGTH);
        // The first run compiles; a pattern that backtracks is slow on the second run too.
        SecretPatterns.looksLikeSecret(value);

        long started = System.nanoTime();
        SecretPatterns.looksLikeSecret(value);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as(unit).isLessThan(WORST_CASE_LIMIT);
    }

    /** Units repeated to 4,000 characters: runs without separators and near misses. */
    private static Stream<String> worstCases() {
        return Stream.of(
                "a",
                " ",
                "password",
                "password ",
                "_password",
                "password=",
                "password:\"",
                "şifre",
                "a:",
                "a://",
                "a://a:",
                "eyJ",
                "eyJ-",
                "eyJa.",
                "sk-",
                "sk-a",
                "sk-A1",
                "-----BEGIN ",
                "---- BEGIN A",
                "-----BEGIN PRIVATE KEY",
                "Authorization: ",
                "Authorization: Basic ",
                "Bearer ",
                "ghp_",
                "%41",
                "%",
                "=",
                "hooks.slack.com/services/",
                "hooks.slack.com/services/T0/",
                "discord.com/api/webhooks/",
                "discord.com/api/webhooks/1/",
                "1",
                "api.telegram.org/bot",
                "api.telegram.org/bot1",
                "api.telegram.org/bot1:",
                "api.telegram.org/file/bot1:",
                "API.TELEGRAM.ORG/bot1:",
                "HOOKS.SLACK.COM/services/T0/",
                "Hooks.Slack.Com/",
                "DISCORD.COM/api/webhooks/1/");
    }

    private static Stream<String> secrets() {
        return Stream.of(
                // AWS access key id (the example id of the AWS documentation).
                "AKIA" + "IOSFODNN7EXAMPLE",
                // GitHub personal access token: ghp_ and 36 characters.
                "ghp" + "_1a2B3c4D5e6F7g8H9i0J1k2L3m4N5o6P7q8R",
                // GitLab personal access token.
                "glpat" + "-x1Y2z3A4b5C6d7E8f9G0",
                // Slack bot token.
                "xox" + "b-1234567890-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx",
                // Private keys, OpenSSH and PEM.
                "-----BEGIN OPENSSH " + "PRIVATE KEY-----",
                "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIEow",
                // JWT (header.payload.signature).
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                        + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0"
                        + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                // A password inside a URL or a JDBC URL.
                "postgres://app:" + "Sup3rS3cret" + "@ornek-db:5432/app",
                // Password only, the usual Redis and Valkey form, and a named user.
                "redis://:" + "s3cr3tpass" + "@ornek-redis:6379/0",
                "rediss://default:" + "s3cr3tpass" + "@ornek-redis:6380",
                "jdbc:postgresql://ornek-db:5432/ORNEKPDB?user=app&password=" + "Sup3rS3cret",
                // key = value assignments with a literal value, in upper and lower case.
                "password=" + "Sup3rS3cret",
                "DB_PASSWORD=" + "Sup3rS3cret",
                "API_KEY=" + "Sup3rS3cretValue",
                "client_secret: " + "Sup3rS3cretValue",
                // Turkish names in every case form; a pattern that lower-cases misses some.
                "root şifre: " + "Sup3rS3cret",
                "Şifre=" + "Sup3rS3cret",
                "ŞİFRE=" + "Sup3rS3cret",
                "ŞIFRE=" + "Sup3rS3cret",
                "db_sifre=" + "Sup3rS3cret",
                "ssh key passphrase=" + "Sup3rS3cret",
                "credentials: " + "Sup3rS3cret",
                // An opaque bearer token that is no JWT.
                "curl -H 'Authorization: Bearer " + "9f8e7d6c5b4a39281706f5e4d3c2b1a0'",
                // ADR-0022 decision 31 (h): armored PGP and other private key headers.
                "-----BEGIN PGP " + "PRIVATE KEY BLOCK-----",
                // SSH2 (RFC 4716) frames its header with four dashes and spaces.
                "---- BEGIN SSH2 ENCRYPTED " + "PRIVATE KEY ----",
                "PuTTY-User-Key-File-3: " + "ssh-ed25519",
                // HTTP Basic credentials are base64 of user:password.
                "Authorization: Basic " + "YWRtaW46UzNjcjN0UGFzcw==",
                "curl -H 'authorization: basic " + "b3JuZWs6czNjcjN0cGFzcw=='",
                // The same header as a JSON key (Postman export) and a Python dict entry.
                "{\"Authorization\": \"Basic " + "YWRtaW46UzNjcjN0UGFzcw==\"}",
                "headers = {'Authorization': 'Basic " + "YWRtaW46UzNjcjN0UGFzcw=='}",
                "authorization=Basic " + "YWRtaW46UzNjcjN0UGFzcw==",
                // Bare provider keys. Prefixes checked on 2026-11-05 in vendor docs (Context7):
                // Anthropic sk-ant-api03- and sk-ant-admin01-, OpenAI sk-proj- and sk-admin-,
                // Slack xoxp-, xapp-, xoxe-, GitLab gldt- (deploy) and glrt- (runner).
                "sk-ant-" + "api03-" + LONG_TAIL,
                "sk-ant-" + "admin01-" + LONG_TAIL,
                "sk-" + "proj-" + LONG_TAIL,
                "sk-" + "admin-" + LONG_TAIL,
                "sk-" + "Qm9Lw4Rt8Vn3Bc6Hd1Fg5Js0ZaXy7Kp2Qm9Lw4Rt8Vn3Bc6H",
                // A legacy key of 32 lower-case hex characters.
                "sk-" + "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "OPENAI_KEY " + "sk-" + "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d",
                "xox" + "p-1234567890-1234567890123-1234567890123-0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "xap" + "p-1-A0123BCDEFG-1234567890123-" + "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "xox" + "e-1-" + LONG_TAIL,
                "gld" + "t-x1Y2z3A4b5C6d7E8f9G0",
                "glr" + "t-x1Y2z3A4b5C6d7E8f9G0",
                // URL-encoded forms are decoded once, then checked.
                "password%3D" + "Sup3rS3cret",
                "api_key%3A+" + "Sup3rS3cretValue",
                "postgres%3A%2F%2Fapp%3A" + "Sup3rS3cret" + "%40ornek-db%3A5432%2Fapp",
                "Authorization%3A%20Bearer%20" + "9f8e7d6c5b4a39281706f5e4d3c2b1a0",
                "-----BEGIN%20RSA%20" + "PRIVATE%20KEY-----");
    }

    private static Stream<String> ordinaryValues() {
        return Stream.of(
                "ornek-app-01.ornek.example",
                "ornek-app-01",
                "3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c",
                "192.0.2.21",
                "2001:db8::1",
                "198.51.100.7/24",
                "8443/https",
                "jdbc:postgresql://ornek-db:5432/ORNEKPDB",
                "https://ornek-lb.ornek.example/health",
                "registry.ornek.example/app:1.4.2",
                "spring-boot 3.5.6",
                // A fact id: 26 upper-case characters must not read as a key.
                "01KR8HTSM011D9CED76QYB9Z3M",
                // The placeholder is how a secret is meant to be referred to (docs/SECRETS.md).
                "kb:secret/ornek-db/prod/app-password",
                // A reference to an environment variable holds no value (G10: not a secret).
                "${DB_PASSWORD}",
                "password=${DB_PASSWORD}",
                // The word alone is knowledge about a secret, not the secret.
                "Parola kasada durur; erişim bastion üzerinden",
                "Şifre kasada durur",
                "password rotation is monthly",
                "Bearer authentication through Keycloak",
                // A URL without credentials, or with a reference in place of the password.
                "redis://ornek-redis:6379",
                "redis://:${REDIS_PASSWORD}@ornek-redis:6379",
                // Decision 31 (h) must keep these ordinary: hosts and words with sk-, Basic as a
                // scheme name, upper-case UUIDs, versions, Turkish sentences.
                "sk-prod-db01.ornek.example",
                "risk-free rollout on task-runner-01 and disk-usage alerts",
                "Basic authentication is off; login goes through Keycloak",
                "WWW-Authenticate: Basic realm=\"ornek\"",
                "3F2B8C1E-9D4A-4E7B-A1C2-5D6E7F8A9B0C",
                // Hex without the sk- prefix: a git SHA, an md5 sum, a compact UUID; and a short
                // sk- hex id.
                "commit 9f8e7d6c5b4a39281706f5e4d3c2b1a0f9e8d7c6",
                "md5 0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "disk-0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "sk-0a1b2c3d4e5f",
                "3f2b8c1e9d4a4e7ba1c25d6e7f8a9b0c",
                "1.4.2-SNAPSHOT",
                "v2.31.0",
                "Java 21.0.5+11",
                "Veritabanı yedeği her gece 02:00'de alınır; yedekler İzmir'deki sunucuda tutulur.",
                // A percent sign that is no escape must neither throw nor flag (decode once).
                "SLA 99.9%, 100% uptime hedefi; %50 kapasite",
                "fe80::1%eth0",
                "https://ornek.example/ara?q=%C4%B0zmir%20veri%20merkezi",
                // Decision (C) must keep the hosts and documentation pages ordinary: no id, no
                // token in the path.
                "https://hooks.slack.com/services/",
                "https://api.slack.com/messaging/webhooks",
                "https://discord.com/api/webhooks",
                "https://discord.com/developers/docs/resources/webhook",
                "");
    }
}
