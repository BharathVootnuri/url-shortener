package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.config.CommonErrorResponses;
import com.bharath.projects.urlshortener.dto.AnalyticsResponse;
import com.bharath.projects.urlshortener.dto.ApiError;
import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.dto.LinkDetailsResponse;
import com.bharath.projects.urlshortener.dto.LinkResponse;
import com.bharath.projects.urlshortener.service.ClickService;
import com.bharath.projects.urlshortener.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping(LinkController.BASE_PATH)
@Tag(name = "Short URLs", description = "Create short URLs, read their details and click analytics.")
public class LinkController {

    public static final String BASE_PATH = "/api/v1/short-urls";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final LinkService linkService;
    private final ClickService clickService;

    public LinkController(LinkService linkService, ClickService clickService) {
        this.linkService = linkService;
        this.clickService = clickService;
    }

    @Operation(summary = "Create a short link",
            description = "Shortens an http/https URL. Optionally sets an expiration instant (must be in the future, "
                    + "at most 5 years ahead). The same URL may be shortened several times; each call creates a new link.")
    @ApiResponse(responseCode = "201", description = "Link created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LinkResponse.class)),
            headers = @Header(name = "Location", description = "Path of the new link resource.",
                    schema = @Schema(type = "string", example = "/api/v1/short-urls/aB3dE9x")))
    @ApiResponse(responseCode = "400",
            description = "INVALID_URL (missing, malformed, unsupported scheme, private host in prod, ...), "
                    + "INVALID_EXPIRY (past or too far ahead) or MALFORMED_REQUEST (unreadable JSON).",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "Request body larger than 8 KB (PAYLOAD_TOO_LARGE).",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "415", description = "Content-Type is not application/json.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @CommonErrorResponses
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request) {
        LinkResponse created = linkService.create(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.code())).body(created);
    }

    @Operation(summary = "Get link details",
            description = "Returns the original URL, expiration, status and all-time click count. "
                    + "Details stay readable after the link has expired (status EXPIRED).")
    @ApiResponse(responseCode = "200", description = "Link details.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LinkDetailsResponse.class)))
    @ApiResponse(responseCode = "404", description = "No link with this code (LINK_NOT_FOUND).",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @CommonErrorResponses
    @GetMapping("/{code}")
    public LinkDetailsResponse details(
            @Parameter(description = "Short code of the link.", example = "aB3dE9x") @PathVariable String code) {
        return linkService.getDetails(code);
    }

    @Operation(summary = "Get click analytics",
            description = "Clicks per UTC time bucket, plus the most frequent referrers, for the range [from, to). "
                    + "Only non-empty buckets are returned. The range defaults to the last 30 days and may span at most 366 days.")
    @ApiResponse(responseCode = "200", description = "Click analytics.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalyticsResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "INVALID_RANGE (from not before to, or range too long) or INVALID_PARAMETER "
                    + "(unparsable instant or unknown granularity).",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No link with this code (LINK_NOT_FOUND).",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ApiError.class)))
    @CommonErrorResponses
    @GetMapping("/{code}/analytics")
    public AnalyticsResponse analytics(
            @Parameter(description = "Short code of the link.", example = "aB3dE9x") @PathVariable String code,
            @Parameter(description = "Inclusive start, ISO-8601 instant. Default: 30 days before `to`.",
                    example = "2026-10-01T00:00:00Z") @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive end, ISO-8601 instant. Default: now.",
                    example = "2026-10-08T00:00:00Z") @RequestParam(required = false) Instant to,
            @Parameter(description = "Bucket size (case-insensitive). Default: DAY.")
            @RequestParam(required = false) Granularity granularity) {
        return clickService.getAnalytics(code, from, to, granularity);
    }
}
