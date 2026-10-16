package com.ccr4ft3r.lightspeed.cache;

import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.ccr4ft3r.lightspeed.platform.StartupCachePlatform;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles.*;
import java.util.function.Supplier;
import java.util.function.Function;

public final class GlobalCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger CACHE_THREAD_ID = new AtomicInteger();

    public static volatile boolean isEnabled = true;
    public static volatile boolean shouldCacheWalkedPaths = true;
    public static volatile boolean shouldCacheEmptyNamespaces = true;
    public static volatile boolean shouldCacheResourceExistence = true;
    public static volatile boolean shouldVerifyJarHash = false;
    public static volatile boolean shouldCacheMaterials = true;
    public static volatile boolean shouldAsyncPreloadPacks = StartupCachePlatform.ASYNC_PRELOAD_PACKS;
    public static volatile boolean shouldParallelizeResourcePackLookup = true;
    public static volatile boolean shouldUseDedicatedResourceReloadExecutor = true;
    public static volatile boolean shouldIsolateModdedResourceReloadFailures = false;
    public static volatile List<String> isolatedResourceReloadListenerPatterns = List.of();

    public static final Map<CharSequence, List<String>> SPLITTED_STRINGS_BY_SEQUENCE = Maps.newConcurrentMap();
    public static final Map<String, String> CANONICAL_PATH_PER_FILE = Maps.newConcurrentMap();
    public static final Map<String, Map<String, Boolean>> PERSISTED_EXISTENCES_BY_MOD = Maps.newConcurrentMap();
    public static final int WORKER_COUNT = StartupCachePlatform.workerCount();
    public static final Map<String, Map<PackType, Set<String>>> PERSISTED_NAMESPACES_BY_MOD = Maps.newConcurrentMap();
    public static final Map<String, Map<PackType, Map<String, List<String>>>> PERSISTED_RESOURCE_LISTS_BY_MOD = Maps.newConcurrentMap();
    private static final AtomicBoolean PERSISTED_CACHE_LOAD_STARTED = new AtomicBoolean(false);
    private static volatile CompletableFuture<Void> persistedCacheLoad = CompletableFuture.completedFuture(null);

    private static final Set<ICache> CACHES = Sets.newConcurrentHashSet();
    private static final Set<CompletableFuture<?>> BACKGROUND_CACHE_TASKS = Sets.newConcurrentHashSet();
    private static final Set<CompletableFuture<?>> PERSISTENCE_TASKS = Sets.newConcurrentHashSet();
    private static volatile CompletableFuture<Void> startupPersistence;
    private static final int CACHE_WORKER_COUNT = StartupCachePlatform.cacheWorkerCount();
    public static final ExecutorService EXECUTOR = StartupCachePlatform.startupExecutor(WORKER_COUNT);
    public static final ExecutorService CACHE_EXECUTOR = Executors.newFixedThreadPool(CACHE_WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "Lightspeed-Cache-", CACHE_THREAD_ID, Thread.MIN_PRIORITY));

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(GlobalCache::awaitPersistenceAtExit,
                "Lightspeed-Cache-Persistence-Exit"));
    }

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
        File file = CacheFiles.cacheFile(directory, id);
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
        }, EXECUTOR);
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

    /** Writes next-launch cache data even after startup lookups have been disabled. */
    public static CompletableFuture<Void> persistCacheAsync(String taskName, Runnable task) {
        long queued = System.nanoTime();
        CompletableFuture<Void> future = CompletableFuture.runAsync(task, CACHE_EXECUTOR);
        PERSISTENCE_TASKS.add(future);
        future.whenComplete((ignored, failure) -> {
            PERSISTENCE_TASKS.remove(future);
            if (failure != null) {
                LOGGER.error("Lightspeed startup cache persistence failed: {}", taskName, failure);
            } else {
                LOGGER.info("Lightspeed startup cache persistence complete: name={} elapsedMs={}",
                        taskName, (System.nanoTime() - queued) / 1_000_000L);
            }
        });
        return trackBackgroundTask(future);
    }

    public static void finishStartupCaches() {
        beginShutdown();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        startupPersistence = completion;
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    disablePersistAndClear();
                } finally {
                    shutdownExecutors();
                }
            }, EXECUTOR).whenComplete((ignored, failure) -> {
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException exception) {
            completion.completeExceptionally(exception);
            throw exception;
        }
    }

    private static void awaitPersistenceAtExit() {
        // An interrupted startup must not wait for unfinished reload prerequisites or delete existing caches.
        CompletableFuture<Void> flush = startupPersistence;
        if (flush != null) {
            try {
                flush.join();
            } catch (CompletionException exception) {
                LOGGER.error("Lightspeed cache flush failed before JVM exit", exception.getCause());
            }
        }
        try {
            CompletableFuture.allOf(PERSISTENCE_TASKS.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException exception) {
            LOGGER.error("Lightspeed pending cache persistence failed before JVM exit", exception.getCause());
        }
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
        }, EXECUTOR);
        return trackBackgroundTask(future);
    }

    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return StartupCachePlatform.resourceReloadExecutor(fallback);
    }

    public static IoSupplier<InputStream> findFirstResource(List<PackResources> packs,
            Function<PackResources, IoSupplier<InputStream>> lookup) {
        for (PackResources pack : packs) {
            IoSupplier<InputStream> supplier = lookup.apply(pack);
            if (supplier != null) {
                return supplier;
            }
        }
        return null;
    }

    public static void disablePersistAndClear() {
        isEnabled = false;
        if (StartupCachePlatform.SHARED_PERSISTENCE) {
            awaitPersistedCachesLoaded();
        }
        awaitBackgroundCacheTasks();
        List<CompletableFuture<Void>> deleteTasks = new ArrayList<>();
        CacheFiles.getCacheFiles(HAS_RESOURCE_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheFiles.getCacheFiles(NAMESPACE_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheFiles.getCacheFiles(RESOURCE_LIST_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CompletableFuture.allOf(deleteTasks.toArray(new CompletableFuture[0])).join();
        if (StartupCachePlatform.SHARED_PERSISTENCE) {
            List<CompletableFuture<Void>> sharedTasks = new ArrayList<>();
            PERSISTED_EXISTENCES_BY_MOD.forEach((id, cache) -> sharedTasks.add(
                    CompletableFuture.runAsync(() -> CacheFiles.persist(cache, CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, id)), CACHE_EXECUTOR)));
            PERSISTED_NAMESPACES_BY_MOD.forEach((id, cache) -> sharedTasks.add(
                    CompletableFuture.runAsync(() -> CacheFiles.persist(cache, CacheFiles.cacheFile(NAMESPACE_CACHE_DIR, id)), CACHE_EXECUTOR)));
            PERSISTED_RESOURCE_LISTS_BY_MOD.forEach((id, cache) -> sharedTasks.add(
                    CompletableFuture.runAsync(() -> CacheFiles.persist(cache, CacheFiles.cacheFile(RESOURCE_LIST_CACHE_DIR, id)), CACHE_EXECUTOR)));
            CompletableFuture.allOf(sharedTasks.toArray(new CompletableFuture[0])).join();
            CompletableFuture.allOf(CACHES.stream().map(GlobalCache::runPersistTask).toArray(CompletableFuture[]::new)).join();
        } else {
            for (ICache cache : CACHES) {
                try {
                    cache.lightspeed$persistAndClearCache();
                } catch (Exception exception) {
                    LOGGER.error("Lightspeed cache persist failed: {}", cache.getClass().getName(), exception);
                }
            }
        }
        SPLITTED_STRINGS_BY_SEQUENCE.clear();
        CANONICAL_PATH_PER_FILE.clear();
        CACHES.clear();
        PERSISTED_EXISTENCES_BY_MOD.clear();
        PERSISTED_NAMESPACES_BY_MOD.clear();
        PERSISTED_RESOURCE_LISTS_BY_MOD.clear();
    }

    public static void shutdownExecutors() {
        EXECUTOR.shutdown();
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

    public static CompletableFuture<Void> loadPersistedCachesAsync() {
        if (PERSISTED_CACHE_LOAD_STARTED.compareAndSet(false, true)) {
            persistedCacheLoad = CompletableFuture.allOf(
                    loadPersistedCaches(NAMESPACE_CACHE_DIR, PERSISTED_NAMESPACES_BY_MOD),
                    loadPersistedCaches(RESOURCE_LIST_CACHE_DIR, PERSISTED_RESOURCE_LISTS_BY_MOD)
            ).exceptionally(throwable -> {
                LOGGER.error("Lightspeed failed to load persisted caches", throwable);
                return null;
            });
        }
        return persistedCacheLoad;
    }

    public static void awaitPersistedCachesLoaded() {
        try {
            loadPersistedCachesAsync().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Lightspeed cache loading interrupted; continuing without waiting for all cache files", e);
        } catch (ExecutionException e) {
            LOGGER.warn("Lightspeed cache loading failed; continuing with in-memory caches", e);
        } catch (TimeoutException e) {
            LOGGER.warn("Lightspeed cache loading timed out; continuing while remaining files load in the background", e);
        }
    }

    public static <T> CompletableFuture<T> supplyCacheAfterPersistedLoad(String taskName, Supplier<T> task) {
        CompletableFuture<T> future = loadPersistedCachesAsync().thenApplyAsync(ignored -> {
            try {
                return isEnabled ? task.get() : null;
            } catch (Exception e) {
                LOGGER.error("Lightspeed cache task failed: {}", taskName, e);
                return null;
            }
        }, CACHE_EXECUTOR);
        return trackBackgroundTask(future);
    }

    private static <K, V> CompletableFuture<Void> loadPersistedCaches(File dir, Map<String, Map<K, V>> targetMap) {
        dir.mkdirs();
        CompletableFuture<?>[] futures = CacheFiles.getCacheFiles(dir)
                .map(file -> executeCacheLogged("load cache file " + file.getName(), () -> {
                    String id = org.apache.commons.io.FilenameUtils.getBaseName(file.getName());
                    Map<K, V> target = targetMap.computeIfAbsent(id, ignored -> Maps.newConcurrentMap());
                    CacheFiles.<K, V>load(file).forEach(target::putIfAbsent);
                }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Void> runPersistTask(ICache cache) {
        return CompletableFuture.runAsync(() -> {
            try {
                cache.lightspeed$persistAndClearCache();
            } catch (Exception e) {
                LOGGER.error("Lightspeed cache persist failed: {}", cache.getClass().getName(), e);
            }
        }, CACHE_EXECUTOR);
    }
}
