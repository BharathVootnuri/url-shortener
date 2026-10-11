package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.entity.ShortLink;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC5.1 / AC5.2: database failures become clean 503/500 responses with no internals leaked,
 * and the service works again as soon as the database does.
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class DatabaseFailureApiIntegrationTest {

    private static final String CODE = "Ab3dE9x";
    private static final String SECRET = "jdbc:postgresql://secret-host:5432/db?password=hunter2";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ShortLinkRepository links;

    @BeforeEach
    void healthyDatabase() {
        reset(links);
    }

    private static void assertNoLeak(String body) {
        assertThat(body).doesNotContain("jdbc", "hunter2", "secret-host", "Exception", "com.bharath", "SQL");
    }

    @Test
    void detailsReturn503WithRetryAfterWhenTheDatabaseIsDown() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new DataAccessResourceFailureException(SECRET));

        String body = mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(header().string("Retry-After", "5"))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    @Test
    void redirectReturns503AndBrowsersGetAFriendlyPage() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new DataAccessResourceFailureException(SECRET));

        mvc.perform(get("/" + CODE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Location"));

        String html = mvc.perform(get("/" + CODE).header("Accept", "text/html"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("temporarily unavailable")))
                .andReturn().getResponse().getContentAsString();
        assertNoLeak(html);
    }

    @Test
    void createReturns503WhenNoConnectionCanBeObtained() throws Exception {
        when(links.saveAndFlush(any(ShortLink.class)))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager: " + SECRET));

        String body = mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    @Test
    void queryTimeoutsAreTreatedAsTemporary() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new QueryTimeoutException(SECRET));

        mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @Test
    void nonRetryableDatabaseErrorsAre500WithoutDetails() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new DataIntegrityViolationException(SECRET));

        String body = mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_ERROR"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    @Test
    void unexpectedErrorsAre500WithoutDetails() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new IllegalStateException(SECRET));

        String body = mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An internal error occurred."))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    @Test
    void ac5_2_serviceRecoversWithoutARestartOnceTheDatabaseIsBack() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new DataAccessResourceFailureException(SECRET));
        mvc.perform(get("/api/v1/short-urls/" + CODE)).andExpect(status().isServiceUnavailable());

        // Database "comes back": same running application now answers normally (404 = healthy lookup).
        // doReturn(...).when(...) avoids invoking the still-throwing stub while re-stubbing.
        doReturn(Optional.empty()).when(links).findByCode(anyString());
        mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));

        ShortLink link = new ShortLink(CODE, "https://example.com/back", Instant.now(), null);
        doReturn(Optional.of(link)).when(links).findByCode(CODE);
        mvc.perform(get("/api/v1/short-urls/" + CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/back"))
                .andExpect(content().string(not(containsString("jdbc"))));
    }
}
