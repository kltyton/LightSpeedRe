package com.ccr4ft3r.lightspeed.platform;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/** Shares bounded workers with the initial Fabric reload; later reloads use Minecraft's executor. */
public final class StartupCachePlatform {
    public static final boolean ASYNC_PRELOAD_PACKS = false;
    public static final boolean SHARED_PERSISTENCE = true;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private StartupCachePlatform() {
    }

    public static int workerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        return Math.max(2, Math.min(configured > 0 ? configured : Runtime.getRuntime().availableProcessors() - 2, 32));
    }

    public static int cacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 0);
        return Math.max(2, Math.min(configured > 0 ? configured : Runtime.getRuntime().availableProcessors() / 4, 8));
    }

    public static ExecutorService startupExecutor(int workers) {
        return new ForkJoinPool(workers, pool -> {
            ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {};
            thread.setContextClassLoader(GlobalCache.class.getClassLoader());
            thread.setName("Lightspeed-Startup-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, (thread, failure) -> LogUtils.getLogger().error("Lightspeed startup worker failed: {}",
                thread.getName(), failure), true);
    }

    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return GlobalCache.isEnabled && GlobalCache.shouldUseDedicatedResourceReloadExecutor
                ? GlobalCache.EXECUTOR : fallback;
    }

    public static Executor resourceReloadExecutor(Executor fallback) {
        return GlobalCache.isEnabled && GlobalCache.shouldUseDedicatedResourceReloadExecutor
                ? GlobalCache.EXECUTOR : fallback;
    }
}
