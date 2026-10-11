package com.bharath.projects.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row per redirect. Deliberately stores no IP address.
 * References the link by id only (FK enforced in the DB) so the async recorder
 * never needs to load a {@link ShortLink}.
 */
@Entity
@Table(name = "click_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClickEvent {

    public static final int MAX_HEADER_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_link_id", nullable = false, updatable = false)
    private Long shortLinkId;

    @Column(name = "clicked_at", nullable = false, updatable = false)
    private Instant clickedAt;

    @Column(length = MAX_HEADER_LENGTH, updatable = false)
    private String referrer;

    @Column(name = "user_agent", length = MAX_HEADER_LENGTH, updatable = false)
    private String userAgent;

    public ClickEvent(Long shortLinkId, Instant clickedAt, String referrer, String userAgent) {
        this.shortLinkId = shortLinkId;
        this.clickedAt = clickedAt;
        this.referrer = truncate(referrer);
        this.userAgent = truncate(userAgent);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_HEADER_LENGTH
                ? value
                : value.substring(0, MAX_HEADER_LENGTH);
    }
}
