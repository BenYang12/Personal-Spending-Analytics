package com.ledgerlens.backend.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// @EnableAsync switches on the machinery behind @Async: Spring wraps any bean
// with an @Async method in a proxy that hands the call to an Executor instead
// of running it on the caller's thread. Without this annotation, @Async is
// silently ignored and everything stays synchronous — a classic quiet failure.
@Configuration
@EnableAsync
public class AsyncConfig {

    // Boot would supply a default executor, but defining our own is the
    // professional move: unbounded thread creation is how services fall over.
    // The bean NAME matters — @Async("plaidSyncExecutor") selects it by name,
    // which keeps slow Plaid calls off any other pool we add later.
    @Bean("plaidSyncExecutor")
    public Executor plaidSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Threads kept alive even when idle. Sync work is IO-bound (waiting on
        // Plaid's HTTP responses), so a small pool goes a long way.
        executor.setCorePoolSize(2);

        // Hard ceiling: never more than 4 concurrent syncs.
        executor.setMaxPoolSize(4);

        // Requests wait here when all threads are busy. A BOUNDED queue is the
        // point: an unbounded one would swallow requests until the JVM runs out
        // of memory, turning a load spike into an outage.
        executor.setQueueCapacity(25);

        // When the queue is full too, run the task on the CALLING thread. That
        // makes the caller wait, which naturally slows down whoever is spamming
        // us — "backpressure" — instead of silently dropping work.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Thread names show up in every log line. "plaid-sync-1" in a stack
        // trace tells you instantly which pool the problem is in.
        executor.setThreadNamePrefix("plaid-sync-");

        // On shutdown, let in-flight syncs finish (up to 30s) rather than
        // killing them mid-transaction.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
