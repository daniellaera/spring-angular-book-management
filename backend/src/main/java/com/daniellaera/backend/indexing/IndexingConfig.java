package com.daniellaera.backend.indexing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Enables @Scheduled / @Async for the Qdrant indexing and provides its small dedicated executor. */
@Configuration
@EnableScheduling
@EnableAsync
public class IndexingConfig {

    public static final String EXECUTOR = "bookIndexingExecutor";

    /** One thread: on-change indexing is serialised and never competes with request threads. */
    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor bookIndexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("book-indexing-");
        // queue full (Ollama stuck for a long time): drop the task, the nightly run catches up
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }
}
