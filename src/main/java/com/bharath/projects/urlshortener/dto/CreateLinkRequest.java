package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Request body for creating a short link.
 * Deliberately has no bean-validation annotations: {@code url} is checked by the URL validator so
 * that missing, blank, and malformed values all produce the same INVALID_URL error.
 *
 * @param url       the http/https URL to shorten
 * @param expiresAt optional expiration instant (ISO-8601); must be in the future
 */
@Schema(description = "Request to create a short link.")
public record CreateLinkRequest(
        @Schema(description = "The http or https URL to shorten (ASCII, at most 2048 characters, no credentials).",
                example = "https://example.com/some/long/path?utm_source=newsletter",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048)
        String url,

        @Schema(description = "Optional expiration instant (ISO-8601, UTC). Must be in the future and at most 5 years ahead. "
                + "Omit for a link that never expires.",
                example = "2027-01-01T00:00:00Z", nullable = true)
        Instant expiresAt) {
}
