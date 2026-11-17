package com.ccr4ft3r.lightspeed.startup.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes ModernFix's {@code BlockStateCacheHandler.rebuildParallel} cache invalidation sweep
 * in parallel across worker threads while preserving the caller's class monitor.
 */
public final class BlockStateCacheParallelSupport {
    @FunctionalInterface
    public interface RangeConsumer {
        void accept(List<?> list, int start, int end);
    }

    private static final int CHUNK_SIZE = 16_384;
    private static final int MIN_PARALLEL_SIZE = 8_192;

    private BlockStateCacheParallelSupport() {
    }

    public static void clearBlockStateCaches(Object registry, RangeConsumer clearer) {
        if (registry instanceof IdMapperListAccess access) {
            List<?> list = access.lightspeed$getIdToT();
            int size = list.size();
            if (size <= 0) {
                return;
            }
            int workers = Math.max(1, ForkJoinPool.commonPool().getParallelism());
            if (size < MIN_PARALLEL_SIZE || workers <= 1) {
                clearer.accept(list, 0, size);
                return;
            }
            AtomicInteger cursor = new AtomicInteger();
            int taskCount = Math.min(workers, (size + CHUNK_SIZE - 1) / CHUNK_SIZE);
            List<ForkJoinTask<?>> tasks = new ArrayList<>(Math.max(0, taskCount - 1));
            Throwable failure = null;
            try {
                for (int w = 0; w < taskCount - 1; w++) {
                    tasks.add(ForkJoinPool.commonPool().submit(() -> drainChunks(list, size, cursor, clearer)));
                }
                drainChunks(list, size, cursor, clearer);
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
            return;
        }
        if (registry instanceof Iterable<?> iterable) {
            @SuppressWarnings("unchecked")
            List<Object> snapshot = new ArrayList<>();
            for (Object o : iterable) {
                snapshot.add(o);
            }
            if (!snapshot.isEmpty()) {
                clearer.accept(snapshot, 0, snapshot.size());
            }
        }
    }

    private static void drainChunks(List<?> list, int size, AtomicInteger cursor, RangeConsumer clearer) {
        for (;;) {
            int start = cursor.getAndAdd(CHUNK_SIZE);
            if (start >= size) {
                return;
            }
            int end = Math.min(size, start + CHUNK_SIZE);
            clearer.accept(list, start, end);
        }
    }
}
