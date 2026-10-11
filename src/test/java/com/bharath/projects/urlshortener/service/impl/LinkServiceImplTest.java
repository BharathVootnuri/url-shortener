package com.bharath.projects.urlshortener.service.impl;

import com.bharath.projects.urlshortener.config.AppProperties;
import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.dto.LinkDetailsResponse;
import com.bharath.projects.urlshortener.dto.LinkResponse;
import com.bharath.projects.urlshortener.dto.LinkStatus;
import com.bharath.projects.urlshortener.dto.RedirectTarget;
import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.exception.CodeGenerationException;
import com.bharath.projects.urlshortener.exception.InvalidExpiryException;
import com.bharath.projects.urlshortener.exception.InvalidUrlException;
import com.bharath.projects.urlshortener.exception.LinkExpiredException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.bharath.projects.urlshortener.util.ShortCodeFormat;
import com.bharath.projects.urlshortener.util.ShortCodeGenerator;
import com.bharath.projects.urlshortener.util.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceImplTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String CODE = "Ab3dE9x";

    @Mock
    ShortLinkRepository links;
    @Mock
    ShortCodeGenerator generator;

    private LinkServiceImpl service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                "http://localhost:8080/",   // trailing slash on purpose
                new AppProperties.ShortCode(7, 3),
                new AppProperties.ClickRecording(2, 4, 10, 5),
                new AppProperties.UrlValidation(2048, false),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(true, 30, 60, 30));
        service = new LinkServiceImpl(
                links,
                new UrlValidator(props),
                generator,
                new ShortCodeFormat(props),
                props,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ShortLink link(String code, Instant expiresAt) {
        ShortLink l = new ShortLink(code, "https://example.com/a?b=1", NOW.minusSeconds(60), expiresAt);
        ReflectionTestUtils.setField(l, "id", 42L);
        ReflectionTestUtils.setField(l, "clickCount", 5L);
        return l;
    }

    // ---------------- create ----------------

    @Test
    void createReturnsNormalizedUrlCodeAndShortUrl() {
        when(generator.generate()).thenReturn(CODE);
        when(links.saveAndFlush(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));

        LinkResponse response = service.create(new CreateLinkRequest("HTTPS://Example.com/a?b=1", null));

        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/" + CODE);
        assertThat(response.originalUrl()).isEqualTo("https://example.com/a?b=1");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isNull();
    }

    @Test
    void createStoresFutureExpiry() {
        Instant expiry = NOW.plusSeconds(3600);
        when(generator.generate()).thenReturn(CODE);
        when(links.saveAndFlush(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));

        LinkResponse response = service.create(new CreateLinkRequest("https://example.com", expiry));

        assertThat(response.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void createRejectsInvalidUrlWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create(new CreateLinkRequest("ftp://example.com", null)))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> service.create(new CreateLinkRequest(null, null)))
                .isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(links, generator);
    }

    @Test
    void createRejectsExpiryNotInTheFuture() {
        assertThatThrownBy(() -> service.create(new CreateLinkRequest("https://example.com", NOW)))
                .isInstanceOf(InvalidExpiryException.class);
        assertThatThrownBy(() -> service.create(new CreateLinkRequest("https://example.com", NOW.minusSeconds(1))))
                .isInstanceOf(InvalidExpiryException.class);

        verifyNoInteractions(links);
    }

    @Test
    void createAcceptsExpiryExactlyFiveYearsAheadButNotBeyond() {
        Instant fiveYears = NOW.atZone(ZoneOffset.UTC).plusYears(5).toInstant();
        when(generator.generate()).thenReturn(CODE);
        when(links.saveAndFlush(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(new CreateLinkRequest("https://example.com", fiveYears)).expiresAt())
                .isEqualTo(fiveYears);
        assertThatThrownBy(() -> service.create(new CreateLinkRequest("https://example.com", fiveYears.plusSeconds(1))))
                .isInstanceOf(InvalidExpiryException.class);
    }

    @Test
    void createRetriesWithNewCodeOnCollision() {
        when(generator.generate()).thenReturn("AAAAAAA", "BBBBBBB");
        when(links.saveAndFlush(any(ShortLink.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(i -> i.getArgument(0));
        when(links.existsByCode("AAAAAAA")).thenReturn(true);

        LinkResponse response = service.create(new CreateLinkRequest("https://example.com", null));

        assertThat(response.code()).isEqualTo("BBBBBBB");
    }

    @Test
    void createGivesUpAfterConfiguredAttempts() {
        when(generator.generate()).thenReturn("AAAAAAA");
        when(links.saveAndFlush(any(ShortLink.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(links.existsByCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateLinkRequest("https://example.com", null)))
                .isInstanceOf(CodeGenerationException.class)
                .hasMessageContaining("3 attempts");

        verify(links, org.mockito.Mockito.times(3)).saveAndFlush(any(ShortLink.class));
    }

    @Test
    void createDoesNotRetryIntegrityErrorsThatAreNotCollisions() {
        when(generator.generate()).thenReturn(CODE);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("not null violated");
        when(links.saveAndFlush(any(ShortLink.class))).thenThrow(failure);
        when(links.existsByCode(CODE)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateLinkRequest("https://example.com", null)))
                .isSameAs(failure);

        verify(links, org.mockito.Mockito.times(1)).saveAndFlush(any(ShortLink.class));
    }

    @Test
    void sameUrlTwiceGivesTwoDifferentCodes() {
        when(generator.generate()).thenReturn("AAAAAAA", "BBBBBBB");
        when(links.saveAndFlush(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));

        LinkResponse first = service.create(new CreateLinkRequest("https://example.com", null));
        LinkResponse second = service.create(new CreateLinkRequest("https://example.com", null));

        assertThat(first.code()).isNotEqualTo(second.code());
    }

    // ---------------- details ----------------

    @Test
    void detailsOfActiveLink() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, NOW.plusSeconds(1))));

        LinkDetailsResponse d = service.getDetails(CODE);

        assertThat(d.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(d.totalClicks()).isEqualTo(5);
        assertThat(d.shortUrl()).isEqualTo("http://localhost:8080/" + CODE);
        assertThat(d.originalUrl()).isEqualTo("https://example.com/a?b=1");
    }

    @Test
    void detailsOfLinkWithoutExpiryAreActive() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, null)));

        assertThat(service.getDetails(CODE).status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(service.getDetails(CODE).expiresAt()).isNull();
    }

    @Test
    void detailsOfExpiredLinkAreStillReadable() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, NOW)));

        LinkDetailsResponse d = service.getDetails(CODE);

        assertThat(d.status()).isEqualTo(LinkStatus.EXPIRED);
        assertThat(d.originalUrl()).isNotBlank();
    }

    @Test
    void detailsOfUnknownCodeIsNotFound() {
        when(links.findByCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetails(CODE)).isInstanceOf(LinkNotFoundException.class);
    }

    // ---------------- resolve ----------------

    @Test
    void resolveReturnsTargetAndLinkId() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, NOW.plusSeconds(1))));

        RedirectTarget target = service.resolve(CODE);

        assertThat(target.url()).isEqualTo("https://example.com/a?b=1");
        assertThat(target.linkId()).isEqualTo(42L);
    }

    @Test
    void resolveRejectsLinkExpiringExactlyNow() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, NOW)));

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(LinkExpiredException.class);
    }

    @Test
    void resolveRejectsExpiredLink() {
        when(links.findByCode(CODE)).thenReturn(Optional.of(link(CODE, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(LinkExpiredException.class);
    }

    @Test
    void resolveUnknownCodeIsNotFound() {
        when(links.findByCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void malformedCodesAreNotFoundWithoutQueryingTheDatabase() {
        for (String bad : new String[]{null, "", "short", "waytoolongcode", "abc-def", "favicon.ico", "abc def"}) {
            assertThatThrownBy(() -> service.resolve(bad)).isInstanceOf(LinkNotFoundException.class);
            assertThatThrownBy(() -> service.getDetails(bad)).isInstanceOf(LinkNotFoundException.class);
        }

        verifyNoInteractions(links);
        verify(links, never()).findByCode(anyString());
    }
}
