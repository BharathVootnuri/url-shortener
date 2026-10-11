package com.bharath.projects.urlshortener.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPropertiesTest {

    @Test
    void corsRejectsWildcardOrigins() {
        assertThatThrownBy(() -> new AppProperties.Cors(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppProperties.Cors(List.of("https://*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corsAcceptsExplicitOriginsAndTreatsNullAsEmpty() {
        assertThat(new AppProperties.Cors(List.of("http://localhost:4200")).allowedOrigins())
                .containsExactly("http://localhost:4200");
        assertThat(new AppProperties.Cors(null).allowedOrigins()).isEmpty();
    }

    @Test
    void clickRecordingRequiresMaxPoolAtLeastCorePool() {
        assertThat(new AppProperties.ClickRecording(2, 4, 10, 5).isMaxPoolSizeAtLeastCorePoolSize()).isTrue();
        assertThat(new AppProperties.ClickRecording(4, 4, 10, 5).isMaxPoolSizeAtLeastCorePoolSize()).isTrue();
        assertThat(new AppProperties.ClickRecording(5, 4, 10, 5).isMaxPoolSizeAtLeastCorePoolSize()).isFalse();
    }
}
