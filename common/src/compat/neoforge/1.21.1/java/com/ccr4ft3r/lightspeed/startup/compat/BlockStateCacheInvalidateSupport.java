package com.ccr4ft3r.lightspeed.startup.compat;

import net.minecraft.core.IdMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Parallelizes ModernFix 1.21.1 {@code BlockStateCacheHandler.invalidateCache()} across worker
 * threads using the mapper's actual iteration order while preserving the caller's
 * {@code BlockStateBase.class} monitor.
 */
public final class BlockStateCacheInvalidateSupport {
    private static final int CHUNK_SIZE = 16_384;
    private static final int MIN_PARALLEL_SIZE = 8_192;

    private BlockStateCacheInvalidateSupport() {
    }

    public static <T> void invalidateIdMapper(IdMapper<T> registry, Consumer<Object> clearer) {
        if (registry == null || clearer == null) {
            return;
        }
        int size = registry.size();
        if (size <= 0) {
            return;
        }
        int workers = Math.max(1, ForkJoinPool.commonPool().getParallelism());
        if (size < MIN_PARALLEL_SIZE || workers <= 1) {
            for (T entry : registry) {
                clearer.accept(entry);
            }
            return;
        }
        List<T> entries = new ArrayList<>(size);
        for (T entry : registry) {
            entries.add(entry);
        }
        int entryCount = entries.size();
        AtomicInteger cursor = new AtomicInteger();
        int taskCount = Math.min(workers, (entryCount + CHUNK_SIZE - 1) / CHUNK_SIZE);
        List<ForkJoinTask<?>> tasks = new ArrayList<>(Math.max(0, taskCount - 1));
        Throwable failure = null;
        try {
            for (int w = 0; w < taskCount - 1; w++) {
                tasks.add(ForkJoinPool.commonPool().submit(() -> drainChunks(entries, entryCount, cursor, clearer)));
            }
            drainChunks(entries, entryCount, cursor, clearer);
        } catch (Throwable error) {
            failure = error;
        }
        for (ForkJoinTask<?> task : tasks) {
            try {
                task.join();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException(failure);
    }

    private static <T> void drainChunks(
            List<T> entries,
            int size,
            AtomicInteger cursor,
            Consumer<Object> clearer
    ) {
        for (;;) {
            int start = cursor.getAndAdd(CHUNK_SIZE);
            if (start >= size) {
                return;
            }
            int end = Math.min(size, start + CHUNK_SIZE);
            for (int i = start; i < end; i++) {
                clearer.accept(entries.get(i));
            }
        }
    }
}
