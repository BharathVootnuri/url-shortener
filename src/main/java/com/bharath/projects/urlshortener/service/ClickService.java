package com.bharath.projects.urlshortener.service;

import com.bharath.projects.urlshortener.dto.AnalyticsResponse;
import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.exception.InvalidRangeException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;

import java.time.Instant;

public interface ClickService {

    /**
     * Records a click asynchronously. Returns immediately and NEVER throws: if the queue is full the
     * click is dropped and logged, and if the write later fails the error is logged.
     * The click timestamp is captured when this method is called, not when the write happens.
     */
    void recordClick(Long linkId, String referrer, String userAgent);

    /**
     * Click analytics for a link over the half-open range [from, to).
     * Defaults: {@code to} = now, {@code from} = 30 days before {@code to}, {@code granularity} = DAY.
     *
     * @throws LinkNotFoundException unknown or malformed code
     * @throws InvalidRangeException from is not before to, or the range exceeds 366 days
     */
    AnalyticsResponse getAnalytics(String code, Instant from, Instant to, Granularity granularity);
}
