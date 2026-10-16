package com.ccr4ft3r.lightspeed.platform;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/** NeoForge retains a separate reload pool after startup cache persistence. */
public final class StartupCachePlatform {
    // Initial pack preloading would dispatch Common setup twice in NeoForge.
    public static final boolean ASYNC_PRELOAD_PACKS = false;
    public static final boolean SHARED_PERSISTENCE = true;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicInteger RELOAD_THREAD_ID = new AtomicInteger();
    private static final ExecutorService RELOAD_EXECUTOR = new ForkJoinPool(reloadWorkerCount(), pool -> {
        ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {};
        thread.setContextClassLoader(GlobalCache.class.getClassLoader());
        thread.setName("Lightspeed-Reload-" + RELOAD_THREAD_ID.incrementAndGet());
        return thread;
    }, (thread, failure) -> LogUtils.getLogger().error("Lightspeed resource reload worker failed: {}", thread.getName(), failure), true);
    private StartupCachePlatform() {}
    public static int workerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        return configured > 0 ? configured : Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 32));
    }
    public static int cacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 0);
        return configured > 0 ? Math.max(1, Math.min(configured, 8)) : Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() / 4, 8));
    }
    private static int reloadWorkerCount() {
        int configured = Integer.getInteger("lightspeed.reloadWorkers", 0);
        return Math.max(2, Math.min(configured > 0 ? configured : Runtime.getRuntime().availableProcessors() - 2, 32));
    }
    public static ExecutorService startupExecutor(int workers) {
        return Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "Lightspeed-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });
    }
    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return GlobalCache.shouldUseDedicatedResourceReloadExecutor ? RELOAD_EXECUTOR : fallback;
    }
}
