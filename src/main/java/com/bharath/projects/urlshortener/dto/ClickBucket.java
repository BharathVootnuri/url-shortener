package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Number of clicks in one time bucket. */
@Schema(description = "Clicks in one UTC-aligned time bucket.")
public record ClickBucket(
        @Schema(description = "Start of the bucket (inclusive, UTC).", example = "2026-10-09T00:00:00Z")
        Instant bucketStart,

        @Schema(description = "Clicks in the bucket (always at least 1; empty buckets are omitted).", example = "7")
        long clicks) {
}
