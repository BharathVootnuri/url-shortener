package com.bharath.projects.urlshortener.config;

import com.bharath.projects.urlshortener.dto.Granularity;
import com.bharath.projects.urlshortener.filter.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(AppProperties props, RateLimitInterceptor rateLimitInterceptor) {
        this.props = props;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * CORS only for the JSON API, and only for explicitly listed origins (never a wildcard; see
     * {@link AppProperties.Cors}). No origins configured means no cross-origin access at all.
     * The redirect endpoint is navigated to, not called from scripts, so it needs no CORS.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = props.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "X-Correlation-Id")
                .exposedHeaders("Retry-After", "X-Correlation-Id", "Location")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }

    /** Accept {@code granularity=day} as well as {@code DAY}. */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new Converter<String, Granularity>() {
            @Override
            public Granularity convert(String source) {
                return Granularity.valueOf(source.trim().toUpperCase(Locale.ROOT));
            }
        });
    }
}
