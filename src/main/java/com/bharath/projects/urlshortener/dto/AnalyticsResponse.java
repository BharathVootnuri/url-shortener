package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Click analytics for one link.
 *
 * @param totalClicks    all-time click count
 * @param clicksInRange  clicks in [from, to); equals the sum of {@code series}
 * @param series         non-empty buckets only, oldest first (empty list when there were no clicks)
 * @param topReferrers   most frequent referrers in the range, at most 10
 */
@Schema(description = "Click analytics of one link for a time range.")
public record AnalyticsResponse(
        @Schema(description = "The 7-character base62 short code.", example = "aB3dE9x")
        String code,

        @Schema(description = "All-time number of recorded clicks (independent of the range).", example = "42")
        long totalClicks,

        @Schema(description = "Clicks within [from, to); equals the sum of the series.", example = "12")
        long clicksInRange,

        @Schema(description = "Effective inclusive start of the range.", example = "2026-09-10T09:30:00Z")
        Instant from,

        @Schema(description = "Effective exclusive end of the range.", example = "2026-10-10T09:30:00Z")
        Instant to,

        @Schema(description = "Bucket size used for the series.")
        Granularity granularity,

        @Schema(description = "Non-empty buckets only, oldest first. Empty when there were no clicks.")
        List<ClickBucket> series,

        @Schema(description = "Most frequent referrers in the range, at most 10, most frequent first.")
        List<ReferrerStat> topReferrers) {
}
