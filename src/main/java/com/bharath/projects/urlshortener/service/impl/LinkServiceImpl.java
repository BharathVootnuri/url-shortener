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
import com.bharath.projects.urlshortener.exception.LinkExpiredException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.bharath.projects.urlshortener.service.LinkService;
import com.bharath.projects.urlshortener.util.ShortCodeFormat;
import com.bharath.projects.urlshortener.util.ShortCodeGenerator;
import com.bharath.projects.urlshortener.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
public class LinkServiceImpl implements LinkService {

    static final int MAX_EXPIRY_YEARS = 5;

    private final ShortLinkRepository links;
    private final UrlValidator urlValidator;
    private final ShortCodeGenerator codeGenerator;
    private final ShortCodeFormat codeFormat;
    private final AppProperties props;
    private final Clock clock;

    public LinkServiceImpl(ShortLinkRepository links,
                           UrlValidator urlValidator,
                           ShortCodeGenerator codeGenerator,
                           ShortCodeFormat codeFormat,
                           AppProperties props,
                           Clock clock) {
        this.links = links;
        this.urlValidator = urlValidator;
        this.codeGenerator = codeGenerator;
        this.codeFormat = codeFormat;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Intentionally NOT {@code @Transactional}: a unique-constraint violation marks a transaction
     * rollback-only (and aborts it on PostgreSQL), so each insert attempt needs its own transaction
     * for the retry loop to work.
     */
    @Override
    public LinkResponse create(CreateLinkRequest request) {
        String url = urlValidator.validateAndNormalize(request.url());
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = validateExpiry(request.expiresAt(), now);

        ShortLink saved = saveWithUniqueCode(url, now, expiresAt);
        return new LinkResponse(saved.getCode(), shortUrl(saved.getCode()), saved.getOriginalUrl(),
                saved.getCreatedAt(), saved.getExpiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public LinkDetailsResponse getDetails(String code) {
        ShortLink link = findOrThrow(code);
        LinkStatus status = link.isExpiredAt(Instant.now(clock)) ? LinkStatus.EXPIRED : LinkStatus.ACTIVE;
        return new LinkDetailsResponse(link.getCode(), shortUrl(link.getCode()), link.getOriginalUrl(),
                link.getCreatedAt(), link.getExpiresAt(), status, link.getClickCount());
    }

    @Override
    @Transactional(readOnly = true)
    public RedirectTarget resolve(String code) {
        ShortLink link = findOrThrow(code);
        if (link.isExpiredAt(Instant.now(clock))) {
            throw new LinkExpiredException();
        }
        return new RedirectTarget(link.getId(), link.getOriginalUrl());
    }

    private ShortLink findOrThrow(String code) {
        if (!codeFormat.isWellFormed(code)) {
            throw new LinkNotFoundException();
        }
        return links.findByCode(code).orElseThrow(LinkNotFoundException::new);
    }

    private Instant validateExpiry(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(now)) {
            throw new InvalidExpiryException("Expiration time must be in the future");
        }
        Instant latest = now.atZone(ZoneOffset.UTC).plusYears(MAX_EXPIRY_YEARS).toInstant();
        if (expiresAt.isAfter(latest)) {
            throw new InvalidExpiryException(
                    "Expiration time must be within " + MAX_EXPIRY_YEARS + " years from now");
        }
        return expiresAt.truncatedTo(ChronoUnit.MILLIS);
    }

    private ShortLink saveWithUniqueCode(String url, Instant createdAt, Instant expiresAt) {
        int attempts = props.shortCode().maxCollisionRetries();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String code = codeGenerator.generate();
            try {
                return links.saveAndFlush(new ShortLink(code, url, createdAt, expiresAt));
            } catch (DataIntegrityViolationException e) {
                // Only a code collision is retried; any other integrity problem is a real bug.
                if (!links.existsByCode(code)) {
                    throw e;
                }
                log.warn("Short code collision on attempt {}/{}", attempt, attempts);
            }
        }
        throw new CodeGenerationException(
                "Could not allocate a unique short code after " + attempts + " attempts");
    }

    private String shortUrl(String code) {
        String base = props.baseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + code;
    }
}
