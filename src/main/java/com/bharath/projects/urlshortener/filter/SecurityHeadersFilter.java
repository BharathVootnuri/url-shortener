package com.bharath.projects.urlshortener.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Baseline security headers for everything this backend serves (JSON and the small HTML error pages).
 *
 * <p>No {@code Referrer-Policy} is set on purpose: it would also apply to the 302 redirects and strip the
 * referrer from the destination site's point of view. The H2 console and Swagger UI (local profile only)
 * need frames, scripts and inline styles, so they are exempt from the framing and CSP headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!isDevTooling(path)) {
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", CSP);
        }
        if (path.startsWith("/api/")) {
            // API data (details, analytics) must never be served stale from a cache.
            response.setHeader("Cache-Control", "no-store");
        }
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        chain.doFilter(request, response);
    }

    /** Local-only developer pages (H2 console, Swagger UI) that need scripts/styles/frames. Disabled in prod. */
    private static boolean isDevTooling(String path) {
        return path.startsWith("/h2-console") || path.startsWith("/swagger-ui");
    }
}
