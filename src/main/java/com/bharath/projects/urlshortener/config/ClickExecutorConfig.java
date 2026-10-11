package com.bharath.projects.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded in-process executor for asynchronous click recording (requirement D8).
 *
 * <ul>
 *   <li>Fixed queue capacity, so memory use is capped.</li>
 *   <li>Default AbortPolicy: when the queue is full, {@code execute} throws and the caller logs and
 *       drops the click. {@code CallerRunsPolicy} is deliberately NOT used because it would run the
 *       database write on the request thread.</li>
 *   <li>Graceful shutdown waits for queued clicks, up to the configured timeout.</li>
 * </ul>
 */
@Configuration
public class ClickExecutorConfig {

    public static final String CLICK_EXECUTOR = "clickExecutor";

    /**
     * {@code @DependsOn} makes Spring destroy this executor (draining the queue) BEFORE the JPA
     * infrastructure and connection pool it needs to finish writing.
     */
    @Bean(name = CLICK_EXECUTOR)
    @DependsOn("entityManagerFactory")
    public ThreadPoolTaskExecutor clickExecutor(AppProperties props) {
        return buildClickExecutor(props.clickRecording());
    }

    static ThreadPoolTaskExecutor buildClickExecutor(AppProperties.ClickRecording cfg) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("click-");
        executor.setCorePoolSize(cfg.corePoolSize());
        executor.setMaxPoolSize(cfg.maxPoolSize());
        executor.setQueueCapacity(cfg.queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(cfg.shutdownTimeoutSeconds());
        return executor;
    }
}
