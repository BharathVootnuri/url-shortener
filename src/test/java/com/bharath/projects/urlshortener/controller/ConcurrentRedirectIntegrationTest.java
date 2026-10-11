package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.service.LinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC4.6 over HTTP: many simultaneous redirects to the same link all succeed, and not a single click is lost
 * or double-counted (the counter increment is atomic and the event log agrees with it).
 */
@SpringBootTest(properties = {
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class ConcurrentRedirectIntegrationTest {

    private static final int REQUESTS = 60;
    private static final int THREADS = 12;

    @Autowired
    MockMvc mvc;
    @Autowired
    LinkService linkService;

    @Test
    void concurrentRedirectsAllSucceedAndEveryClickIsCountedExactlyOnce() throws Exception {
        String code = linkService.create(new CreateLinkRequest("https://example.com/busy", null)).code();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch startGun = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                Callable<Integer> request = () -> {
                    startGun.await();
                    return mvc.perform(get("/" + code)).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(request));
            }
            startGun.countDown();

            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(302);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(linkService.getDetails(code).totalClicks()).isEqualTo(REQUESTS));
        // No late stragglers pushing the counter past the expected value.
        Thread.sleep(200);
        assertThat(linkService.getDetails(code).totalClicks()).isEqualTo(REQUESTS);
    }
}
