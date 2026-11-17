package com.ccr4ft3r.lightspeed.startup.registry;

import com.google.common.collect.BiMap;
import com.google.common.collect.ForwardingMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Parallelizes independent registry snapshot synchronization, validation, and baking inside
 * {@code GameData.freezeData()} while preserving strict {@code minecraft:block -> minecraft:item}
 * ordering and keeping {@code BiMap.forEach} call sites intact for ModernFix Mixin injections.
 */
public final class GameDataFreezeSupport {
    private static final ResourceLocation BLOCKS_KEY = ForgeRegistries.Keys.BLOCKS.location();
    private static final ResourceLocation ITEMS_KEY = ForgeRegistries.Keys.ITEMS.location();
    private static final MethodHandle LOAD_REGISTRY = resolveLoadRegistryHandle();

    private GameDataFreezeSupport() {
    }

    private static MethodHandle resolveLoadRegistryHandle() {
        try {
            Class<?> gameDataClass = Class.forName("net.minecraftforge.registries.GameData");
            return MethodHandles.publicLookup().findStatic(
                    gameDataClass,
                    "loadRegistry",
                    MethodType.methodType(void.class, ResourceLocation.class, RegistryManager.class,
                            RegistryManager.class, boolean.class)
            );
        } catch (Throwable t) {
            throw new IllegalStateException("Cannot resolve public GameData.loadRegistry bridge", t);
        }
    }

    public static void syncActiveToFrozen(BiMap<ResourceLocation, ForgeRegistry<?>> activeRegistries) {
        RegistryManager active = RegistryManager.ACTIVE;
        RegistryManager frozen = RegistryManager.FROZEN;

        List<ResourceLocation> independentKeys = new ArrayList<>(activeRegistries.size());
        boolean hasBlocks = false;
        boolean hasItems = false;
        for (ResourceLocation key : activeRegistries.keySet()) {
            if (BLOCKS_KEY.equals(key)) {
                hasBlocks = true;
            } else if (ITEMS_KEY.equals(key)) {
                hasItems = true;
            } else {
                independentKeys.add(key);
            }
        }

        // Pre-populate FROZEN registries on caller thread in dependency order because RegistryManager.registries
        // (HashBiMap) and persisted/synced/legacyNames sets are non-thread-safe during lazy creation.
        if (hasBlocks) {
            frozen.getRegistry(BLOCKS_KEY, active);
        }
        if (hasItems) {
            frozen.getRegistry(ITEMS_KEY, active);
        }
        for (int i = 0, n = independentKeys.size(); i < n; i++) {
            frozen.getRegistry(independentKeys.get(i), active);
        }

        // Phase 1: Sync ACTIVE -> FROZEN. minecraft:block creates FROZEN BLOCK_TO_ITEM before minecraft:item reads it;
        // every other registry owns disjoint FROZEN instances and callback state.
        List<Runnable> syncTasks = new ArrayList<>(independentKeys.size() + 2);
        if (hasBlocks && hasItems) {
            syncTasks.add(() -> {
                invokeLoadRegistry(BLOCKS_KEY, active, frozen);
                invokeLoadRegistry(ITEMS_KEY, active, frozen);
            });
        } else {
            if (hasBlocks) {
                syncTasks.add(() -> invokeLoadRegistry(BLOCKS_KEY, active, frozen));
            }
            if (hasItems) {
                syncTasks.add(() -> invokeLoadRegistry(ITEMS_KEY, active, frozen));
            }
        }
        for (int i = 0, n = independentKeys.size(); i < n; i++) {
            ResourceLocation key = independentKeys.get(i);
            syncTasks.add(() -> invokeLoadRegistry(key, active, frozen));
        }
        runParallel(syncTasks, false);
    }

    public static BiMap<ResourceLocation, ForgeRegistry<?>> parallelForEachView(
            BiMap<ResourceLocation, ForgeRegistry<?>> delegate
    ) {
        return delegate.size() <= 1 ? delegate : new ParallelForEachBiMap(delegate);
    }

