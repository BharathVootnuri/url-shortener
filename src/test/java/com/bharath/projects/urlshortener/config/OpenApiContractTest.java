package com.bharath.projects.urlshortener.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T16: the generated OpenAPI document must (a) describe the behaviour the rest of the test-suite verifies and
 * (b) match the committed {@code docs/openapi.json}, so API changes can't slip in without the contract
 * (and the Angular client built from it) being updated deliberately.
 *
 * <p>Regenerate the committed file with:
 * {@code .\mvnw.cmd test -Dtest=OpenApiContractTest -Dopenapi.update=true}
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final Path COMMITTED_SPEC = Path.of("docs", "openapi.json");
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    MockMvc mvc;

    private String generatedSpec() throws Exception {
        return mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ drift detection

    @Test
    void committedSpecMatchesTheGeneratedOne() throws Exception {
        JsonNode generated = MAPPER.readTree(generatedSpec());

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED_SPEC.getParent());
            Files.writeString(COMMITTED_SPEC, MAPPER.writeValueAsString(generated) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }

        assertThat(COMMITTED_SPEC).as("docs/openapi.json must exist (generate it with -Dopenapi.update=true)").exists();
        JsonNode committed = MAPPER.readTree(Files.readString(COMMITTED_SPEC, StandardCharsets.UTF_8));
        assertThat(generated)
                .as("docs/openapi.json is out of date. Regenerate: .\\mvnw.cmd test -Dtest=OpenApiContractTest -Dopenapi.update=true")
                .isEqualTo(committed);
    }

    // ------------------------------------------------------------------ content

    @Test
    void documentsExactlyTheThreeApiOperationsAndTheRedirect() throws Exception {
        String spec = generatedSpec();

        Map<String, Object> paths = JsonPath.read(spec, "$.paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/api/v1/short-urls", "/api/v1/short-urls/{code}", "/api/v1/short-urls/{code}/analytics", "/{code}");
        assertThat(JsonPath.<String>read(spec, "$.info.title")).isEqualTo("URL Shortener API");
        assertThat(JsonPath.<String>read(spec, "$.servers[0].url")).isEqualTo("http://localhost:8080");
    }

    @Test
    void everyOperationHasASummaryAndTheCommonErrorResponses() throws Exception {
        String spec = generatedSpec();

        List<String> operations = List.of(
                "$.paths['/api/v1/short-urls'].post",
                "$.paths['/api/v1/short-urls/{code}'].get",
                "$.paths['/api/v1/short-urls/{code}/analytics'].get",
                "$.paths['/{code}'].get");
        for (String op : operations) {
            assertThat(JsonPath.<String>read(spec, op + ".summary")).as(op).isNotBlank();
            Map<String, Object> responses = JsonPath.read(spec, op + ".responses");
            assertThat(responses.keySet()).as(op).contains("429", "500", "503");
        }
    }

    @Test
    void createDocumentsSuccessAndItsErrors() throws Exception {
        Map<String, Object> responses = JsonPath.read(generatedSpec(), "$.paths['/api/v1/short-urls'].post.responses");
        assertThat(responses.keySet()).containsExactlyInAnyOrder("201", "400", "413", "415", "429", "500", "503");
    }

    @Test
    void redirectDocumentsTheLocationHeaderAndGoneForExpiredLinks() throws Exception {
        String spec = generatedSpec();
        Map<String, Object> responses = JsonPath.read(spec, "$.paths['/{code}'].get.responses");

        assertThat(responses.keySet()).containsExactlyInAnyOrder("302", "404", "410", "429", "500", "503");
        assertThat(JsonPath.<Map<String, Object>>read(spec, "$.paths['/{code}'].get.responses['302'].headers"))
                .containsKey("Location");
    }

    @Test
    void errorsAreDocumentedAsProblemJsonWithTheRealFlatShape() throws Exception {
        String spec = generatedSpec();

        assertThat(JsonPath.<Map<String, Object>>read(spec,
                "$.paths['/api/v1/short-urls/{code}'].get.responses['404'].content"))
                .containsOnlyKeys("application/problem+json");

        Map<String, Object> apiError = JsonPath.read(spec, "$.components.schemas.ApiError.properties");
        assertThat(apiError.keySet()).contains("type", "title", "status", "detail", "instance", "code", "traceId");

        // Spring's own ProblemDetail would be documented with a nested 'properties' map; we don't want that.
        Map<String, Object> schemas = JsonPath.read(spec, "$.components.schemas");
        assertThat(schemas).doesNotContainKey("ProblemDetail");
    }

    @Test
    void analyticsParametersAreDocumentedAndGranularityIsAnEnum() throws Exception {
        String spec = generatedSpec();
        String base = "$.paths['/api/v1/short-urls/{code}/analytics'].get.parameters";

        List<String> names = JsonPath.read(spec, base + "[*].name");
        assertThat(names).containsExactlyInAnyOrder("code", "from", "to", "granularity");
        assertThat(JsonPath.<List<String>>read(spec, base + "[?(@.name=='granularity')].schema.enum[*]"))
                .containsExactly("HOUR", "DAY");
        assertThat(JsonPath.<List<Boolean>>read(spec, base + "[?(@.name=='from')].required")).containsExactly(false);
    }

    @Test
    void requestAndResponseSchemasAreDescribed() throws Exception {
        String spec = generatedSpec();

        assertThat(JsonPath.<Map<String, Object>>read(spec, "$.components.schemas.CreateLinkRequest.properties"))
                .containsOnlyKeys("url", "expiresAt");
        assertThat(JsonPath.<List<String>>read(spec, "$.components.schemas.CreateLinkRequest.required"))
                .containsExactly("url");
        assertThat(JsonPath.<Map<String, Object>>read(spec, "$.components.schemas.LinkDetailsResponse.properties"))
                .containsKeys("code", "shortUrl", "originalUrl", "createdAt", "expiresAt", "status", "totalClicks");
        assertThat(JsonPath.<Map<String, Object>>read(spec, "$.components.schemas.AnalyticsResponse.properties"))
                .containsKeys("totalClicks", "clicksInRange", "from", "to", "granularity", "series", "topReferrers");
    }

    // ------------------------------------------------------------------ serving

    @Test
    void swaggerUiIsServedLocallyAndNotBlockedByTheApiSecurityHeaders() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Security-Policy")).isNull())
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Frame-Options")).isNull());
    }

    @Test
    void shortCodeRedirectRouteStillWinsOverSwaggerRoutes() throws Exception {
        // A single-segment path that is not a link must still be a clean 404 problem, not a swagger page.
        mvc.perform(get("/Ab3dE9x").accept("application/json"))
                .andExpect(status().isNotFound());
    }
}
