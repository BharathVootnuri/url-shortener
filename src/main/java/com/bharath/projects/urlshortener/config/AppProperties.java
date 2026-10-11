package com.bharath.projects.urlshortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Type-safe binding for the {@code app.*} properties.
 * Values differ per profile (see application-local.yaml / application-prod.yaml).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String baseUrl,
        @NotNull @Valid ShortCode shortCode,
        @NotNull @Valid ClickRecording clickRecording,
        @NotNull @Valid UrlValidation urlValidation,
        @NotNull @Valid Cors cors,
        @NotNull @Valid RateLimit rateLimit) {

    public record ShortCode(
            @Min(4) @Max(16) int length,
            @Min(1) int maxCollisionRetries) {
    }

    public record ClickRecording(
            @Min(1) int corePoolSize,
            @Min(1) int maxPoolSize,
            @Min(1) int queueCapacity,
            @Min(0) int shutdownTimeoutSeconds) {

        @AssertTrue(message = "app.click-recording.max-pool-size must be >= core-pool-size")
        public boolean isMaxPoolSizeAtLeastCorePoolSize() {
            return maxPoolSize >= corePoolSize;
        }
    }

    public record UrlValidation(
            @Min(1) @Max(2048) int maxLength,
            boolean allowPrivateHosts) {
    }

    /** Explicit origin allow-list. A wildcard is refused at startup (requirement: no wildcard CORS). */
    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            for (String origin : allowedOrigins) {
                if (origin.contains("*")) {
                    throw new IllegalArgumentException(
                            "app.cors.allowed-origins must list explicit origins; wildcards are not allowed");
                }
            }
        }
    }

    /**
     * Per-client-IP token buckets, held in memory (single instance only).
     *
     * @param createPerMinute  burst size and refill rate (per minute) for creating links
     * @param missPerMinute    unknown-code lookups tolerated per minute before the client is throttled
     *                         (slows code enumeration)
     * @param analyticsPerMinute analytics requests per minute; the analytics query is the one expensive read
     *                         (it scans a link's click rows in the requested range), so it is throttled too
     */
    public record RateLimit(
            boolean enabled,
            @Min(1) int createPerMinute,
            @Min(1) int missPerMinute,
            @Min(1) int analyticsPerMinute) {
    }
}