    private static final class ParallelForEachBiMap
            extends ForwardingMap<ResourceLocation, ForgeRegistry<?>>
            implements BiMap<ResourceLocation, ForgeRegistry<?>> {
        private final BiMap<ResourceLocation, ForgeRegistry<?>> delegate;

        private ParallelForEachBiMap(BiMap<ResourceLocation, ForgeRegistry<?>> delegate) {
            this.delegate = delegate;
        }

        @Override
        protected BiMap<ResourceLocation, ForgeRegistry<?>> delegate() {
            return delegate;
        }

        @Override
        public ForgeRegistry<?> forcePut(ResourceLocation key, ForgeRegistry<?> value) {
            return delegate.forcePut(key, value);
        }

        @Override
        public BiMap<ForgeRegistry<?>, ResourceLocation> inverse() {
            return delegate.inverse();
        }

        @Override
        public Set<ForgeRegistry<?>> values() {
            return delegate.values();
        }

        @Override
        public void forEach(BiConsumer<? super ResourceLocation, ? super ForgeRegistry<?>> action) {
            if (delegate.size() <= 1) {
                delegate.forEach(action);
                return;
            }
            // Pin minecraft:block onto the caller thread (preserves any thread-local BakeReason / context),
            // while dispatching the remaining independent registries to pool workers concurrently.
            Runnable callerTask = null;
            List<Runnable> workerTasks = new ArrayList<>(delegate.size());
            for (Map.Entry<ResourceLocation, ForgeRegistry<?>> entry : delegate.entrySet()) {
                ResourceLocation name = entry.getKey();
                ForgeRegistry<?> registry = entry.getValue();
                Runnable task = () -> action.accept(name, registry);
                if (BLOCKS_KEY.equals(name)) {
                    callerTask = task;
                } else {
                    workerTasks.add(task);
                }
            }
            if (callerTask == null) {
                runParallel(workerTasks, false);
                return;
            }
            runParallelWithCallerTask(callerTask, workerTasks);
        }
    }

    private static void invokeLoadRegistry(
            ResourceLocation registryName,
            RegistryManager from,
            RegistryManager to
    ) {
        try {
            LOAD_REGISTRY.invokeExact(registryName, from, to, true);
        } catch (Throwable t) {
            rethrow(t);
        }
    }

    private static void runParallelWithCallerTask(Runnable callerTask, List<Runnable> workerTasks) {
        if (workerTasks.isEmpty()) {
            callerTask.run();
            return;
        }
        int count = workerTasks.size();
        int workers = Math.max(1, Math.min(count, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
        AtomicInteger cursor = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(workers);
        Runnable drain = () -> {
            for (;;) {
                if (failure.get() != null) {
                    return;
                }
                int idx = cursor.getAndIncrement();
                if (idx >= count) {
                    return;
                }
                try {
                    workerTasks.get(idx).run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    return;
                }
            }
        };
        for (int w = 0; w < workers; w++) {
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    drain.run();
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            callerTask.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
        drain.run();
        awaitWorkers(done, failure, "Interrupted while running parallel registry freeze stage");
        Throwable err = failure.get();
        if (err != null) {
            rethrow(err);
        }
    }

    private static void runParallel(List<Runnable> tasks, boolean ignoreCallerFirst) {
        int count = tasks.size();
        if (count == 0) {
            return;
        }
        if (count == 1) {
            tasks.get(0).run();
            return;
        }
        int workers = Math.max(1, Math.min(count, Runtime.getRuntime().availableProcessors()));
        if (workers <= 1) {
            for (int i = 0; i < count; i++) {
                tasks.get(i).run();
            }
            return;
        }

        AtomicInteger cursor = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(workers - 1);
        Runnable drain = () -> {
            for (;;) {
                if (failure.get() != null) {
                    return;
                }
                int idx = cursor.getAndIncrement();
                if (idx >= count) {
                    return;
                }
                try {
                    tasks.get(idx).run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    return;
                }
            }
        };

        for (int w = 1; w < workers; w++) {
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    drain.run();
                } finally {
                    done.countDown();
                }
            });
        }
        drain.run();
        awaitWorkers(done, failure, "Interrupted while freezing registries");
        Throwable err = failure.get();
        if (err != null) {
            rethrow(err);
        }
    }

    private static void awaitWorkers(CountDownLatch done, AtomicReference<Throwable> failure, String message) {
        InterruptedException interrupted = null;
        for (;;) {
            try {
                done.await();
                break;
            } catch (InterruptedException exception) {
                if (interrupted == null) {
                    interrupted = exception;
                    // Stop workers from claiming another registry, but join work already in progress.
                    failure.compareAndSet(null, exception);
                }
            }
        }
        if (interrupted != null) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, interrupted);
        }
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException rt) {
            throw rt;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new RuntimeException(t);
    }
}
