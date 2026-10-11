package com.bharath.projects.urlshortener.filter;

import com.bharath.projects.urlshortener.controller.LinkController;
import com.bharath.projects.urlshortener.controller.RedirectController;
import com.bharath.projects.urlshortener.exception.RateLimitExceededException;
import com.bharath.projects.urlshortener.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RateLimiter} to the link controllers.
 *
 * <p>This is an interceptor rather than a servlet filter so that CORS headers are already on the
 * response when a 429 is produced; a browser app can then read the error instead of seeing an opaque
 * network failure. Only our own controllers are limited (not actuator, the error page, or CORS preflight).
 * The client key is {@code getRemoteAddr()}; behind a trusted reverse proxy configure
 * {@code server.forward-headers-strategy} so that it reflects the real client address.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimiter.Decision decision = decide(method.getBeanType(), request, request.getRemoteAddr());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        return true;
    }

    private RateLimiter.Decision decide(Class<?> controller, HttpServletRequest request, String client) {
        if (controller == LinkController.class && "POST".equals(request.getMethod())) {
            return rateLimiter.tryAcquireCreate(client);
        }
        if (controller == LinkController.class && request.getRequestURI().endsWith("/analytics")) {
            // The one expensive read: scans the link's click rows for the requested range.
            RateLimiter.Decision analytics = rateLimiter.tryAcquireAnalytics(client);
            return analytics.allowed() ? rateLimiter.checkMiss(client) : analytics;
        }
        if (controller == LinkController.class || controller == RedirectController.class) {
            // Lookups: refused only once the client has racked up too many unknown-code misses.
            return rateLimiter.checkMiss(client);
        }
        return new RateLimiter.Decision(true, 0);
    }
}
