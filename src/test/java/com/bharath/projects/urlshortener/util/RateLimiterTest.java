package com.bharath.projects.urlshortener.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter(true, 3, 3, clock);

        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();

        RateLimiter.Decision denied = limiter.tryAcquireCreate("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void retryAfterReflectsRefillRate() {
        RateLimiter limiter = new RateLimiter(true, 6, 6, clock); // 1 token every 10 seconds
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquireCreate("a");
        }

        assertThat(limiter.tryAcquireCreate("a").retryAfterSeconds()).isEqualTo(10);

        advance(Duration.ofSeconds(4));
        assertThat(limiter.tryAcquireCreate("a").retryAfterSeconds()).isEqualTo(6);
    }

    @Test
    void tokensRefillOverTime() {
        RateLimiter limiter = new RateLimiter(true, 6, 6, clock); // 1 token every 10 seconds
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquireCreate("a");
        }
        assertThat(limiter.tryAcquireCreate("a").allowed()).isFalse();

        advance(Duration.ofSeconds(10));

        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isFalse();
    }

    @Test
    void refillNeverExceedsCapacity() {
        RateLimiter limiter = new RateLimiter(true, 2, 2, clock);
        advance(Duration.ofHours(5));

        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isFalse();
    }

    @Test
    void clientsAreIndependent() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, clock);

        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireCreate("a").allowed()).isFalse();
        assertThat(limiter.tryAcquireCreate("b").allowed()).isTrue();
    }

    @Test
    void createAndMissLimitsAreIndependent() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, clock);
        limiter.tryAcquireCreate("a");
        assertThat(limiter.tryAcquireCreate("a").allowed()).isFalse();

        assertThat(limiter.checkMiss("a").allowed()).isTrue();
    }

    @Test
    void clientIsThrottledOnlyAfterEnoughMisses() {
        RateLimiter limiter = new RateLimiter(true, 30, 3, clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.checkMiss("a").allowed()).isTrue(); // checking does not consume
            limiter.recordMiss("a");
        }

        RateLimiter.Decision decision = limiter.checkMiss("a");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limiter.checkMiss("other").allowed()).isTrue();
    }

    @Test
    void analyticsHasItsOwnBucketPerClientIndependentOfCreateAndMiss() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, 2, clock);

        assertThat(limiter.tryAcquireAnalytics("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireAnalytics("a").allowed()).isTrue();
        RateLimiter.Decision denied = limiter.tryAcquireAnalytics("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);

        // Other limits and other clients are unaffected.
        assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
        assertThat(limiter.checkMiss("a").allowed()).isTrue();
        assertThat(limiter.tryAcquireAnalytics("b").allowed()).isTrue();

        advance(Duration.ofSeconds(30)); // 2/min = 1 token per 30 s
        assertThat(limiter.tryAcquireAnalytics("a").allowed()).isTrue();
    }

    @Test
    void analyticsIsAllowedWhenTheLimiterIsDisabled() {
        RateLimiter limiter = new RateLimiter(false, 1, 1, 1, clock);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquireAnalytics("a").allowed()).isTrue();
        }
    }

    @Test
    void checkingMissesRepeatedlyNeverThrottlesByItself() {
        RateLimiter limiter = new RateLimiter(true, 30, 3, clock);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.checkMiss("a").allowed()).isTrue();
        }
    }

    @Test
    void missThrottleLiftsAsTokensRefill() {
        RateLimiter limiter = new RateLimiter(true, 30, 3, clock); // 1 token per 20 s
        for (int i = 0; i < 5; i++) {
            limiter.recordMiss("a"); // extra misses cannot dig a deeper hole than empty
        }
        assertThat(limiter.checkMiss("a").allowed()).isFalse();

        advance(Duration.ofSeconds(20));

        assertThat(limiter.checkMiss("a").allowed()).isTrue();
    }

    @Test
    void disabledLimiterAllowsEverything() {
        RateLimiter limiter = new RateLimiter(false, 1, 1, clock);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquireCreate("a").allowed()).isTrue();
            limiter.recordMiss("a");
            assertThat(limiter.checkMiss("a").allowed()).isTrue();
        }
    }

    @Test
    void trackedClientsStayBounded() {
        RateLimiter limiter = new RateLimiter(true, 5, 5, clock);

        for (int i = 0; i < RateLimiter.MAX_TRACKED_CLIENTS * 3; i++) {
            limiter.tryAcquireCreate("client-" + i);
        }

        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(RateLimiter.MAX_TRACKED_CLIENTS);
    }

    @Test
    void idleClientsArePurgedInsteadOfEverythingBeingCleared() {
        RateLimiter limiter = new RateLimiter(true, 2, 2, clock);
        // A client that has used its allowance...
        limiter.tryAcquireCreate("busy");
        limiter.tryAcquireCreate("busy");
        // ...and many that touched it once, then fully refilled while idle.
        for (int i = 0; i < RateLimiter.MAX_TRACKED_CLIENTS - 1; i++) {
            limiter.tryAcquireCreate("idle-" + i);
        }
        advance(Duration.ofMinutes(2));
        limiter.tryAcquireCreate("busy"); // refilled too; consume again so it is not idle
        limiter.tryAcquireCreate("busy");

        limiter.tryAcquireCreate("newcomer"); // hits the cap and triggers the purge

        assertThat(limiter.tryAcquireCreate("busy").allowed()).isFalse(); // its state survived the purge
        assertThat(limiter.trackedClients()).isLessThan(RateLimiter.MAX_TRACKED_CLIENTS);
    }
}
