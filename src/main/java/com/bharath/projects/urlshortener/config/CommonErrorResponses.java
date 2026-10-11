package com.bharath.projects.urlshortener.config;

import com.bharath.projects.urlshortener.dto.ApiError;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents the error responses every endpoint can produce regardless of its own logic
 * (rate limiting, database outage, unexpected failure). Put it on controller methods next to
 * the endpoint-specific {@link ApiResponse}s.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded (RATE_LIMITED).",
                headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying.",
                        schema = @Schema(type = "integer")),
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server or database error (INTERNAL_ERROR, DATABASE_ERROR).",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "503", description = "Database temporarily unavailable (DATABASE_UNAVAILABLE). Safe to retry.",
                headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying.",
                        schema = @Schema(type = "integer")),
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
})
public @interface CommonErrorResponses {
}
