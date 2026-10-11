package com.bharath.projects.urlshortener.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    /** Returns the given values for successive nextInt calls, then 1 forever. */
    private static Random scripted(int... values) {
        return new Random() {
            private int i = 0;

            @Override
            public int nextInt(int bound) {
                return i < values.length ? values[i++] : 1;
            }
        };
    }

    @Test
    void generatesBase62CodesOfConfiguredLength() {
        ShortCodeGenerator generator = new ShortCodeGenerator(7, new SecureRandom(), ShortCodeGenerator.RESERVED);
        ShortCodeFormat format = new ShortCodeFormat(7);

        for (int i = 0; i < 1000; i++) {
            assertThat(format.isWellFormed(generator.generate())).isTrue();
        }
    }

    @Test
    void honoursOtherLengths() {
        ShortCodeGenerator generator = new ShortCodeGenerator(10, new SecureRandom(), Set.of());

        assertThat(generator.generate()).hasSize(10);
    }

    @Test
    void codesAreNotRepeatedInPractice() {
        ShortCodeGenerator generator = new ShortCodeGenerator(7, new SecureRandom(), ShortCodeGenerator.RESERVED);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }

        assertThat(seen).hasSize(10_000);
    }

    @Test
    void skipsReservedWords() {
        // First attempt yields "0000" (reserved), second attempt yields "1111".
        ShortCodeGenerator generator = new ShortCodeGenerator(4, scripted(0, 0, 0, 0), Set.of("0000"));

        assertThat(generator.generate()).isEqualTo("1111");
    }

    @Test
    void reservedWordCheckIsCaseInsensitive() {
        // Indexes 10..13 are "ABCD"; reserved set holds "abcd".
        ShortCodeGenerator generator = new ShortCodeGenerator(4, scripted(10, 11, 12, 13), Set.of("abcd"));

        assertThat(generator.generate()).isEqualTo("1111");
    }
}
