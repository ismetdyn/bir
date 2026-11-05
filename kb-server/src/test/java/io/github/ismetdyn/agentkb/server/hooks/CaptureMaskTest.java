package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * docs/SECRETS.md: CaptureService masks from where the first secret-looking part starts, so a
 * secret spread over words, a second secret and a key body after its header are all gone. The
 * values are fake.
 */
class CaptureMaskTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "the db password: kbtest-Hunter-77 is old | the db ****",
                "Bearer kbtestFAKEtoken0123456789 then token=kbtest-two-9 | ****",
                "key -----BEGIN RSA PRIVATE KEY----- MIIEkbtestFAKEbody -----END RSA PRIVATE KEY-----"
                        + " | key ****",
                "which subnet? 10.20.0.0/16 | which subnet? 10.20.0.0/16"
            })
    void masks_from_the_first_secret_to_the_end(String text, String expected) {
        assertThat(CaptureService.mask(text)).isEqualTo(expected);
    }
}
