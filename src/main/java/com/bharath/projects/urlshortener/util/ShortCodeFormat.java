package com.bharath.projects.urlshortener.util;

import com.bharath.projects.urlshortener.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shape of a valid short code: exactly the configured number of base62 characters.
 * Lets the redirect path reject junk (favicon.ico, scanners, etc.) without touching the database.
 */
@Component
public class ShortCodeFormat {

    private final int length;

    @Autowired
    public ShortCodeFormat(AppProperties props) {
        this(props.shortCode().length());
    }

    ShortCodeFormat(int length) {
        this.length = length;
    }

    public int length() {
        return length;
    }

    public boolean isWellFormed(String code) {
        if (code == null || code.length() != length) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
