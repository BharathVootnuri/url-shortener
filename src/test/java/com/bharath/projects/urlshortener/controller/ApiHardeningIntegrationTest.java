package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting, CORS, security headers and request-size limits, using deliberately tiny limits and
 * production URL rules (private hosts rejected). Each test uses its own client IP so the in-memory
 * rate-limit state of one test cannot affect another.
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=3",
        "app.rate-limit.miss-per-minute=3",
        "app.rate-limit.analytics-per-minute=3",
        "app.url-validation.allow-private-hosts=false"})
@AutoConfigureMockMvc
class ApiHardeningIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200"; // from the local profile

    @Autowired
    MockMvc mvc;
    @Autowired
    ShortLinkRepository links;

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder createFrom(String ip, String url) {
        return from(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}"), ip);
    }

    // ------------------------------------------------------------------ rate limiting

    @Test
    void ac5_4_creationIsRateLimitedPerClientWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(createFrom("10.0.1.1", "https://example.com/" + i)).andExpect(status().isCreated());
        }

        String retryAfter = mvc.perform(createFrom("10.0.1.1", "https://example.com/4"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);

        // Other clients are unaffected.
        mvc.perform(createFrom("10.0.1.2", "https://example.com/other")).andExpect(status().isCreated());
    }

    @Test
    void rateLimitedResponseStillCarriesCorsHeadersSoBrowserAppsCanReadIt() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(createFrom("10.0.2.1", "https://example.com/" + i)).andExpect(status().isCreated());
        }

        mvc.perform(createFrom("10.0.2.1", "https://example.com/x").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void invalidCreateRequestsCountTowardsTheLimitToo() throws Exception {
        // The limit is on requests, not successes, so a client cannot probe validation rules without
        // limit: 3 requests of any kind, then 429.
        for (int i = 0; i < 3; i++) {
            mvc.perform(createFrom("10.0.3.1", "ftp://nope")).andExpect(status().isBadRequest());
        }
        mvc.perform(createFrom("10.0.3.1", "https://example.com")).andExpect(status().isTooManyRequests());
    }

    @Test
    void clientsThatKeepGuessingUnknownCodesAreThrottled() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(from(get("/zzzzzz" + i), "10.0.4.1")).andExpect(status().isNotFound());
        }

        mvc.perform(from(get("/zzzzzz9"), "10.0.4.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        mvc.perform(from(get("/api/v1/short-urls/zzzzzz9"), "10.0.4.1"))
                .andExpect(status().isTooManyRequests());

        // A well-behaved client elsewhere is unaffected.
        mvc.perform(from(get("/zzzzzz9"), "10.0.4.2")).andExpect(status().isNotFound());
    }

    @Test
    void successfulRedirectsAreNeverThrottledByTheMissLimit() throws Exception {
        String json = mvc.perform(createFrom("10.0.5.1", "https://example.com/popular"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(json, "$.code");

        for (int i = 0; i < 25; i++) {
            mvc.perform(from(get("/" + code), "10.0.5.2")).andExpect(status().isFound());
        }
    }

    @Test
    void theExpensiveAnalyticsQueryIsThrottledButDetailsAndRedirectsAreNot() throws Exception {
        String json = mvc.perform(createFrom("10.0.8.1", "https://example.com/stats"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(json, "$.code");

        for (int i = 0; i < 3; i++) {
            mvc.perform(from(get("/api/v1/short-urls/" + code + "/analytics"), "10.0.8.2")).andExpect(status().isOk());
        }
        mvc.perform(from(get("/api/v1/short-urls/" + code + "/analytics"), "10.0.8.2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));

        // The same throttled client can still use the cheap endpoints, and other clients can use analytics.
        mvc.perform(from(get("/api/v1/short-urls/" + code), "10.0.8.2")).andExpect(status().isOk());
        mvc.perform(from(get("/" + code), "10.0.8.2")).andExpect(status().isFound());
        mvc.perform(from(get("/api/v1/short-urls/" + code + "/analytics"), "10.0.8.3")).andExpect(status().isOk());
    }

    @Test
    void rateLimitIsNotAppliedToActuator() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(from(get("/actuator/health"), "10.0.6.1")).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ URL rules in prod mode

    @Test
    void productionRulesRejectPrivateAndLocalTargets() throws Exception {
        String[] blocked = {"http://localhost:8080/", "http://127.0.0.1/", "http://169.254.169.254/latest/",
                "http://10.0.0.5/admin", "http://[::1]/", "http://intranet/"};
        for (int i = 0; i < blocked.length; i++) {
            // distinct client per request so the create rate limit (3/min) is not what is being tested
            mvc.perform(createFrom("10.0.7." + (10 + i), blocked[i]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_URL"));
        }
        mvc.perform(createFrom("10.0.7.2", "https://example.com/ok")).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void corsPreflightAllowedForConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/v1/short-urls")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mvc.perform(options("/api/v1/short-urls")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsIsNotWildcardAndForeignOriginsAreRefused() throws Exception {
        mvc.perform(from(get("/api/v1/short-urls/zzzzzzz").header("Origin", ALLOWED_ORIGIN), "10.0.8.1"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));

        mvc.perform(from(get("/api/v1/short-urls/zzzzzzz").header("Origin", "http://evil.example"), "10.0.8.2"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ------------------------------------------------------------------ headers

    @Test
    void securityHeadersArePresentOnApiResponses() throws Exception {
        mvc.perform(from(get("/api/v1/short-urls/zzzzzzz"), "10.0.9.1"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void securityHeadersArePresentOnRedirectsWithoutReferrerPolicy() throws Exception {
        String json = mvc.perform(createFrom("10.0.9.2", "https://example.com/hdr"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(json, "$.code");

        mvc.perform(from(get("/" + code), "10.0.9.3"))
                .andExpect(status().isFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Referrer-Policy"));
    }

    @Test
    void hstsOnlyOnSecureRequests() throws Exception {
        mvc.perform(from(get("/api/v1/short-urls/zzzzzzz"), "10.0.9.4"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(from(get("/api/v1/short-urls/zzzzzzz").secure(true), "10.0.9.5"))
                .andExpect(header().string("Strict-Transport-Security", matchesPattern("max-age=\\d+")));
    }

    // ------------------------------------------------------------------ request size

    @Test
    void oversizedBodiesAreRejectedWith413AndNothingIsStored() throws Exception {
        long before = links.count();
        String huge = "{\"url\":\"https://example.com/" + "a".repeat(10_000) + "\"}";

        mvc.perform(from(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content(huge), "10.0.10.1"))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(links.count()).isEqualTo(before);
    }
}
