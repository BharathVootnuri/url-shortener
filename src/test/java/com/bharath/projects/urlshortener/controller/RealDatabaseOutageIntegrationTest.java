package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.service.LinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC5.1 / AC5.2 with a genuinely unreachable database (no repository mocks): the real DataSource is wrapped so
 * that no connection can be obtained, failing the way a dead PostgreSQL does (a connection exception from the
 * driver). The real JPA, transaction, health-indicator and exception-handler stack then reacts. The health
 * endpoint must report DOWN, the API must answer 503 with Retry-After and no internals, and everything must
 * recover by itself when the database is back, without a restart.
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class RealDatabaseOutageIntegrationTest {

    /** Wraps the real DataSource; while {@code down} every connection attempt fails. */
    static class SwitchableDataSource extends DelegatingDataSource {
        volatile boolean down;

        SwitchableDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            failIfDown();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            failIfDown();
            return super.getConnection(username, password);
        }

        private void failIfDown() throws SQLException {
            if (down) {
                throw new SQLTransientConnectionException(
                        "Connection to jdbc:postgresql://secret-host:5432/db refused (password=hunter2)", "08001");
            }
        }
    }

    @TestConfiguration
    static class OutageConfig {
        @Bean
        static BeanPostProcessor switchableDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    return bean instanceof DataSource ds && !(bean instanceof SwitchableDataSource)
                            ? new SwitchableDataSource(ds)
                            : bean;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    LinkService linkService;
    @Autowired
    DataSource dataSource;

    @AfterEach
    void databaseBackUp() {
        ((SwitchableDataSource) dataSource).down = false;
    }

    @Test
    void healthReportsDownApiAnswers503AndEverythingRecoversWhenTheDatabaseIsBack() throws Exception {
        assertThat(dataSource).isInstanceOf(SwitchableDataSource.class);
        SwitchableDataSource db = (SwitchableDataSource) dataSource;

        String code = linkService.create(new CreateLinkRequest("https://example.com/outage", null)).code();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        db.down = true;

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));

        mvc.perform(get("/api/v1/short-urls/" + code))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("secret-host"))));

        mvc.perform(get("/" + code).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        mvc.perform(post("/api/v1/short-urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/during-outage\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        db.down = false;

        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/short-urls/" + code)).andExpect(status().isOk());
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        assertThat(linkService.getDetails(code).originalUrl()).isEqualTo("https://example.com/outage");
    }
}
