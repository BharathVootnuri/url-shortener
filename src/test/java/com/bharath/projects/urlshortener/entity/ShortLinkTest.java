package com.bharath.projects.urlshortener.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ShortLinkTest {

    private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void neverExpiresWithoutExpiry() {
        ShortLink link = new ShortLink("abc1234", "https://example.com", T, null);

        assertThat(link.isExpiredAt(T.plusSeconds(10L * 365 * 24 * 3600))).isFalse();
    }

    @Test
    void activeJustBeforeExpiry() {
        ShortLink link = new ShortLink("abc1234", "https://example.com", T, T.plusSeconds(60));

        assertThat(link.isExpiredAt(T.plusSeconds(59))).isFalse();
    }

    @Test
    void expiredExactlyAtExpiryInstant() {
        Instant expiry = T.plusSeconds(60);
        ShortLink link = new ShortLink("abc1234", "https://example.com", T, expiry);

        assertThat(link.isExpiredAt(expiry)).isTrue();
    }

    @Test
    void expiredAfterExpiry() {
        ShortLink link = new ShortLink("abc1234", "https://example.com", T, T.plusSeconds(60));

        assertThat(link.isExpiredAt(T.plusSeconds(61))).isTrue();
    }
}
