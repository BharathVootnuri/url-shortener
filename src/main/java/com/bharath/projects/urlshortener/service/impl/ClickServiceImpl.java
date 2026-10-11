package com.bharath.projects.urlshortener.service.impl;

import com.bharath.projects.urlshortener.config.ClickExecutorConfig;
import com.bharath.projects.urlshortener.dto.AnalyticsResponse;
import com.bharath.projects.urlshortener.dto.ClickBucket;
import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.dto.ReferrerStat;
import com.bharath.projects.urlshortener.entity.ClickEvent;
import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.exception.InvalidRangeException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;
import com.bharath.projects.urlshortener.repository.ClickEventRepository;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.bharath.projects.urlshortener.service.ClickService;
import com.bharath.projects.urlshortener.util.ShortCodeFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@Slf4j
@Service
public class ClickServiceImpl implements ClickService {

    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    static final Duration MAX_WINDOW = Duration.ofDays(366);
    static final int TOP_REFERRERS = 10;

    private final Executor clickExecutor;
    private final ClickWriter writer;
    private final ShortLinkRepository links;
    private final ClickEventRepository clicks;
    private final ShortCodeFormat codeFormat;
    private final Clock clock;

    public ClickServiceImpl(@Qualifier(ClickExecutorConfig.CLICK_EXECUTOR) Executor clickExecutor,
                            ClickWriter writer,
                            ShortLinkRepository links,
                            ClickEventRepository clicks,
                            ShortCodeFormat codeFormat,
                            Clock clock) {
        this.clickExecutor = clickExecutor;
        this.writer = writer;
        this.links = links;
        this.clicks = clicks;
        this.codeFormat = codeFormat;
        this.clock = clock;
    }

    @Override
    public void recordClick(Long linkId, String referrer, String userAgent) {
        try {
            // Capture everything on the caller's thread so the event reflects the moment of the redirect.
            ClickEvent event = new ClickEvent(linkId, Instant.now(clock), sanitize(referrer), sanitize(userAgent));
            clickExecutor.execute(() -> persist(event));
        } catch (RuntimeException e) {
            // Queue full or executor shut down: the redirect must still succeed.
            log.warn("Dropped click for link {}: {}", linkId, e.toString());
        }
    }

    private void persist(ClickEvent event) {
        try {
            writer.write(event);
        } catch (Exception e) {
            log.error("Failed to record click for link {}", event.getShortLinkId(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(String code, Instant from, Instant to, Granularity granularity) {
        Granularity bucketSize = granularity == null ? Granularity.DAY : granularity;
        Instant effectiveTo = to != null ? to : Instant.now(clock);
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_WINDOW);
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new InvalidRangeException("'from' must be before 'to'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRangeException("Time range must not exceed " + MAX_WINDOW.toDays() + " days");
        }

        ShortLink link = findOrThrow(code);

        Map<Instant, Long> buckets = new TreeMap<>();
        try (Stream<Instant> times = clicks.streamClickTimes(link.getId(), effectiveFrom, effectiveTo)) {
            times.forEach(t -> buckets.merge(bucketSize.bucketStart(t), 1L, Long::sum));
        }
        List<ClickBucket> series = new ArrayList<>(buckets.size());
        long inRange = 0;
        for (Map.Entry<Instant, Long> e : buckets.entrySet()) {
            series.add(new ClickBucket(e.getKey(), e.getValue()));
            inRange += e.getValue();
        }

        List<ReferrerStat> topReferrers = clicks
                .topReferrers(link.getId(), effectiveFrom, effectiveTo, PageRequest.of(0, TOP_REFERRERS))
                .stream()
                .map(r -> new ReferrerStat(r.getReferrer(), r.getClicks()))
                .toList();

        return new AnalyticsResponse(link.getCode(), link.getClickCount(), inRange,
                effectiveFrom, effectiveTo, bucketSize, series, topReferrers);
    }

    private ShortLink findOrThrow(String code) {
        if (!codeFormat.isWellFormed(code)) {
            throw new LinkNotFoundException();
        }
        return links.findByCode(code).orElseThrow(LinkNotFoundException::new);
    }

    /** Strips control characters (log/header injection) and maps blank to null. Length is capped by ClickEvent. */
    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
