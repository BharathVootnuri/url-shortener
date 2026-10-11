package com.bharath.projects.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Click count for one Referer header value.")
public record ReferrerStat(
        @Schema(description = "Referer header value as sent by the browser (truncated to 512 characters). "
                + "Clicks without a referrer are not listed here.",
                example = "https://news.example.org/post/1")
        String referrer,

        @Schema(description = "Clicks with this referrer in the range.", example = "5")
        long clicks) {
}
