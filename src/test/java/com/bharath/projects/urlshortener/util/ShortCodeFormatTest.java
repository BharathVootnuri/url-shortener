package com.bharath.projects.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeFormatTest {

    private final ShortCodeFormat format = new ShortCodeFormat(7);

    @ParameterizedTest
    @ValueSource(strings = {"abcdefg", "ABCDEFG", "0123456", "aB3dE9x"})
    void acceptsBase62OfExactLength(String code) {
        assertThat(format.isWellFormed(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abcdef", "abcdefgh", "abc-efg", "abc efg", "abc/efg", "favicon.ico",
            "abcdef\u00e9", "../etc/", "abcdef%", "ab\ncdef"})
    void rejectsEverythingElse(String code) {
        assertThat(format.isWellFormed(code)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(format.isWellFormed(null)).isFalse();
    }
}
