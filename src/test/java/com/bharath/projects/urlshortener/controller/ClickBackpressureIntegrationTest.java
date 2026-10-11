package com.bharath.projects.urlshortener.controller;

import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.entity.ClickEvent;
import com.bharath.projects.urlshortener.service.LinkService;
import com.bharath.projects.urlshortener.service.impl.ClickWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC4.7: when click recording cannot keep up (queue full), redirects must stay fast and successful and the
 * overflow must be dropped, not turned into errors or slowdowns for users.
 *
 * <p>The executor is shrunk to 1 worker + a queue of 2 and the writer is blocked, so exactly 3 clicks can be
 * accepted (1 running, 2 queued) and every further click must be dropped.
 */
@SpringBootTest(properties = {
        "app.click-recording.core-pool-size=1",
        "app.click-recording.max-pool-size=1",
        "app.click-recording.queue-capacity=2",
        "app.rate-limit.create-per-minute=100000",
        "app.rate-limit.miss-per-minute=100000"})
@AutoConfigureMockMvc
class ClickBackpressureIntegrationTest {

    private static final int REQUESTS = 20;
    private static final int ACCEPTED = 3;

    @Autowired
    MockMvc mvc;
    @Autowired
    LinkService linkService;
    @MockitoBean
    ClickWriter writer;

    @Test
    void redirectsStayFastAndSuccessfulWhileTheRecordingQueueOverflows() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(writer).write(any(ClickEvent.class));

        String code = linkService.create(new CreateLinkRequest("https://example.com/slow-writes", null)).code();

        try {
            long start = System.nanoTime();
            for (int i = 0; i < REQUESTS; i++) {
                mvc.perform(get("/" + code))
                        .andExpect(status().isFound())
                        .andExpect(header().string("Location", "https://example.com/slow-writes"));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            // The writer is completely stuck, yet no redirect waited for it.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        } finally {
            release.countDown();
        }

        // Once the writer recovers, exactly the accepted clicks are written.
        verify(writer, timeout(5_000).times(ACCEPTED)).write(any(ClickEvent.class));
        verify(writer, times(ACCEPTED)).write(any(ClickEvent.class));
    }
}
