package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Link details. Readable even after the link has expired (status = EXPIRED). */
@Schema(description = "Details of a short link.")
public record LinkDetailsResponse(
        @Schema(description = "The 7-character base62 short code.", example = "aB3dE9x")
        String code,

        @Schema(description = "Full short URL to share.", example = "http://localhost:8080/aB3dE9x")
        String shortUrl,

        @Schema(description = "The URL the short link redirects to.", example = "https://example.com/some/long/path")
        String originalUrl,

        @Schema(description = "Creation instant (UTC).", example = "2026-10-10T09:30:00Z")
        Instant createdAt,

        @Schema(description = "Expiration instant (UTC), or null if the link never expires.",
                example = "2027-01-01T00:00:00Z", nullable = true)
        Instant expiresAt,

        @Schema(description = "ACTIVE redirects; EXPIRED answers 410 Gone.")
        LinkStatus status,

        @Schema(description = "All-time number of recorded clicks.", example = "42")
        long totalClicks) {
}
