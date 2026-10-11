package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Response to a successful create (HTTP 201). */
@Schema(description = "A newly created short link.")
public record LinkResponse(
        @Schema(description = "The 7-character base62 short code.", example = "aB3dE9x")
        String code,

        @Schema(description = "Full short URL to share.", example = "http://localhost:8080/aB3dE9x")
        String shortUrl,

        @Schema(description = "The (normalized) URL the short link redirects to.",
                example = "https://example.com/some/long/path?utm_source=newsletter")
        String originalUrl,

        @Schema(description = "Creation instant (UTC).", example = "2026-10-10T09:30:00Z")
        Instant createdAt,

        @Schema(description = "Expiration instant (UTC), or null if the link never expires.",
                example = "2027-01-01T00:00:00Z", nullable = true)
        Instant expiresAt) {
}
