package com.bharath.projects.urlshortener.util;

import com.bharath.projects.urlshortener.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Generates random base62 short codes. Codes are not sequential, so they cannot be enumerated.
 * Uniqueness is NOT guaranteed here; the database unique constraint is the source of truth.
 */
@Component
public class ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Paths owned by the application. A generated code must never collide with a route. */
    static final Set<String> RESERVED = Set.of(
            "api", "actuator", "assets", "static", "error", "h2-console", "swagger-ui", "favicon");

    private final int length;
    private final Random random;
    private final Set<String> reserved;

    @Autowired
    public ShortCodeGenerator(AppProperties props) {
        this(props.shortCode().length(), new SecureRandom(), RESERVED);
    }

    ShortCodeGenerator(int length, Random random, Set<String> reserved) {
        this.length = length;
        this.random = random;
        this.reserved = reserved;
    }

    public String generate() {
        while (true) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!reserved.contains(code.toLowerCase(Locale.ROOT))) {
                return code;
            }
        }
    }
}
