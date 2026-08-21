package com.ccr4ft3r.lightspeed.cache;

import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.PathPackResources;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class GlobalCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger STARTUP_THREAD_ID = new AtomicInteger();
    private static final AtomicInteger CACHE_THREAD_ID = new AtomicInteger();

    public static volatile boolean isEnabled = true;
    public static volatile boolean shouldCacheWalkedPaths = true;
    public static volatile boolean shouldCacheEmptyNamespaces = true;
    public static volatile boolean shouldCacheResourceExistence = true;
    public static volatile boolean shouldCacheMaterials = true;
    public static volatile boolean shouldAsyncPreloadPacks = true;
    public static volatile boolean shouldParallelizeResourcePackLookup = true;
    public static volatile boolean shouldUseDedicatedResourceReloadExecutor = true;
    public static volatile int parallelLookupMinPacks = 4;
    public static volatile boolean shouldIsolateModdedResourceReloadFailures = true;
    public static volatile boolean shouldUseConnectorCompatibilityMode = true;
    public static volatile List<String> isolatedResourceReloadListenerPatterns = List.of("*");

    public static final Map<CharSequence, List<String>> SPLITTED_STRINGS_BY_SEQUENCE = Maps.newConcurrentMap();
    public static final Map<String, String> CANONICAL_PATH_PER_FILE = Maps.newConcurrentMap();
    public static final Map<String, Map<String, Boolean>> PERSISTED_EXISTENCES_BY_MOD = Maps.newConcurrentMap();
    public static final int WORKER_COUNT = getWorkerCount();

    private static final Set<ICache> CACHES = Sets.newConcurrentHashSet();
    private static final Set<CompletableFuture<?>> BACKGROUND_CACHE_TASKS = Sets.newConcurrentHashSet();
    private static final int CACHE_WORKER_COUNT = getCacheWorkerCount();
    private static final ForkJoinPool STARTUP_EXECUTOR = new ForkJoinPool(
            WORKER_COUNT,
            GlobalCache::newStartupWorker,
            (thread, throwable) -> LOGGER.error("Lightspeed startup worker failed: {}", thread.getName(), throwable),
            true);

    public static final ExecutorService EXECUTOR = STARTUP_EXECUTOR;
    public static final ExecutorService CACHE_EXECUTOR = Executors.newFixedThreadPool(CACHE_WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "Lightspeed-Cache-", CACHE_THREAD_ID, Thread.MIN_PRIORITY));

    private GlobalCache() {
    }

    public static void add(ICache cache) {
        CACHES.add(cache);
    }

    public static <K, V> CompletableFuture<Void> loadPersistedCacheAsync(File directory, String id,
                                                                         Map<K, V> targetMap) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        File file = new File(directory, id + ".ser");
        if (!file.isFile()) {
            return CompletableFuture.completedFuture(null);
        }
        return executeCacheLogged("load cache file " + file.getName(), () ->
                CacheFiles.<K, V>load(file).forEach(targetMap::putIfAbsent));
    }

    public static CompletableFuture<Void> executeLogged(String taskName, Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception exception) {
                LOGGER.error("Lightspeed task failed: {}", taskName, exception);
            }
        }, STARTUP_EXECUTOR);
    }

    public static CompletableFuture<Void> executeCacheLogged(String taskName, Runnable task) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                if (isEnabled) {
                    task.run();
                }
            } catch (Exception exception) {
                LOGGER.error("Lightspeed cache task failed: {}", taskName, exception);
            }
        }, CACHE_EXECUTOR);
        return trackBackgroundTask(future);
    }

    public static <T> CompletableFuture<T> supplyStartupAfter(CompletableFuture<?> prerequisite,
                                                               String taskName, Supplier<T> task) {
        CompletableFuture<T> future = prerequisite.thenApplyAsync(ignored -> {
            try {
                return isEnabled ? task.get() : null;
            } catch (Exception exception) {
                LOGGER.error("Lightspeed cache task failed: {}", taskName, exception);
                return null;
            }
        }, STARTUP_EXECUTOR);
        return trackBackgroundTask(future);
    }

    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return isEnabled && shouldUseDedicatedResourceReloadExecutor ? STARTUP_EXECUTOR : fallback;
    }

    public static boolean isStartupWorkerThread() {
        return ForkJoinTask.getPool() == STARTUP_EXECUTOR;
    }

    public static IoSupplier<InputStream> findFirstResource(List<PackResources> packs, PackType type,
                                                             ResourceLocation location) {
        if (packs.isEmpty()) {
            return null;
        }
        if (packs.size() < parallelLookupMinPacks
                || !shouldParallelizeResourcePackLookup
                || isStartupWorkerThread()
                || packs.stream().anyMatch(pack -> !isSafeForParallelLookup(pack))) {
            return findFirstResourceSequential(packs, type, location);
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(packs.size());
        try {
            for (PackResources pack : packs) {
                futures.add(CompletableFuture.supplyAsync(() -> pack.getResource(type, location), STARTUP_EXECUTOR));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Lightspeed parallel resource lookup rejected; falling back to sequential lookup", exception);
            return findFirstResourceSequential(packs, type, location);
        }

        for (CompletableFuture<IoSupplier<InputStream>> future : futures) {
            try {
                IoSupplier<InputStream> supplier = future.get();
                if (supplier != null) {
                    return supplier;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return findFirstResourceSequential(packs, type, location);
            } catch (ExecutionException exception) {
                LOGGER.warn("Lightspeed parallel resource lookup failed for {}", location, exception);
            }
        }
        return null;
    }

    public static void disablePersistAndClear() {
        isEnabled = false;
        awaitBackgroundCacheTasks();

        List<CompletableFuture<Void>> deleteTasks = new ArrayList<>();
        CacheFiles.getCacheFiles(CacheFiles.HAS_RESOURCE_CACHE_DIR)
                .forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheFiles.getCacheFiles(CacheFiles.NAMESPACE_CACHE_DIR)
                .forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheFiles.getCacheFiles(CacheFiles.RESOURCE_LIST_CACHE_DIR)
                .forEach(file -> deleteTasks.add(deleteAsync(file)));
        CompletableFuture.allOf(deleteTasks.toArray(new CompletableFuture[0])).join();

        for (ICache cache : CACHES) {
            try {
                cache.lightspeed$persistAndClearCache();
            } catch (Exception exception) {
                LOGGER.error("Lightspeed cache persist failed: {}", cache.getClass().getName(), exception);
            }
        }

        SPLITTED_STRINGS_BY_SEQUENCE.clear();
        CANONICAL_PATH_PER_FILE.clear();
        CACHES.clear();
        PERSISTED_EXISTENCES_BY_MOD.clear();
    }

    public static void shutdownExecutors() {
        STARTUP_EXECUTOR.shutdown();
        CACHE_EXECUTOR.shutdown();
    }

    public static void beginShutdown() {
        isEnabled = false;
    }

    private static <T> CompletableFuture<T> trackBackgroundTask(CompletableFuture<T> future) {
        BACKGROUND_CACHE_TASKS.add(future);
        future.whenComplete((ignored, throwable) -> BACKGROUND_CACHE_TASKS.remove(future));
        return future;
    }

    private static int getWorkerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        if (configured > 0) {
            return Math.max(2, Math.min(configured, 32));
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 2, 32));
    }

    private static int getCacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 0);
        if (configured > 0) {
            return Math.max(1, Math.min(configured, 2));
        }
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() / 8, 2));
    }

    private static ForkJoinWorkerThread newStartupWorker(ForkJoinPool pool) {
        ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {
        };
        thread.setContextClassLoader(GlobalCache.class.getClassLoader());
        thread.setName("Lightspeed-Startup-" + STARTUP_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    }

    private static Thread newDaemonThread(Runnable runnable, String namePrefix, AtomicInteger id, int priority) {
        Thread thread = new Thread(runnable, namePrefix + id.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(priority);
        return thread;
    }

    private static CompletableFuture<Void> deleteAsync(File file) {
        return CompletableFuture.runAsync(() -> {
            if (!file.delete() && file.exists()) {
                LOGGER.warn("Lightspeed could not delete old cache file {}", file);
            }
        }, CACHE_EXECUTOR);
    }

    private static void awaitBackgroundCacheTasks() {
        while (true) {
            CompletableFuture<?>[] tasks = BACKGROUND_CACHE_TASKS.toArray(new CompletableFuture[0]);
            if (tasks.length == 0) {
                return;
            }
            try {
                CompletableFuture.allOf(tasks).join();
            } catch (CompletionException exception) {
                LOGGER.warn("Lightspeed cache task failed before persist", exception.getCause());
            }
        }
    }

    private static IoSupplier<InputStream> findFirstResourceSequential(List<PackResources> packs, PackType type,
                                                                        ResourceLocation location) {
        for (PackResources pack : packs) {
            IoSupplier<InputStream> supplier = pack.getResource(type, location);
            if (supplier != null) {
                return supplier;
            }
        }
        return null;
    }

    private static boolean isSafeForParallelLookup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        boolean forgeModPathPack = packResources instanceof PathPackResources
                && (packClass == PathPackResources.class
                || packClass.getName().startsWith("net.minecraftforge.resource.ResourcePackLoader$"));
        return (forgeModPathPack || packClass == FilePackResources.class)
                && !FusionPackCompat.hasOverrides(packResources);
    }
}
