package com.bharath.projects.urlshortener.service;

import com.bharath.projects.urlshortener.config.AppProperties;
import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.dto.LinkResponse;
import com.bharath.projects.urlshortener.exception.CodeGenerationException;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import com.bharath.projects.urlshortener.util.ShortCodeGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Short-code collisions against the REAL unique constraint (the unit tests only simulate them with mocks):
 * the generator is forced to return an already-used code, and the service must retry with a fresh one
 * without overwriting the existing link, and give up cleanly after the configured number of attempts.
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class CollisionRetryIntegrationTest {

    @Autowired
    LinkService linkService;
    @Autowired
    ShortLinkRepository links;
    @Autowired
    AppProperties props;
    @Autowired
    MockMvc mvc;
    @MockitoSpyBean
    ShortCodeGenerator generator;

    private LinkResponse createExisting() {
        LinkResponse existing = linkService.create(new CreateLinkRequest("https://example.com/existing", null));
        clearInvocations(generator);
        return existing;
    }

    @Test
    void collisionWithAnExistingCodeIsRetriedAndTheExistingLinkIsLeftUntouched() {
        LinkResponse existing = createExisting();
        // First two draws collide with the existing code, the third is a genuine random code.
        doReturn(existing.code()).doReturn(existing.code()).doCallRealMethod().when(generator).generate();

        LinkResponse created = linkService.create(new CreateLinkRequest("https://example.com/new", null));

        assertThat(created.code()).isNotEqualTo(existing.code());
        verify(generator, times(3)).generate();
        assertThat(linkService.getDetails(existing.code()).originalUrl()).isEqualTo("https://example.com/existing");
        assertThat(linkService.getDetails(created.code()).originalUrl()).isEqualTo("https://example.com/new");
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsWithoutCreatingAnything() {
        LinkResponse existing = createExisting();
        doReturn(existing.code()).when(generator).generate();
        long before = links.count();

        assertThatThrownBy(() -> linkService.create(new CreateLinkRequest("https://example.com/never", null)))
                .isInstanceOf(CodeGenerationException.class);

        verify(generator, times(props.shortCode().maxCollisionRetries())).generate();
        assertThat(links.count()).isEqualTo(before);
        assertThat(linkService.getDetails(existing.code()).originalUrl()).isEqualTo("https://example.com/existing");
    }

    @Test
    void persistentCollisionsSurfaceAs503WithRetryAfterAndTheServiceRecoversAfterwards() throws Exception {
        LinkResponse existing = createExisting();
        doReturn(existing.code()).when(generator).generate();

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("CODE_ALLOCATION_FAILED"));

        doCallRealMethod().when(generator).generate();
        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isCreated());
    }
}
