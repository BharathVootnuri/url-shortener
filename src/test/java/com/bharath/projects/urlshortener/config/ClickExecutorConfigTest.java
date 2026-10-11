package com.bharath.projects.urlshortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickExecutorConfigTest {

    /** 1 thread + queue of 2 = 3 tasks in flight; the 4th must be rejected. */
    private ThreadPoolTaskExecutor smallExecutor() {
        ThreadPoolTaskExecutor executor = ClickExecutorConfig.buildClickExecutor(
                new AppProperties.ClickRecording(1, 1, 2, 5));
        executor.initialize();
        return executor;
    }

    @Test
    void rejectsWhenQueueIsFullInsteadOfBlockingOrRunningOnTheCallerThread() throws Exception {
        ThreadPoolTaskExecutor executor = smallExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        AtomicInteger ranOnCaller = new AtomicInteger();

        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> ranOnCaller.addAndGet(Thread.currentThread() == caller ? 1 : 0));
            executor.execute(() -> ranOnCaller.addAndGet(Thread.currentThread() == caller ? 1 : 0));

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> executor.execute(() -> ranOnCaller.incrementAndGet()))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)).isLessThan(1000);
        } finally {
            release.countDown();
            executor.shutdown();
        }

        assertThat(ranOnCaller.get()).isZero();
    }

    @Test
    void gracefulShutdownDrainsQueuedTasks() throws Exception {
        ThreadPoolTaskExecutor executor = smallExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        executor.execute(() -> {
            started.countDown();
            await(release);
            completed.incrementAndGet();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(completed::incrementAndGet);
        executor.execute(completed::incrementAndGet);

        release.countDown();
        executor.shutdown(); // waits for running + queued tasks

        assertThat(completed.get()).isEqualTo(3);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
