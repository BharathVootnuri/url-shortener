package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.config.CommonErrorResponses;
import com.bharath.projects.urlshortener.dto.ApiError;
import com.bharath.projects.urlshortener.dto.RedirectTarget;
import com.bharath.projects.urlshortener.service.ClickService;
import com.bharath.projects.urlshortener.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Tag(name = "Redirect", description = "The public short URL itself.")
public class RedirectController {

    private final LinkService linkService;
    private final ClickService clickService;

    public RedirectController(LinkService linkService, ClickService clickService) {
        this.linkService = linkService;
        this.clickService = clickService;
    }

    /**
     * 302 (not 301) so browsers never cache the redirect: every click reaches the server, which keeps
     * analytics accurate and lets expiry take effect immediately. Unknown or malformed codes give 404,
     * expired links 410 (see the exception handler).
     */
    @Operation(summary = "Redirect to the original URL",
            description = "Sends the browser to the original URL with a 302 and records the click asynchronously "
                    + "(timestamp, Referer and User-Agent; no IP address). HEAD requests redirect but are not counted. "
                    + "Browsers asking for text/html get a small HTML error page instead of JSON on 404/410.")
    @ApiResponse(responseCode = "302", description = "Redirect to the original URL.",
            headers = {
                    @Header(name = "Location", description = "The original URL.",
                            schema = @Schema(type = "string", example = "https://example.com/some/long/path")),
                    @Header(name = "Cache-Control", description = "Always no-store, so every click reaches the server.",
                            schema = @Schema(type = "string", example = "no-store"))
            })
    @ApiResponse(responseCode = "404", description = "Unknown or malformed code (LINK_NOT_FOUND).",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "410", description = "The link has expired (LINK_EXPIRED).",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @CommonErrorResponses
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
            @Parameter(description = "Short code of the link.", example = "aB3dE9x") @PathVariable String code,
            @Parameter(hidden = true) HttpServletRequest request) {
        RedirectTarget target = linkService.resolve(code);

        // HEAD requests (link checkers, previews) are not clicks. Recording is asynchronous and never fails the redirect.
        if (!"HEAD".equals(request.getMethod())) {
            clickService.recordClick(target.linkId(),
                    request.getHeader(HttpHeaders.REFERER),
                    request.getHeader(HttpHeaders.USER_AGENT));
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target.url()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
