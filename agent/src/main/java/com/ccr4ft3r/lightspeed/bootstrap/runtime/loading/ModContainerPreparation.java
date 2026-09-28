package com.ccr4ft3r.lightspeed.bootstrap.runtime.loading;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.Stream;

/** Prepares independent mod files on Forge workers, then publishes in the original order. */
public final class ModContainerPreparation {
    private static final Set<Long> WORKERS = ConcurrentHashMap.newKeySet();
    private static volatile int preparedFiles;
    private ModContainerPreparation() { }

    public static Stream<Object> build(Stream<?> files, Function<Object, Object> factory,
            Executor executor, Runnable ticker) {
        List<CompletableFuture<Object>> futures = files.map(file -> CompletableFuture.supplyAsync(() -> {
            WORKERS.add(Thread.currentThread().getId());
            return factory.apply(file);
        }, executor)).toList();
        CompletableFuture<Void> complete = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        while (!complete.isDone()) {
            ticker.run();
            LockSupport.parkNanos(1_000_000L);
        }
        try {
            complete.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
        preparedFiles += futures.size();
        return futures.stream().map(CompletableFuture::join);
    }

    public static int preparedFiles() { return preparedFiles; }
    public static int workerCount() { return WORKERS.size(); }
}
