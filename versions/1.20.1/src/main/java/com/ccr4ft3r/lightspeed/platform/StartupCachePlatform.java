package com.ccr4ft3r.lightspeed.platform;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/** Forge shares its startup pool with the initial resource reload only. */
public final class StartupCachePlatform {
    public static final boolean ASYNC_PRELOAD_PACKS = true;
    public static final boolean SHARED_PERSISTENCE = false;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private StartupCachePlatform() {}
    public static int workerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        return Math.max(2, Math.min(configured > 0 ? configured : Runtime.getRuntime().availableProcessors() - 2, 32));
    }
    public static int cacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 0);
        return Math.max(1, Math.min(configured > 0 ? configured : Runtime.getRuntime().availableProcessors() / 8, 2));
    }
    public static ExecutorService startupExecutor(int workers) {
        return new ForkJoinPool(workers, pool -> {
            ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {};
            thread.setContextClassLoader(GlobalCache.class.getClassLoader());
            thread.setName("Lightspeed-Startup-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }, (thread, failure) -> LogUtils.getLogger().error("Lightspeed startup worker failed: {}", thread.getName(), failure), true);
    }
    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return GlobalCache.isEnabled && GlobalCache.shouldUseDedicatedResourceReloadExecutor ? GlobalCache.EXECUTOR : fallback;
    }
}
