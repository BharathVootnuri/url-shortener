package com.bharath.projects.urlshortener.util;

import com.bharath.projects.urlshortener.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, per-client token-bucket rate limiting. Three independent limits:
 * <ul>
 *   <li><b>create</b>: each link creation consumes a token.</li>
 *   <li><b>miss</b>: each lookup of an unknown code consumes a token; a client that has run out is
 *       refused further lookups until tokens refill. This slows code enumeration without limiting
 *       normal redirect traffic.</li>
 *   <li><b>analytics</b>: each analytics request consumes a token. It is the only expensive read (it scans a
 *       link's click rows), so it is throttled; plain redirects and details lookups are not.</li>
 * </ul>
 * State lives in this JVM only, so it does not survive a restart or span multiple instances
 * (acceptable for the single-instance prototype; a shared store would replace it when scaling out).
 * Memory is bounded: idle (fully refilled) buckets are purged when the tracked-client cap is reached.
 */
@Component
public class RateLimiter {

    /** Outcome of a limit check. {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final boolean enabled;
    private final int createPerMinute;
    private final int missPerMinute;
    private final int analyticsPerMinute;
    private final Clock clock;
    private final ConcurrentMap<String, TokenBucket> createBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TokenBucket> missBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TokenBucket> analyticsBuckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(AppProperties props, Clock clock) {
        this(props.rateLimit().enabled(), props.rateLimit().createPerMinute(),
                props.rateLimit().missPerMinute(), props.rateLimit().analyticsPerMinute(), clock);
    }

    RateLimiter(boolean enabled, int createPerMinute, int missPerMinute, Clock clock) {
        this(enabled, createPerMinute, missPerMinute, 30, clock);
    }

    RateLimiter(boolean enabled, int createPerMinute, int missPerMinute, int analyticsPerMinute, Clock clock) {
        this.enabled = enabled;
        this.createPerMinute = createPerMinute;
        this.missPerMinute = missPerMinute;
        this.analyticsPerMinute = analyticsPerMinute;
        this.clock = clock;
    }

    /** Consumes one create token for the client, if available. */
    public Decision tryAcquireCreate(String client) {
        if (!enabled) {
            return Decision.ALLOWED;
        }
        return bucket(createBuckets, client, createPerMinute).tryConsume(clock.millis());
    }

    /** Consumes one analytics token for the client, if available. */
    public Decision tryAcquireAnalytics(String client) {
        if (!enabled) {
            return Decision.ALLOWED;
        }
        return bucket(analyticsBuckets, client, analyticsPerMinute).tryConsume(clock.millis());
    }

    /** Checks (without consuming) whether the client may still look up codes. */
    public Decision checkMiss(String client) {
        if (!enabled) {
            return Decision.ALLOWED;
        }
        return bucket(missBuckets, client, missPerMinute).peek(clock.millis());
    }

    /** Records one unknown-code lookup against the client. */
    public void recordMiss(String client) {
        if (enabled) {
            bucket(missBuckets, client, missPerMinute).consume(clock.millis());
        }
    }

    private TokenBucket bucket(ConcurrentMap<String, TokenBucket> buckets, String client, int perMinute) {
        TokenBucket existing = buckets.get(client);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= MAX_TRACKED_CLIENTS) {
            long now = clock.millis();
            buckets.values().removeIf(b -> b.isFull(now));
            if (buckets.size() >= MAX_TRACKED_CLIENTS) {
                buckets.clear(); // last resort: bound memory even under a flood of distinct clients
            }
        }
        return buckets.computeIfAbsent(client, k -> new TokenBucket(perMinute, clock.millis()));
    }

    int trackedClients() {
        return createBuckets.size() + missBuckets.size() + analyticsBuckets.size();
    }

    private static final class TokenBucket {
        private final int capacity;
        private final double tokensPerMilli;
        private double tokens;
        private long lastRefillMillis;

        TokenBucket(int capacityPerMinute, long nowMillis) {
            this.capacity = capacityPerMinute;
            this.tokensPerMilli = capacityPerMinute / 60_000.0;
            this.tokens = capacityPerMinute;
            this.lastRefillMillis = nowMillis;
        }

        synchronized Decision tryConsume(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.ALLOWED;
            }
            return denied();
        }

        synchronized Decision peek(long now) {
            refill(now);
            return tokens >= 1 ? Decision.ALLOWED : denied();
        }

        synchronized void consume(long now) {
            refill(now);
            tokens = Math.max(0, tokens - 1);
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);
                lastRefillMillis = now;
            }
        }

        private Decision denied() {
            double millisUntilToken = (1 - tokens) / tokensPerMilli;
            return new Decision(false, (long) Math.ceil(millisUntilToken / 1000.0));
        }
    }
}
