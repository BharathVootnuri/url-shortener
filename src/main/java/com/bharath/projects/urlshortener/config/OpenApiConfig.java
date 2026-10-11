package com.bharath.projects.urlshortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level metadata of the generated OpenAPI document. The server URL comes from {@code app.base-url}
 * (not from the incoming request) so the spec is identical wherever it is generated, which also keeps
 * the committed {@code docs/openapi.json} comparable in tests.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI urlShortenerOpenApi(AppProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("v1")
                        .description("""
                                Create short links for http/https URLs, redirect from a short code, read link \
                                details and click analytics.

                                Errors use RFC 7807 `application/problem+json` with a stable `code` and a `traceId`. \
                                There is no authentication: the API is public and protected by rate limiting.""")
                        .license(new License().name("Interview assignment - no license")))
                .addServersItem(new Server().url(properties.baseUrl()).description("Configured base URL"));
    }
}
