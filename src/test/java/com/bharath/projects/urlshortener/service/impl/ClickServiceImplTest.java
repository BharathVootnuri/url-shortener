package com.bharath.projects.urlshortener.service.impl;

import com.bharath.projects.urlshortener.config.AppProperties;
import com.bharath.projects.urlshortener.dto.AnalyticsResponse;
import com.bharath.projects.urlshortener.dto.ClickBucket;
import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.entity.ClickEvent;
import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.exception.InvalidRangeException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;
import com.bharath.projects.urlshortener.repository.ClickEventRepository;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.bharath.projects.urlshortener.util.ShortCodeFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickServiceImplTest {

    private static final Instant NOW = Instant.parse("2030-06-15T12:30:00Z");
    private static final String CODE = "Ab3dE9x";

    @Mock
    ClickWriter writer;
    @Mock
    ShortLinkRepository links;
    @Mock
    ClickEventRepository clicks;

    /** Clock whose "now" can be moved, to prove the click time is taken at call time. */
    private final AtomicReference<Instant> clockTime = new AtomicReference<>(NOW);
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
            return clockTime.get();
        }
    };

    /** Executor that queues work until the test decides to run it. */
    private final List<Runnable> queued = new ArrayList<>();

    private ClickServiceImpl service;

    @BeforeEach
    void setUp() {
        service = serviceWith(queued::add);
    }

    private ClickServiceImpl serviceWith(Executor executor) {
        AppProperties props = new AppProperties(
                "http://localhost:8080",
                new AppProperties.ShortCode(7, 3),
                new AppProperties.ClickRecording(2, 4, 10, 5),
                new AppProperties.UrlValidation(2048, false),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(true, 30, 60, 30));
        return new ClickServiceImpl(executor, writer, links, clicks, new ShortCodeFormat(props), clock);
    }

    private static ShortLink link(long totalClicks) {
        ShortLink l = new ShortLink(CODE, "https://example.com", NOW.minus(Duration.ofDays(60)), null);
        ReflectionTestUtils.setField(l, "id", 42L);
        ReflectionTestUtils.setField(l, "clickCount", totalClicks);
        return l;
    }

    private static ClickEventRepository.ReferrerCount referrer(String name, long clicks) {
        return new ClickEventRepository.ReferrerCount() {
            @Override
            public String getReferrer() {
                return name;
            }

            @Override
            public long getClicks() {
                return clicks;
            }
        };
    }

    // ---------------- recordClick (async behaviour) ----------------

    @Test
    void recordClickReturnsWithoutWaitingForTheWrite() {
        service.recordClick(42L, "https://ref.example", "Mozilla/5.0");

        // Handed to the executor, but nothing has been written yet.
        assertThat(queued).hasSize(1);
        verifyNoInteractions(writer);

        queued.get(0).run();

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getShortLinkId()).isEqualTo(42L);
        assertThat(captor.getValue().getReferrer()).isEqualTo("https://ref.example");
        assertThat(captor.getValue().getUserAgent()).isEqualTo("Mozilla/5.0");
    }

    @Test
    void clickTimestampIsCapturedAtCallTimeNotWriteTime() {
        service.recordClick(42L, null, null);
        clockTime.set(NOW.plusSeconds(300)); // the write happens "later"
        queued.get(0).run();

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getClickedAt()).isEqualTo(NOW);
    }

    @Test
    void writeFailureIsSwallowedAndDoesNotThrow() {
        doThrow(new IllegalStateException("db down")).when(writer).write(any());

        service.recordClick(42L, null, null);
        queued.get(0).run(); // must not throw
    }

    @Test
    void fullQueueDropsTheClickWithoutThrowing() {
        ClickServiceImpl saturated = serviceWith(r -> {
            throw new TaskRejectedException("queue full");
        });

        saturated.recordClick(42L, "https://ref.example", "ua"); // must not throw

        verifyNoInteractions(writer);
    }

    @Test
    void controlCharactersAreStrippedAndBlankBecomesNull() {
        service.recordClick(42L, "https://ref.example/\r\nX-Injected: 1", "   ");
        queued.get(0).run();

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getReferrer()).isEqualTo("https://ref.example/X-Injected: 1");
        assertThat(captor.getValue().getUserAgent()).isNull();
    }

    @Test
    void overlongHeadersAreTruncated() {
        service.recordClick(42L, "r".repeat(2000), "u".repeat(2000));
        queued.get(0).run();

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getReferrer()).hasSize(ClickEvent.MAX_HEADER_LENGTH);
        assertThat(captor.getValue().getUserAgent()).hasSize(ClickEvent.MAX_HEADER_LENGTH);
    }

    // ---------------- analytics ----------------

    @Test
    void dailyBucketsAreAlignedToUtcSortedAndSumToTheTotal() {
        Instant from = Instant.parse("2030-06-01T00:00:00Z");
        Instant to = Instant.parse("2030-06-10T00:00:00Z");
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(7)));
        when(clicks.streamClickTimes(42L, from, to)).thenReturn(Stream.of(
                Instant.parse("2030-06-03T23:59:59Z"),
                Instant.parse("2030-06-02T08:00:00Z"),
                Instant.parse("2030-06-02T20:00:00Z"),
                Instant.parse("2030-06-04T00:00:00Z"),
                Instant.parse("2030-06-02T00:00:00Z")));
        when(clicks.topReferrers(eq(42L), eq(from), eq(to), any(Pageable.class))).thenReturn(List.of());

        AnalyticsResponse r = service.getAnalytics(CODE, from, to, Granularity.DAY);

        assertThat(r.series()).containsExactly(
                new ClickBucket(Instant.parse("2030-06-02T00:00:00Z"), 3),
                new ClickBucket(Instant.parse("2030-06-03T00:00:00Z"), 1),
                new ClickBucket(Instant.parse("2030-06-04T00:00:00Z"), 1));
        assertThat(r.clicksInRange()).isEqualTo(5);
        assertThat(r.totalClicks()).isEqualTo(7); // all-time counter, independent of the range
        assertThat(r.granularity()).isEqualTo(Granularity.DAY);
    }

    @Test
    void hourlyBuckets() {
        Instant from = Instant.parse("2030-06-01T00:00:00Z");
        Instant to = Instant.parse("2030-06-02T00:00:00Z");
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(3)));
        when(clicks.streamClickTimes(42L, from, to)).thenReturn(Stream.of(
                Instant.parse("2030-06-01T10:05:00Z"),
                Instant.parse("2030-06-01T10:59:59Z"),
                Instant.parse("2030-06-01T11:00:00Z")));
        when(clicks.topReferrers(eq(42L), eq(from), eq(to), any(Pageable.class))).thenReturn(List.of());

        AnalyticsResponse r = service.getAnalytics(CODE, from, to, Granularity.HOUR);

        assertThat(r.series()).containsExactly(
                new ClickBucket(Instant.parse("2030-06-01T10:00:00Z"), 2),
                new ClickBucket(Instant.parse("2030-06-01T11:00:00Z"), 1));
    }

    @Test
    void noClicksGivesEmptySeriesAndZeros() {
        Instant from = Instant.parse("2030-06-01T00:00:00Z");
        Instant to = Instant.parse("2030-06-02T00:00:00Z");
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(0)));
        when(clicks.streamClickTimes(42L, from, to)).thenReturn(Stream.empty());
        when(clicks.topReferrers(eq(42L), eq(from), eq(to), any(Pageable.class))).thenReturn(List.of());

        AnalyticsResponse r = service.getAnalytics(CODE, from, to, Granularity.DAY);

        assertThat(r.series()).isEmpty();
        assertThat(r.topReferrers()).isEmpty();
        assertThat(r.clicksInRange()).isZero();
        assertThat(r.totalClicks()).isZero();
    }

    @Test
    void defaultsAreLast30DaysEndingNowByDay() {
        Instant expectedFrom = NOW.minus(Duration.ofDays(30));
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(0)));
        when(clicks.streamClickTimes(42L, expectedFrom, NOW)).thenReturn(Stream.empty());
        when(clicks.topReferrers(eq(42L), eq(expectedFrom), eq(NOW), any(Pageable.class))).thenReturn(List.of());

        AnalyticsResponse r = service.getAnalytics(CODE, null, null, null);

        assertThat(r.from()).isEqualTo(expectedFrom);
        assertThat(r.to()).isEqualTo(NOW);
        assertThat(r.granularity()).isEqualTo(Granularity.DAY);
    }

    @Test
    void topReferrersAreMappedAndLimitedToTen() {
        Instant from = Instant.parse("2030-06-01T00:00:00Z");
        Instant to = Instant.parse("2030-06-02T00:00:00Z");
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(0)));
        when(clicks.streamClickTimes(42L, from, to)).thenReturn(Stream.empty());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(clicks.topReferrers(eq(42L), eq(from), eq(to), page.capture()))
                .thenReturn(List.of(referrer("https://a.example", 5), referrer("https://b.example", 2)));

        AnalyticsResponse r = service.getAnalytics(CODE, from, to, Granularity.DAY);

        assertThat(r.topReferrers()).extracting("referrer", "clicks")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("https://a.example", 5L),
                        org.assertj.core.groups.Tuple.tuple("https://b.example", 2L));
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void rejectsReversedOrEmptyRange() {
        Instant t = Instant.parse("2030-06-01T00:00:00Z");

        assertThatThrownBy(() -> service.getAnalytics(CODE, t.plusSeconds(1), t, Granularity.DAY))
                .isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> service.getAnalytics(CODE, t, t, Granularity.DAY))
                .isInstanceOf(InvalidRangeException.class);

        verifyNoInteractions(links, clicks);
    }

    @Test
    void rejectsRangeLongerThan366DaysButAcceptsExactly366() {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(0)));
        Instant ok = from.plus(Duration.ofDays(366));
        when(clicks.streamClickTimes(42L, from, ok)).thenReturn(Stream.empty());
        when(clicks.topReferrers(eq(42L), eq(from), eq(ok), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.getAnalytics(CODE, from, ok, Granularity.DAY).clicksInRange()).isZero();
        assertThatThrownBy(() -> service.getAnalytics(CODE, from, ok.plusSeconds(1), Granularity.DAY))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void unknownCodeIsNotFound() {
        when(links.findByCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnalytics(CODE, null, null, null))
                .isInstanceOf(LinkNotFoundException.class);
        verify(clicks, never()).streamClickTimes(anyLong(), any(), any());
    }

    @Test
    void malformedCodeIsNotFoundWithoutQueryingTheDatabase() {
        assertThatThrownBy(() -> service.getAnalytics("bad", null, null, null))
                .isInstanceOf(LinkNotFoundException.class);

        verify(links, never()).findByCode(anyString());
    }
}
