package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.repository.ClickEventRepository;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box tests of the HTTP API against the real application (H2, Flyway, async click executor).
 * Rate limits are raised so these tests never trip them; limits are covered in
 * {@link ApiHardeningIntegrationTest}. Test names reference the acceptance criteria in
 * docs/0.Requirements-analysis.md.
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class LinkApiIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    MockMvc mvc;
    @Autowired
    ShortLinkRepository links;
    @Autowired
    ClickEventRepository clicks;

    // ------------------------------------------------------------------ helpers

    private static String body(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    private MvcResult create(String json) throws Exception {
        return mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private String createLink(String url) throws Exception {
        MvcResult result = create(body(url));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private ShortLink saveExpiredLink(String code) {
        return links.save(new ShortLink(code, "https://example.com/old",
                Instant.now().minus(Duration.ofDays(2)), Instant.now().minusSeconds(1)));
    }

    private int totalClicks(String code) throws Exception {
        String json = mvc.perform(get("/api/v1/short-urls/" + code)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.totalClicks");
    }

    // ------------------------------------------------------------------ create (AC1.x)

    @Test
    void ac1_1_createReturns201WithCodeShortUrlAndLocation() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://example.com/a?b=1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/a?b=1"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();

        String code = JsonPath.read(result.getResponse().getContentAsString(), "$.code");
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.shortUrl"))
                .isEqualTo("http://localhost:8080/" + code);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/short-urls/" + code);
    }

    @Test
    void createNormalizesTheUrl() throws Exception {
        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content(body("  HTTPS://Example.COM/Path?Q=A  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/Path?Q=A"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"url\":\"ftp://x.com\"}",
            "{\"url\":\"javascript:alert(1)\"}",
            "{\"url\":\"data:text/html,hi\"}",
            "{\"url\":\"//x.com\"}",
            "{\"url\":\"example.com\"}",
            "{\"url\":\"\"}",
            "{\"url\":\"   \"}",
            "{\"url\":null}",
            "{}",
            "{\"url\":{\"nested\":1}}",
            "{\"url\":[\"https://example.com\"]}",
            "{\"url\":\"https://user:pass@example.com\"}",
            "{\"url\":\"http://2130706433/\"}",
            "{\"url\":\"https://example.com/a b\"}"})
    void ac1_2_invalidUrlsAreRejectedAndNothingIsStored(String json) throws Exception {
        long before = links.count();

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_URL"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId", notNullValue()));

        assertThat(links.count()).isEqualTo(before);
    }

    @Test
    void ac1_2_urlOverMaximumLengthIsRejected() throws Exception {
        String tooLong = "https://example.com/" + "a".repeat(2100);

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content(body(tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_URL"));
    }

    @Test
    void ac1_4_futureExpiryIsStoredAndReturned() throws Exception {
        Instant expiry = Instant.now().plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"expiresAt\":\"" + expiry + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiry.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"2000-01-01T00:00:00Z\"",      // in the past
            "\"2099-01-01T00:00:00Z\"",      // beyond 5 years
            "\"tomorrow\"",                  // not a date
            "\"2030-13-45T99:00:00Z\"",      // impossible date
            "{\"nested\":1}"})
    void ac1_4_badExpiryIsRejected(String expiresAtJson) throws Exception {
        long before = links.count();

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"expiresAt\":" + expiresAtJson + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPIRY"));

        assertThat(links.count()).isEqualTo(before);
    }

    @Test
    void ac1_6_sameUrlTwiceGivesDifferentCodes() throws Exception {
        assertThat(createLink("https://example.com/same")).isNotEqualTo(createLink("https://example.com/same"));
    }

    // ------------------------------------------------------------------ redirect (AC2.x)

    @Test
    void ac2_1_redirectIs302WithLocationAndNoStoreAndRecordsTheClick() throws Exception {
        String code = createLink("https://example.com/page?x=1");

        mvc.perform(get("/" + code).header("Referer", "https://ref.example/post").header("User-Agent", "JUnit/1.0"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page?x=1"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Referrer-Policy"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(totalClicks(code)).isEqualTo(1));
        mvc.perform(get("/api/v1/short-urls/" + code + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("https://ref.example/post"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(1));
    }

    @Test
    void headRequestsRedirectButAreNotCountedAsClicks() throws Exception {
        String code = createLink("https://example.com/head");

        mvc.perform(head("/" + code).header("User-Agent", "link-checker")).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        // Exactly the one GET is counted, and it stays at one (a late HEAD click would make it two).
        await().atMost(Duration.ofSeconds(10)).during(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(totalClicks(code)).isEqualTo(1));
    }

    @Test
    void ac2_2_unknownCodeIs404ProblemJson() throws Exception {
        mvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void ac2_2_browsersGetAnHtmlPageInsteadOfJson() throws Exception {
        String html = mvc.perform(get("/zzzzzzz").header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<h1>Link not found</h1>")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("zzzzzzz"); // nothing from the request is echoed into the page
    }

    @Test
    void ac2_3_expiredLinkIs410AndDoesNotRedirectOrRecordAClick() throws Exception {
        ShortLink expired = saveExpiredLink("Exp0001");

        mvc.perform(get("/" + expired.getCode()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/" + expired.getCode()).header("Accept", "text/html"))
                .andExpect(status().isGone())
                .andExpect(content().string(containsString("expired")));

        assertThat(clicks.countInRange(expired.getId(), Instant.EPOCH, Instant.now().plusSeconds(60))).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "abcdefgh", "abc-efg", "favicon.ico", "robots.txt", "%20%20%20%20%20%20%20"})
    void ac2_4_malformedCodesAre404(String path) throws Exception {
        mvc.perform(get("/" + path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void redirectWithOtherMethodsIs405() throws Exception {
        mvc.perform(post("/Ab3dE9x"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    // ------------------------------------------------------------------ details (AC3.x)

    @Test
    void ac3_1_detailsOfActiveLink() throws Exception {
        String code = createLink("https://example.com/details");

        mvc.perform(get("/api/v1/short-urls/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + code))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/details"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void ac3_2_detailsOfExpiredLinkStillReadable() throws Exception {
        ShortLink expired = saveExpiredLink("Exp0002");

        mvc.perform(get("/api/v1/short-urls/" + expired.getCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/old"));
    }

    @Test
    void ac3_3_unknownCodeDetailsIs404() throws Exception {
        mvc.perform(get("/api/v1/short-urls/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        mvc.perform(get("/api/v1/short-urls/x"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ analytics (AC4.x)

    @Test
    void ac4_1_analyticsCountsClicksBucketsThemAndSumsConsistently() throws Exception {
        String code = createLink("https://example.com/analytics");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + code)).andExpect(status().isFound());
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(totalClicks(code)).isEqualTo(3));

        for (String granularity : new String[]{"DAY", "day", "HOUR"}) {
            mvc.perform(get("/api/v1/short-urls/" + code + "/analytics").param("granularity", granularity))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalClicks").value(3))
                    .andExpect(jsonPath("$.clicksInRange").value(3))
                    .andExpect(jsonPath("$.granularity").value(granularity.toUpperCase()))
                    .andExpect(jsonPath("$.series[0].clicks").value(3));
        }
    }

    @Test
    void ac4_5_linkWithoutClicksHasZerosAndEmptySeries() throws Exception {
        String code = createLink("https://example.com/quiet");

        mvc.perform(get("/api/v1/short-urls/" + code + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.clicksInRange").value(0))
                .andExpect(jsonPath("$.series").isEmpty())
                .andExpect(jsonPath("$.topReferrers").isEmpty())
                .andExpect(jsonPath("$.granularity").value("DAY"));
    }

    @Test
    void ac4_4_badRangesAndParametersAre400() throws Exception {
        String code = createLink("https://example.com/range");
        String url = "/api/v1/short-urls/" + code + "/analytics";

        // from after to
        mvc.perform(get(url).param("from", "2030-01-02T00:00:00Z").param("to", "2030-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        // longer than 366 days
        mvc.perform(get(url).param("from", "2028-01-01T00:00:00Z").param("to", "2030-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        // invalid granularity / dates
        mvc.perform(get(url).param("granularity", "WEEK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'granularity'."));
        mvc.perform(get(url).param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        // exactly 366 days is allowed
        mvc.perform(get(url).param("from", "2029-01-01T00:00:00Z").param("to", "2030-01-02T00:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    void analyticsOfUnknownCodeIs404() throws Exception {
        mvc.perform(get("/api/v1/short-urls/zzzzzzz/analytics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ errors (AC5.x)

    @Test
    void ac5_3_malformedJsonIs400AndUnsupportedMediaTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Unrecognized"))));
        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.TEXT_PLAIN).content("https://example.com"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unmappedApiPathAndWrongMethodUseTheSameErrorFormat() throws Exception {
        mvc.perform(get("/api/v1/nope/at/all"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId", notNullValue()));
        mvc.perform(get("/api/v1/short-urls"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void ac5_1_errorBodiesNeverLeakInternals() throws Exception {
        String[] responses = {
                mvc.perform(get("/api/v1/short-urls/zzzzzzz")).andReturn().getResponse().getContentAsString(),
                mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/api/v1/nope/at/all")).andReturn().getResponse().getContentAsString()};

        for (String json : responses) {
            assertThat(json).contains("\"traceId\"", "\"code\"", "\"status\"");
            assertThat(json).doesNotContain("Exception", "com.bharath", "at org.", "SELECT", "select ", "jdbc");
        }
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void correlationIdIsGeneratedEchoedAndSanitized() throws Exception {
        String generated = mvc.perform(get("/api/v1/short-urls/zzzzzzz"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");

        mvc.perform(get("/api/v1/short-urls/zzzzzzz").header("X-Correlation-Id", "client-req_42.a"))
                .andExpect(header().string("X-Correlation-Id", "client-req_42.a"))
                .andExpect(jsonPath("$.traceId").value("client-req_42.a"));

        String replaced = mvc.perform(get("/api/v1/short-urls/zzzzzzz").header("X-Correlation-Id", "bad id with spaces"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(replaced).doesNotContain(" ").matches("[0-9a-f-]{36}");
    }
}
