package io.github.ismetdyn.agentkb.core.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lead decision (C): a webhook URL whose path is the credential looks like a secret, so the scanner
 * writes no url fact for it. Shapes checked on 2026-11-05 in vendor docs (Context7
 * /websites/slack_dev, /discord/discord-api-docs): Slack incoming webhooks and workflow triggers,
 * Discord execute-webhook with and without an API version. The hosts and documentation pages
 * without a token stay ordinary, and the patterns stay linear (SecretPatternsTest). None of the
 * tokens is real.
 *
 * <p>Lead decision CP5 (3): Telegram bot URLs, {@code api.telegram.org/bot<id>:<token>/<method>}
 * (Context7 /websites/core_telegram_bots_api, 2026-11-05: "https://api.telegram.org/bot<token>/
 * METHOD_NAME", downloads under {@code /file/bot<token>/}). The docs only say the token "looks
 * something like" {@code 123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11}, a 34-character secret part;
 * lead decision CP5 (b) takes 30 to 40 characters of {@code [A-Za-z0-9_-]}, so 29 is no token. Host
 * names are case-insensitive (DNS), so an upper-case host is the same webhook.
 */
class WebhookSecretPatternsTest {

    // 68 characters of the base64url alphabet, the length of a Discord webhook token.
    private static final String DISCORD_TOKEN =
            "Xy7Kp2Qm9Lw4Rt8Vn3Bc6Hd1Fg5Js0Za-Xy7Kp2Qm9Lw4Rt8Vn3_Bc6Hd1Fg5Js0ZaQm";

    // A bot id and a 35-character token part of the base64url alphabet; no real bot.
    private static final String TELEGRAM_TOKEN =
            "7123456789:" + "AAH" + "kbtestFakeTelegramBotQ0000_-xyzW";

    // The token example of the Telegram docs: a 34-character secret part.
    private static final String TELEGRAM_DOCS_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";

    // The longest (40) and one below the shortest (29) secret part of lead decision CP5 (b).
    private static final String TELEGRAM_TOKEN_40 =
            "7123456789:kbtestFakeTelegramBot40_-00000000000xyzW";
    private static final String TELEGRAM_TOKEN_29 = "7123456789:kbtestFakeTelegramBot29_-xyzW";

    private Locale defaultLocale;

    @BeforeEach
    void rememberLocale() {
        defaultLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @ParameterizedTest(name = "webhook: {0}")
    @MethodSource("webhooks")
    void a_webhook_url_with_its_token_is_a_secret(String value) {
        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isTrue();
    }

    /** CLAUDE.md: a host lower-cased under tr-TR turns the I of DISCORD into ı. */
    @ParameterizedTest(name = "webhook under tr-TR: {0}")
    @MethodSource("webhooks")
    void detection_does_not_depend_on_the_default_locale(String value) {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isTrue();
    }

    @ParameterizedTest(name = "not a secret: {0}")
    @MethodSource("ordinaryValues")
    void hosts_and_doc_pages_without_a_token_are_not_secrets(String value) {
        assertThat(SecretPatterns.looksLikeSecret(value)).as(value).isFalse();
    }

    private static Stream<String> ordinaryValues() {
        return Stream.of(
                "https://api.telegram.org",
                "https://api.telegram.org/bot",
                "https://api.telegram.org/bot<token>/getMe",
                "https://api.telegram.org/bot123456/getMe",
                "https://api.telegram.org/bot" + TELEGRAM_TOKEN_29 + "/getMe",
                "https://core.telegram.org/bots/api",
                "https://API.TELEGRAM.ORG/bot<token>/METHOD_NAME",
                "https://HOOKS.SLACK.COM/services/",
                "https://DISCORD.COM/api/webhooks",
                "https://Discord.com/developers/docs/resources/webhook");
    }

    private static Stream<String> webhooks() {
        return Stream.of(
                "https://api.telegram.org/bot" + TELEGRAM_TOKEN + "/sendMessage",
                "https://api.telegram.org/bot" + TELEGRAM_DOCS_TOKEN + "/getMe",
                "https://api.telegram.org/bot" + TELEGRAM_TOKEN_40 + "/getMe",
                "TELEGRAM_URL=https://api.telegram.org/bot" + TELEGRAM_TOKEN + "/getMe",
                "https://api.telegram.org/file/bot" + TELEGRAM_TOKEN + "/photos/file_1.jpg",
                "https://API.Telegram.ORG/bot" + TELEGRAM_TOKEN + "/getUpdates",
                "https://HOOKS.SLACK.COM/services/"
                        + "T0KBTEST01/B0KBTEST01/kbtestFakeSlackHook00001",
                "https://Hooks.Slack.Com/triggers/"
                        + "T0123456789/3141592653589/c6e6c0d868b3054ca0f4611a5dbadaf0",
                "https://DISCORD.COM/api/webhooks/" + "347114750880120863/" + DISCORD_TOKEN,
                "https://DiscordApp.Com/api/v10/webhooks/" + "347114750880120863/" + DISCORD_TOKEN,
                "https://hooks.slack.com/services/"
                        + "T0KBTEST01/B0KBTEST01/kbtestFakeSlackHook00001",
                "SLACK_URL=https://hooks.slack.com/services/"
                        + "T0123456789/B1001010101/kbtestFakeSlackHook00002",
                "https://hooks.slack.com/triggers/"
                        + "T0123456789/3141592653589/c6e6c0d868b3054ca0f4611a5dbadaf0",
                "https://discord.com/api/webhooks/" + "347114750880120863/" + DISCORD_TOKEN,
                "https://discord.com/api/v10/webhooks/" + "347114750880120863/" + DISCORD_TOKEN);
    }
}
