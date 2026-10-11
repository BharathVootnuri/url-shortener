package com.bharath.projects.urlshortener.repository;

import com.bharath.projects.urlshortener.config.AppProperties;
import com.bharath.projects.urlshortener.entity.ClickEvent;
import com.bharath.projects.urlshortener.entity.ShortLink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the "local" profile (H2 in PostgreSQL mode) with the real Flyway migration,
 * so it also proves the migration and entity mappings agree (ddl-auto=validate).
 */
@SpringBootTest
class RepositoryIntegrationTest {

    private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    ShortLinkRepository links;

    @Autowired
    ClickEventRepository clicks;

    @Autowired
    AppProperties props;

    private String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    private ShortLink saveLink(Instant expiresAt) {
        return links.save(new ShortLink(uniqueCode(), "https://example.com/a?b=1", T, expiresAt));
    }

    @Test
    void localProfileBindsAppProperties() {
        assertThat(props.urlValidation().allowPrivateHosts()).isTrue();
        assertThat(props.shortCode().length()).isEqualTo(7);
        assertThat(props.cors().allowedOrigins()).containsExactly("http://localhost:4200");
    }

    @Test
    void savesAndFindsByCode() {
        ShortLink saved = saveLink(T.plusSeconds(3600));

        ShortLink found = links.findByCode(saved.getCode()).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getOriginalUrl()).isEqualTo("https://example.com/a?b=1");
        assertThat(found.getCreatedAt()).isEqualTo(T);
        assertThat(found.getExpiresAt()).isEqualTo(T.plusSeconds(3600));
        assertThat(found.getClickCount()).isZero();
    }

    @Test
    void unknownCodeIsEmpty() {
        assertThat(links.findByCode("nope123")).isEmpty();
    }

    @Test
    void duplicateCodeIsRejectedByDatabase() {
        ShortLink first = saveLink(null);

        assertThatThrownBy(() -> links.saveAndFlush(
                new ShortLink(first.getCode(), "https://other.example.com", T, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incrementClickCountIsAtomicUnderConcurrency() throws Exception {
        ShortLink link = saveLink(null);
        int threads = 10;
        int perThread = 5; // 50 increments total

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < perThread; j++) {
                        links.incrementClickCount(link.getId());
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(links.findByCode(link.getCode()).orElseThrow().getClickCount())
                .isEqualTo(threads * perThread);
    }

    @Test
    void incrementOnMissingLinkUpdatesNothing() {
        assertThat(links.incrementClickCount(-1L)).isZero();
    }

    @Test
    void countsClicksInHalfOpenRange() {
        ShortLink link = saveLink(null);
        clicks.save(new ClickEvent(link.getId(), T, null, null));                    // == from: included
        clicks.save(new ClickEvent(link.getId(), T.plusSeconds(30), null, null));    // inside
        clicks.save(new ClickEvent(link.getId(), T.plusSeconds(60), null, null));    // == to: excluded
        clicks.save(new ClickEvent(link.getId(), T.minusSeconds(1), null, null));    // before: excluded

        assertThat(clicks.countInRange(link.getId(), T, T.plusSeconds(60))).isEqualTo(2);
    }

    @Test
    void clicksOfOtherLinksAreNotCounted() {
        ShortLink a = saveLink(null);
        ShortLink b = saveLink(null);
        clicks.save(new ClickEvent(a.getId(), T, null, null));
        clicks.save(new ClickEvent(b.getId(), T, null, null));

        assertThat(clicks.countInRange(a.getId(), T.minusSeconds(1), T.plusSeconds(1))).isEqualTo(1);
    }

    @Test
    void topReferrersAreOrderedLimitedAndSkipNulls() {
        ShortLink link = saveLink(null);
        for (int i = 0; i < 3; i++) {
            clicks.save(new ClickEvent(link.getId(), T.plusSeconds(i), "https://a.example", "ua"));
        }
        for (int i = 0; i < 2; i++) {
            clicks.save(new ClickEvent(link.getId(), T.plusSeconds(i), "https://b.example", "ua"));
        }
        clicks.save(new ClickEvent(link.getId(), T, "https://c.example", "ua"));
        clicks.save(new ClickEvent(link.getId(), T, null, "ua")); // no referrer: excluded

        List<ClickEventRepository.ReferrerCount> top = clicks.topReferrers(
                link.getId(), T.minusSeconds(1), T.plusSeconds(100), PageRequest.of(0, 2));

        assertThat(top).hasSize(2);
        assertThat(top.get(0).getReferrer()).isEqualTo("https://a.example");
        assertThat(top.get(0).getClicks()).isEqualTo(3);
        assertThat(top.get(1).getReferrer()).isEqualTo("https://b.example");
        assertThat(top.get(1).getClicks()).isEqualTo(2);
    }

    @Test
    void clickEventRequiresExistingLink() {
        assertThatThrownBy(() -> clicks.saveAndFlush(new ClickEvent(-999L, T, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
