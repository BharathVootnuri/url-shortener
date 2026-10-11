package com.bharath.projects.urlshortener.repository;

import com.bharath.projects.urlshortener.entity.ClickEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /** Clicks in the half-open range [from, to). */
    @Query("select count(c) from ClickEvent c "
            + "where c.shortLinkId = :linkId and c.clickedAt >= :from and c.clickedAt < :to")
    long countInRange(@Param("linkId") Long linkId,
                      @Param("from") Instant from,
                      @Param("to") Instant to);

    /**
     * Click timestamps in [from, to), streamed so callers can bucket them without holding every
     * row in memory. Bucketing is done in Java (UTC) rather than SQL because date truncation and
     * session time zones behave differently on H2 and PostgreSQL.
     * Must be called inside a transaction and the stream must be closed.
     */
    @Query("select c.clickedAt from ClickEvent c "
            + "where c.shortLinkId = :linkId and c.clickedAt >= :from and c.clickedAt < :to")
    Stream<Instant> streamClickTimes(@Param("linkId") Long linkId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    /**
     * Referrer counts for a link in [from, to), most frequent first. Clicks without a referrer
     * are excluded. Pass a {@link Pageable} to cap the number of rows (top N).
     */
    @Query("select c.referrer as referrer, count(c) as clicks from ClickEvent c "
            + "where c.shortLinkId = :linkId and c.clickedAt >= :from and c.clickedAt < :to "
            + "and c.referrer is not null "
            + "group by c.referrer order by count(c) desc, c.referrer asc")
    List<ReferrerCount> topReferrers(@Param("linkId") Long linkId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);

    interface ReferrerCount {
        String getReferrer();

        long getClicks();
    }
}
