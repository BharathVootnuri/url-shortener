package com.bharath.projects.urlshortener.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ClickEventTest {

    @Test
    void truncatesLongReferrerAndUserAgent() {
        String longValue = "x".repeat(ClickEvent.MAX_HEADER_LENGTH + 100);

        ClickEvent event = new ClickEvent(1L, Instant.now(), longValue, longValue);

        assertThat(event.getReferrer()).hasSize(ClickEvent.MAX_HEADER_LENGTH);
        assertThat(event.getUserAgent()).hasSize(ClickEvent.MAX_HEADER_LENGTH);
    }

    @Test
    void keepsNullAndShortValues() {
        ClickEvent event = new ClickEvent(1L, Instant.now(), null, "Mozilla/5.0");

        assertThat(event.getReferrer()).isNull();
        assertThat(event.getUserAgent()).isEqualTo("Mozilla/5.0");
    }
}
