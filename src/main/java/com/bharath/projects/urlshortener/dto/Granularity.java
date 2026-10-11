package com.bharath.projects.urlshortener.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Time-bucket size for analytics. Buckets are aligned to UTC. */
public enum Granularity {
    HOUR(ChronoUnit.HOURS),
    DAY(ChronoUnit.DAYS);

    private final ChronoUnit unit;

    Granularity(ChronoUnit unit) {
        this.unit = unit;
    }

    /** Start of the UTC bucket containing the instant. */
    public Instant bucketStart(Instant instant) {
        return instant.truncatedTo(unit);
    }
}
