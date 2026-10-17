package com.ccr4ft3r.lightspeed.platform;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns startup workers and the loader-specific resource reload lifetime. */
public final class StartupCachePlatform {
    public enum Profile { FORGE, FABRIC, NEOFORGE }

    private static final Profile PROFILE = LoaderEnvironment.startupProfile();
    public static final boolean ASYNC_PRELOAD_PACKS = PROFILE == Profile.FORGE;
    public static final boolean SHARED_PERSISTENCE = PROFILE != Profile.FORGE;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicInteger RELOAD_THREAD_ID = new AtomicInteger();

    private StartupCachePlatform() {}

    public static int workerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        int processors = Runtime.getRuntime().availableProcessors();
        if (PROFILE == Profile.NEOFORGE) {
            return configured > 0 ? configured : Math.max(2, Math.min(processors, 32));
        }
        return Math.max(2, Math.min(configured > 0 ? configured : processors - 2, 32));
    }

    public static int cacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 0);
        int processors = Runtime.getRuntime().availableProcessors();
        if (PROFILE == Profile.FORGE) {
            return Math.max(1, Math.min(configured > 0 ? configured : processors / 8, 2));
        }
        if (PROFILE == Profile.NEOFORGE && configured > 0) {
            return Math.max(1, Math.min(configured, 8));
        }
        return Math.max(2, Math.min(configured > 0 ? configured : processors / 4, 8));
    }

    public static ExecutorService startupExecutor(int workers) {
        if (PROFILE == Profile.NEOFORGE) {
            return Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "Lightspeed-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
        }
        return forkJoinPool(workers, "Startup", THREAD_ID, PROFILE == Profile.FORGE);
    }

    private static ExecutorService forkJoinPool(int workers, String stage, AtomicInteger id, boolean lowPriority) {
        return new ForkJoinPool(workers, pool -> {
            ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {};
            thread.setContextClassLoader(GlobalCache.class.getClassLoader());
            thread.setName("Lightspeed-" + stage + "-" + id.incrementAndGet());
            thread.setDaemon(true);
            if (lowPriority) {
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            }
            return thread;
        }, (thread, failure) -> LogUtils.getLogger().error("Lightspeed {} worker failed: {}",
                stage, thread.getName(), failure), true);
    }

    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return (ExecutorService) resourceReloadExecutor((Executor) fallback);
    }

    public static Executor resourceReloadExecutor(Executor fallback) {
        if (PROFILE == Profile.NEOFORGE) {
            return GlobalCache.shouldUseDedicatedResourceReloadExecutor ? ReloadWorkers.EXECUTOR : fallback;
        }
        return GlobalCache.isEnabled && GlobalCache.shouldUseDedicatedResourceReloadExecutor
                ? GlobalCache.EXECUTOR : fallback;
    }

    private static final class ReloadWorkers {
        private static final int CONFIGURED = Integer.getInteger("lightspeed.reloadWorkers", 0);
        private static final int WORKERS = Math.max(2, Math.min(CONFIGURED > 0 ? CONFIGURED
                : Runtime.getRuntime().availableProcessors() - 2, 32));
        private static final ExecutorService EXECUTOR = forkJoinPool(WORKERS, "Reload", RELOAD_THREAD_ID, false);
    }
}
