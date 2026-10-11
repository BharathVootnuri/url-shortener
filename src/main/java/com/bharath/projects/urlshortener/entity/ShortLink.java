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

@Entity
@Table(name = "short_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16, updatable = false)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048, updatable = false)
    private String originalUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    /**
     * Denormalized total. Only ever changed through the atomic UPDATE in
     * {@code ShortLinkRepository#incrementClickCount}, never by flushing this entity.
     */
    @Column(name = "click_count", nullable = false, updatable = false)
    private long clickCount;

    public ShortLink(String code, String originalUrl, Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** A link is expired when {@code now >= expiresAt}. No expiry means it never expires. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
