package com.bharath.projects.urlshortener.util;

import com.bharath.projects.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    /** Prod rules: private/local hosts rejected. */
    private final UrlValidator strict = new UrlValidator(2048, false);
    /** Local profile rules: private/local hosts allowed. */
    private final UrlValidator lenient = new UrlValidator(2048, true);

    // ---- accepted + normalized (same result under both rule sets) ----

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "https://example.com/a?b=1                    | https://example.com/a?b=1",
            "http://example.com                           | http://example.com",
            "  https://example.com/x  \t                  | https://example.com/x",
            "HTTPS://Example.COM/Path?Q=A#Frag            | https://example.com/Path?Q=A#Frag",
            "http://example.com:8080/x                     | http://example.com:8080/x",
            "https://example.com/a%20b?c=%E2%9C%93        | https://example.com/a%20b?c=%E2%9C%93",
            "https://sub.domain.example.co.uk/            | https://sub.domain.example.co.uk/",
            "https://example.com./x                       | https://example.com./x",
            "https://8.8.8.8/dns                          | https://8.8.8.8/dns",
            "https://203.0.113.5/x                        | https://203.0.113.5/x",
            "https://[2001:db8::1]/x                      | https://[2001:db8::1]/x",
            "https://[2001:DB8::1]:8443/x                 | https://[2001:db8::1]:8443/x",
            // just outside the private ranges
            "http://172.15.0.1/                           | http://172.15.0.1/",
            "http://172.32.0.1/                           | http://172.32.0.1/",
            "http://100.63.255.255/                       | http://100.63.255.255/",
            "http://100.128.0.1/                          | http://100.128.0.1/",
            "http://223.255.255.255/                      | http://223.255.255.255/",
    })
    void acceptsAndNormalizes(String input, String expected) {
        assertThat(strict.validateAndNormalize(input)).isEqualTo(expected);
        assertThat(lenient.validateAndNormalize(input)).isEqualTo(expected);
    }

    // ---- rejected under both rule sets ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "\t\n",
            "ftp://example.com",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "mailto:a@example.com",
            "//example.com",
            "example.com",
            "/relative/path",
            "http://",
            "http:///path",
            "https:example.com",
            "https://user:pass@example.com",
            "https://user@example.com",
            "https://exa mple.com",
            "https://example.com/a b",
            "https://example.com/caf\u00e9",
            "https://exa\u00e9mple.com",
            "https://example.com/\r\nSet-Cookie: x=1",
            "https://example.com/\u0000",
            "https://example.com/a|b",
            "https://example.com/{x}",
            "http://exa_mple.com",
            "http://-example.com",
            "http://example.com:0",
            "http://example.com:99999",
            "http://example.com:abc",
            // numeric host tricks: browsers read these as 127.0.0.1 etc.
            "http://2130706433/",
            "http://0x7f.1/",
            "http://0x7f000001/",
            "http://127.1/",
            "http://017700000001/",
            "http://01.02.03.04/",
            "http://256.1.1.1/",
            "http://1.2.3/",
            "http://1.2.3.4.5/",
            "http://[not-an-ip]/",
            "http://[fe80::1%25eth0]/",
    })
    void rejectsInvalidUrlsRegardlessOfProfile(String input) {
        assertThatThrownBy(() -> strict.validateAndNormalize(input)).isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> lenient.validateAndNormalize(input)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsUrlLongerThanMaximum() {
        String atLimit = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());
        String overLimit = atLimit + "a";

        assertThat(strict.validateAndNormalize(atLimit)).isEqualTo(atLimit);
        assertThatThrownBy(() -> strict.validateAndNormalize(overLimit))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void trimsSurroundingWhitespaceButNotInnerWhitespace() {
        assertThat(strict.validateAndNormalize("  \thttps://example.com/x \n"))
                .isEqualTo("https://example.com/x");
        assertThatThrownBy(() -> strict.validateAndNormalize(" https://example.com/x y "))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void honoursConfiguredMaxLength() {
        UrlValidator small = new UrlValidator(30, false);

        assertThatThrownBy(() -> small.validateAndNormalize("https://example.com/this-is-too-long"))
                .isInstanceOf(InvalidUrlException.class);
    }

    // ---- private/local hosts: rejected in prod rules, allowed in local rules ----

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://localhost",
            "http://localhost:8080/x",
            "http://LOCALHOST:8080/x",
            "http://localhost./x",
            "http://foo.localhost/",
            "http://printer.local/",
            "http://db.internal/",
            "http://intranet/",
            "http://127.0.0.1/",
            "http://127.255.255.254/",
            "http://0.0.0.0/",
            "http://10.0.0.1/",
            "http://10.255.255.255/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://100.127.255.255/",
            "http://198.18.0.1/",
            "http://224.0.0.1/",
            "http://255.255.255.255/",
            "http://[::1]/",
            "http://[::]/",
            "http://[fd00::1]/",
            "http://[fc00::1]/",
            "http://[fe80::1]/",
            "http://[ff02::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:10.0.0.1]/",
    })
    void privateAndLocalHostsAreRejectedInProdButAllowedLocally(String input) {
        assertThatThrownBy(() -> strict.validateAndNormalize(input))
                .isInstanceOf(InvalidUrlException.class);

        assertThat(lenient.validateAndNormalize(input)).isNotBlank();
    }

    @Test
    void localProfileKeepsLocalhostUrlsUsable() {
        assertThat(lenient.validateAndNormalize("HTTP://LocalHost:8080/Some/Path?x=1"))
                .isEqualTo("http://localhost:8080/Some/Path?x=1");
    }

    @Test
    void errorMessagesAreSafeToShow() {
        assertThatThrownBy(() -> strict.validateAndNormalize("ftp://example.com"))
                .hasMessage("Only http and https URLs are allowed");
    }
}
