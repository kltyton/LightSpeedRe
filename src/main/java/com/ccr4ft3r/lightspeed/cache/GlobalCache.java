package com.ccr4ft3r.lightspeed.cache;

import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.ccr4ft3r.lightspeed.util.CacheUtil;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ccr4ft3r.lightspeed.util.CacheUtil.*;

public class GlobalCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicBoolean PERSISTED_CACHE_LOAD_STARTED = new AtomicBoolean(false);
    private static volatile CompletableFuture<Void> persistedCacheLoad = CompletableFuture.completedFuture(null);

    public static volatile boolean isEnabled = true;
    public static volatile boolean shouldCacheWalkedPaths = true;
    public static volatile boolean shouldCacheEmptyNamespaces = true;
    public static volatile boolean shouldCacheResourceExistence = true;
    public static volatile boolean shouldCacheMaterials = true;
    public static volatile boolean shouldAsyncPreloadPacks = true;
    public static volatile boolean shouldParallelizeResourcePackLookup = true;
    public static volatile int parallelLookupMinPacks = 4;
    public static volatile boolean shouldIsolateModdedResourceReloadFailures = true;
    public static volatile List<String> isolatedResourceReloadListenerPatterns = List.of("*");
    public static final Map<CharSequence, List<String>> SPLITTED_STRINGS_BY_SEQUENCE = Maps.newConcurrentMap();
    public static final Map<String, String> CANONICAL_PATH_PER_FILE = Maps.newConcurrentMap();
    private static final Set<ICache> CACHES = Sets.newConcurrentHashSet();
    public static final Map<String, Map<String, Boolean>> PERSISTED_EXISTENCES_BY_MOD = Maps.newConcurrentMap();
    public static final Map<String, Map<PackType, Set<String>>> PERSISTED_NAMESPACES_BY_MOD = Maps.newConcurrentMap();
    public static final Map<String, Map<PackType, Map<String, List<String>>>> PERSISTED_RESOURCE_LISTS_BY_MOD = Maps.newConcurrentMap();
    public static final int WORKER_COUNT = getWorkerCount();
    private static final AtomicInteger CACHE_THREAD_ID = new AtomicInteger();
    private static final Set<CompletableFuture<?>> BACKGROUND_CACHE_TASKS = Sets.newConcurrentHashSet();
    private static final int CACHE_WORKER_COUNT = getCacheWorkerCount();
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "Lightspeed-", THREAD_ID, Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)));
    public static final ExecutorService CACHE_EXECUTOR = Executors.newFixedThreadPool(CACHE_WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "Lightspeed-Cache-", CACHE_THREAD_ID, Thread.MIN_PRIORITY));

    public static void add(ICache cache) {
        CACHES.add(cache);
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

    public static <K, V> CompletableFuture<Void> loadPersistedCacheAsync(File dir, String id, Map<K, V> targetMap) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        File file = new File(dir, id + ".ser");
        if (!file.isFile()) {
            return CompletableFuture.completedFuture(null);
        }
        return executeCacheLogged("load cache file " + file.getName(), () -> targetMap.putAll(CacheUtil.load(file)));
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

    public static CompletableFuture<Void> executeLogged(String taskName, Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Lightspeed task failed: {}", taskName, e);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> executeCacheLogged(String taskName, Runnable task) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                if (isEnabled) {
                    task.run();
                }
            } catch (Exception e) {
                LOGGER.error("Lightspeed cache task failed: {}", taskName, e);
            }
        }, CACHE_EXECUTOR);
        BACKGROUND_CACHE_TASKS.add(future);
        future.whenComplete((ignored, throwable) -> BACKGROUND_CACHE_TASKS.remove(future));
        return future;
    }

    public static IoSupplier<InputStream> findFirstResource(List<PackResources> packs, PackType type, ResourceLocation location) {
        if (packs.isEmpty()) {
            return null;
        }
        if (packs.size() < parallelLookupMinPacks || !shouldParallelizeResourcePackLookup || packs.stream().anyMatch(pack -> !isSafeForParallelLookup(pack))) {
            return findFirstResourceSequential(packs, type, location);
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(packs.size());
        try {
            for (PackResources pack : packs) {
                futures.add(CompletableFuture.supplyAsync(() -> pack.getResource(type, location), EXECUTOR));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Lightspeed parallel resource lookup rejected; falling back to sequential lookup", e);
            return findFirstResourceSequential(packs, type, location);
        }

        for (CompletableFuture<IoSupplier<InputStream>> future : futures) {
            try {
                IoSupplier<InputStream> supplier = future.get();
                if (supplier != null) {
                    return supplier;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return findFirstResourceSequential(packs, type, location);
            } catch (ExecutionException e) {
                LOGGER.warn("Lightspeed parallel resource lookup failed for {}", location, e);
            }
        }
        return null;
    }

    public static void disablePersistAndClear() {
        isEnabled = false;
        awaitPersistedCachesLoaded();
        awaitBackgroundCacheTasks();

        List<CompletableFuture<Void>> deleteTasks = new ArrayList<>();
        CacheUtil.getCacheFiles(HAS_RESOURCE_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheUtil.getCacheFiles(NAMESPACE_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CacheUtil.getCacheFiles(RESOURCE_LIST_CACHE_DIR).forEach(file -> deleteTasks.add(deleteAsync(file)));
        CompletableFuture.allOf(deleteTasks.toArray(new CompletableFuture[0])).join();

        List<CompletableFuture<Void>> persistTasks = new ArrayList<>();
        CACHES.forEach(cache -> persistTasks.add(runPersistTask(cache)));
        CompletableFuture.allOf(persistTasks.toArray(new CompletableFuture[0])).join();
        SPLITTED_STRINGS_BY_SEQUENCE.clear();
        CANONICAL_PATH_PER_FILE.clear();
        CACHES.clear();
        PERSISTED_EXISTENCES_BY_MOD.clear();
        PERSISTED_NAMESPACES_BY_MOD.clear();
        PERSISTED_RESOURCE_LISTS_BY_MOD.clear();
    }

    private static int getWorkerCount() {
        int configured = Integer.getInteger("lightspeed.workers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 32));
    }

    private static int getCacheWorkerCount() {
        int configured = Integer.getInteger("lightspeed.cacheWorkers", 1);
        return Math.max(1, Math.min(configured, 4));
    }

    private static Thread newDaemonThread(Runnable runnable, String namePrefix, AtomicInteger id, int priority) {
        Thread thread = new Thread(runnable, namePrefix + id.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(priority);
        return thread;
    }

    private static <K, V> CompletableFuture<Void> loadPersistedCaches(File dir, Map<String, Map<K, V>> targetMap) {
        dir.mkdirs();
        CompletableFuture<?>[] futures = CacheUtil.getCacheFiles(dir)
                .map(file -> executeCacheLogged("load cache file " + file.getName(), () -> {
                    String id = org.apache.commons.io.FilenameUtils.getBaseName(file.getName());
                    targetMap.computeIfAbsent(id, ignored -> Maps.newConcurrentMap()).putAll(CacheUtil.load(file));
                }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Void> deleteAsync(File file) {
        return CompletableFuture.runAsync(() -> {
            if (!file.delete() && file.exists()) {
                LOGGER.warn("Lightspeed could not delete old cache file {}", file);
            }
        }, CACHE_EXECUTOR);
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

    private static void awaitBackgroundCacheTasks() {
        CompletableFuture<?>[] tasks = BACKGROUND_CACHE_TASKS.toArray(new CompletableFuture[0]);
        if (tasks.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(tasks).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Lightspeed cache task wait interrupted; persisting completed cache state", e);
        } catch (ExecutionException e) {
            LOGGER.warn("Lightspeed cache task failed before persist", e);
        } catch (TimeoutException e) {
            LOGGER.warn("Lightspeed cache tasks did not finish before title screen; persisting completed cache state");
        }
    }

    public static void shutdownExecutors() {
        EXECUTOR.shutdown();
        CACHE_EXECUTOR.shutdown();
    }

    private static IoSupplier<InputStream> findFirstResourceSequential(List<PackResources> packs, PackType type, ResourceLocation location) {
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
                && (packClass == PathPackResources.class || packClass.getName().startsWith("net.minecraftforge.resource.ResourcePackLoader$"));
        return (forgeModPathPack || packClass == FilePackResources.class) && !FusionPackCompat.hasOverrides(packResources);
    }
}
