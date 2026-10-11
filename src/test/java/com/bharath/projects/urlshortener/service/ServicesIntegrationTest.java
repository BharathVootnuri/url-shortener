package com.bharath.projects.urlshortener.service;

import com.bharath.projects.urlshortener.dto.AnalyticsResponse;
import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.dto.LinkDetailsResponse;
import com.bharath.projects.urlshortener.dto.LinkResponse;
import com.bharath.projects.urlshortener.dto.LinkStatus;
import com.bharath.projects.urlshortener.dto.RedirectTarget;
import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.exception.InvalidUrlException;
import com.bharath.projects.urlshortener.exception.LinkExpiredException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;
import com.bharath.projects.urlshortener.repository.ClickEventRepository;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through the real services, async executor, and H2 (local profile) with Flyway.
 * Async writes are awaited, never slept on.
 */
@SpringBootTest
class ServicesIntegrationTest {

    @Autowired
    LinkService linkService;
    @Autowired
    ClickService clickService;
    @Autowired
    ShortLinkRepository links;
    @Autowired
    ClickEventRepository clicks;

    private LinkResponse createLink() {
        return linkService.create(new CreateLinkRequest("https://example.com/page?x=1", null));
    }

    private AnalyticsResponse analytics(String code) {
        Instant now = Instant.now();
        return clickService.getAnalytics(code, now.minus(Duration.ofDays(1)), now.plus(Duration.ofHours(1)),
                Granularity.DAY);
    }

    @Test
    void createThenDetailsThenResolve() {
        LinkResponse created = createLink();

        assertThat(created.code()).hasSize(7).matches("[0-9A-Za-z]{7}");
        assertThat(created.shortUrl()).isEqualTo("http://localhost:8080/" + created.code());

        LinkDetailsResponse details = linkService.getDetails(created.code());
        assertThat(details.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(details.totalClicks()).isZero();
        assertThat(details.originalUrl()).isEqualTo("https://example.com/page?x=1");

        RedirectTarget target = linkService.resolve(created.code());
        assertThat(target.url()).isEqualTo("https://example.com/page?x=1");
        assertThat(target.linkId()).isNotNull();
    }

    @Test
    void sameUrlGetsDifferentCodes() {
        assertThat(createLink().code()).isNotEqualTo(createLink().code());
    }

    @Test
    void localProfileAllowsLocalhostTargetsButStillRejectsBadSchemes() {
        LinkResponse local = linkService.create(new CreateLinkRequest("http://localhost:4200/app", null));
        assertThat(local.originalUrl()).isEqualTo("http://localhost:4200/app");

        assertThatThrownBy(() -> linkService.create(new CreateLinkRequest("javascript:alert(1)", null)))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void unknownAndMalformedCodes() {
        assertThatThrownBy(() -> linkService.resolve("zzzzzzz")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> linkService.getDetails("nope")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> clickService.getAnalytics("zzzzzzz", null, null, null))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void expiredLinkCannotBeResolvedButDetailsStayReadable() {
        ShortLink expired = links.save(new ShortLink("ExpTest", "https://example.com/old",
                Instant.now().minus(Duration.ofDays(2)), Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> linkService.resolve(expired.getCode())).isInstanceOf(LinkExpiredException.class);
        assertThat(linkService.getDetails(expired.getCode()).status()).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void clicksAreRecordedAsynchronouslyAndAppearInDetailsAndAnalytics() {
        LinkResponse created = createLink();
        Long linkId = linkService.resolve(created.code()).linkId();

        clickService.recordClick(linkId, "https://ref-a.example", "Mozilla/5.0");
        clickService.recordClick(linkId, "https://ref-a.example", "Mozilla/5.0");
        clickService.recordClick(linkId, "https://ref-b.example", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(linkService.getDetails(created.code()).totalClicks()).isEqualTo(3));

        AnalyticsResponse a = analytics(created.code());
        assertThat(a.totalClicks()).isEqualTo(3);
        assertThat(a.clicksInRange()).isEqualTo(3);
        assertThat(a.series()).hasSize(1);
        assertThat(a.series().get(0).clicks()).isEqualTo(3);
        assertThat(a.topReferrers()).extracting("referrer").containsExactly(
                "https://ref-a.example", "https://ref-b.example");
        assertThat(a.topReferrers().get(0).clicks()).isEqualTo(2);
    }

    @Test
    void fiftyConcurrentClicksAreAllCountedExactlyOnce() throws Exception {
        LinkResponse created = createLink();
        Long linkId = linkService.resolve(created.code()).linkId();

        ExecutorService callers = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                tasks.add(() -> {
                    clickService.recordClick(linkId, "https://ref.example", "ua");
                    return null;
                });
            }
            for (Future<Void> f : callers.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            callers.shutdown();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(linkService.getDetails(created.code()).totalClicks()).isEqualTo(50));
        // Counter and event rows agree: no lost or duplicated updates.
        assertThat(analytics(created.code()).clicksInRange()).isEqualTo(50);
    }

    @Test
    void clickForMissingLinkFailsQuietlyAndLeavesNoRows() {
        clickService.recordClick(-12345L, "https://ref.example", "ua"); // must not throw

        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(clicks.countInRange(-12345L, Instant.EPOCH, Instant.now().plusSeconds(60))).isZero());
    }
}
