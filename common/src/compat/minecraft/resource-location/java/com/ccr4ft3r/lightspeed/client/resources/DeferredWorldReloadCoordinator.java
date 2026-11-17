package com.ccr4ft3r.lightspeed.client.resources;

import com.ccr4ft3r.lightspeed.compat.ResourceReloadFailureGuard;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Splits initial client resource reloading into menu-essential listeners that block TitleScreen
 * and verified world-only listeners deferred until immediately after TitleScreen is operable.
 */
public final class DeferredWorldReloadCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("lightspeed.deferWorldReload", "true"));

    private static final String TEXTURE_ATLAS_HOLDER = "net.minecraft.client.resources.TextureAtlasHolder";
    private static final String BEWLR = "net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer";
    private static final String GUI_SPRITE_MANAGER = "net.minecraft.client.gui.GuiSpriteManager";
    private static final Set<String> WORLD_ONLY_LISTENER_CLASSES = Set.of(
            "net.minecraft.client.resources.GrassColorReloadListener",
            "net.minecraft.client.resources.FoliageColorReloadListener",
            "net.minecraft.client.resources.model.ModelManager",
            "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher",
            BEWLR,
            "net.minecraft.client.renderer.entity.ItemRenderer",
            "net.minecraft.client.renderer.block.BlockRenderDispatcher",
            "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
            "net.minecraft.client.renderer.LevelRenderer",
            "net.minecraft.client.particle.ParticleEngine",
            "net.minecraft.client.resources.PaintingTextureManager",
            "net.minecraft.client.resources.MobEffectTextureManager",
            "net.minecraft.client.resources.MapDecorationTextureManager",
            "com.teamresourceful.resourcefullib.client.highlights.HighlightHandler",
            "software.bernie.geckolib.cache.GeckoLibCache",
            "guideme.internal.GuideReloadListener",
            "dev.ftb.mods.ftbchunks.ColorMapLoader",
            "com.thevortex.potionsmaster.client.DynamicLanguageProvider",
            "slimeknights.tconstruct.world.client.SlimeColorReloadListener",
            "slimeknights.mantle.data.datamap.BlockStateDataMapLoader",
            "vazkii.patchouli.client.book.BookContentResourceListenerLoader"
    );

    private static final AtomicBoolean INITIAL_SPLIT_CLAIMED = new AtomicBoolean(false);
    private static final AtomicBoolean IMMEDIATE_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean DEFERRED_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);
    private static final Object STATE_LOCK = new Object();
    private static final ThreadLocal<Boolean> IN_DEFERRED_TASK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_AWAIT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<PreparableReloadListener> ACTIVE_IMMEDIATE_LISTENER = new ThreadLocal<>();
    private static final Set<PreparableReloadListener> DEFERRED_FOLLOWUP_LISTENERS =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private static volatile PendingReloadSpec pendingSpec;
    private static volatile CompletableFuture<Void> activeCompletion;
    private static volatile CompletableFuture<Void> deferredSettlement = CompletableFuture.completedFuture(null);
    private static volatile CompletableFuture<?> immediateCompletion;
    private static volatile TaskTracker immediateTasks;
    private static volatile TaskTracker activeTasks;

    private record PendingReloadSpec(
            ResourceManager resources,
            List<PreparableReloadListener> deferredListeners,
            Executor backgroundExecutor,
            Executor gameExecutor,
            boolean logDebug
    ) {
    }

    private DeferredWorldReloadCoordinator() {
    }

    public static ReloadInstance createInitialOrNormalReload(
            PackType packType,
            ResourceManager resources,
            List<PreparableReloadListener> listeners,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CompletableFuture<Unit> waitingFor,
            boolean logDebug) {
        if (!ENABLED
                || packType != PackType.CLIENT_RESOURCES
                || listeners == null
                || listeners.isEmpty()
                || !INITIAL_SPLIT_CLAIMED.compareAndSet(false, true)) {
            return SimpleReloadInstance.create(
                    resources, listeners, backgroundExecutor, gameExecutor, waitingFor, logDebug);
        }

        List<PreparableReloadListener> immediate = new ArrayList<>(listeners.size());
        List<PreparableReloadListener> deferred = new ArrayList<>(16);
        for (PreparableReloadListener listener : listeners) {
            if (listener != null && isWorldOnlyListener(listener)) {
                deferred.add(listener);
            } else if (listener != null) {
                immediate.add(new TrackedImmediateListener(listener));
            } else {
                immediate.add(null);
            }
        }
        if (deferred.isEmpty()) {
            return SimpleReloadInstance.create(
                    resources, listeners, backgroundExecutor, gameExecutor, waitingFor, logDebug);
        }

        LOGGER.info(
                "Lightspeed deferred non-menu reload partitioned: immediate={} deferred={}",
                immediate.size(),
                deferred.size());
        immediateTasks = new TaskTracker();
        CompletableFuture<Void> settlement = new CompletableFuture<>();
        deferredSettlement = settlement;
        pendingSpec = new PendingReloadSpec(
                resources, List.copyOf(deferred), backgroundExecutor, gameExecutor, logDebug);

        ReloadInstance immediateReload = SimpleReloadInstance.create(
                resources, immediate, backgroundExecutor, gameExecutor, waitingFor, logDebug);
        immediateCompletion = immediateReload.done();
        immediateReload.done().whenComplete((ignored, error) -> {
            if (error == null) {
                IMMEDIATE_DONE.set(true);
                if (StartupMetrics.hasRecorded("title-screen-operable")) {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.execute(() -> {
                            if (client.getOverlay() == null) {
                                startIfPending();
                            }
                        });
                    }
                }
            } else {
                immediateTasks.whenIdle().thenRun(() -> settlement.completeExceptionally(error));
            }
        });
        return immediateReload;
    }

    public static boolean isTrackingImmediateListeners() {
        return pendingSpec != null && !IMMEDIATE_DONE.get() && !DEFERRED_STARTED.get();
    }

    public static Executor wrapImmediateExecutor(PreparableReloadListener listener, Executor delegate) {
        if (!isTrackingImmediateListeners() || listener == null) {
            return delegate;
        }
        TaskTracker tracker = immediateTasks;
        Executor tracked = tracker == null ? delegate : tracker.wrap(delegate);
        return command -> tracked.execute(() -> {
            PreparableReloadListener previous = ACTIVE_IMMEDIATE_LISTENER.get();
            ACTIVE_IMMEDIATE_LISTENER.set(listener);
            try {
                command.run();
            } finally {
                ACTIVE_IMMEDIATE_LISTENER.set(previous);
            }
        });
    }

    public static PreparableReloadListener enterImmediateListener(PreparableReloadListener listener) {
        PreparableReloadListener previous = ACTIVE_IMMEDIATE_LISTENER.get();
        if (isTrackingImmediateListeners() && listener != null) {
            ACTIVE_IMMEDIATE_LISTENER.set(listener);
        }
        return previous;
    }

    public static void exitImmediateListener(PreparableReloadListener previous) {
        ACTIVE_IMMEDIATE_LISTENER.set(previous);
    }

    public static PreparableReloadListener unwrapImmediateListener(PreparableReloadListener listener) {
        return listener instanceof TrackedImmediateListener tracked ? tracked.delegate() : listener;
    }

    /**
     * Defers ModelManager queries made before TitleScreen is operable, scheduling any active
     * immediate reload listeners to rerun right after deferred ModelManager loading completes.
     */
    public static boolean deferImmediateModelAccess() {
        if (pendingSpec == null || DEFERRED_STARTED.get() || StartupMetrics.hasRecorded("title-screen-operable")) {
            return false;
        }
        PreparableReloadListener listener = ACTIVE_IMMEDIATE_LISTENER.get();
        if (listener == null) {
            return false;
        }
        if (DEFERRED_FOLLOWUP_LISTENERS.add(listener)) {
            LOGGER.info(
                    "Lightspeed deferred model-dependent reload listener until after TitleScreen: {}",
                    listener.getName());
        }
        return true;
    }

    public static void startIfPending() {
        startIfPending(false);
    }

    public static CompletableFuture<Void> deferredWorkCompletion() {
        return deferredSettlement;
    }

    public static boolean shouldQueueReentrantReload(PackType packType) {
        return ENABLED && packType == PackType.CLIENT_RESOURCES
                && Boolean.TRUE.equals(IN_AWAIT.get()) && !deferredSettlement.isDone();
    }

    public static ReloadInstance queueReentrantReload(Executor gameExecutor, Supplier<ReloadInstance> reload) {
        CompletableFuture<ReloadInstance> started = deferredSettlement.thenApplyAsync(ignored -> reload.get(), gameExecutor);
        CompletableFuture<?> completion = started.thenCompose(ReloadInstance::done);
        return new ReloadInstance() {
            @Override
            public CompletableFuture<?> done() {
                return completion;
            }

            @Override
            public float getActualProgress() {
                if (!started.isDone()) return 0.0F;
                if (started.isCompletedExceptionally() || started.isCancelled()) return 1.0F;
                ReloadInstance delegate = started.getNow(null);
                return delegate == null ? 0.0F : delegate.getActualProgress();
            }
        };
    }

    private static void startIfPending(boolean force) {
        if (SHUTTING_DOWN.get() || (!force && (!IMMEDIATE_DONE.get() || !StartupMetrics.hasRecorded("title-screen-operable")))) {
            return;
        }
        PendingReloadSpec spec;
        CompletableFuture<Void> completion;
        TaskTracker tracker;
        synchronized (STATE_LOCK) {
            spec = pendingSpec;
            if (SHUTTING_DOWN.get() || !IMMEDIATE_DONE.get() || spec == null || DEFERRED_STARTED.get()) {
                return;
            }
            completion = new CompletableFuture<>();
            tracker = new TaskTracker();
            activeTasks = tracker;
            activeCompletion = completion;
            DEFERRED_STARTED.set(true);
            pendingSpec = null;
        }
        long startNanos = System.nanoTime();
        int count = spec.deferredListeners().size();
        LOGGER.info("Lightspeed starting deferred non-menu resource reload (listeners={})", count);

        Executor bg = tracker.wrap(wrapExecutor(spec.backgroundExecutor()));
        Executor game = tracker.wrap(wrapExecutor(spec.gameExecutor()));
        CompletableFuture<Void> settlement = deferredSettlement;
        completion.whenComplete((ignored, error) -> tracker.whenIdle().thenRun(() -> {
            if (error == null) {
                settlement.complete(null);
            } else {
                settlement.completeExceptionally(error);
            }
        }));
        try {
            ReloadInstance reload = SimpleReloadInstance.create(
                    spec.resources(),
                    spec.deferredListeners(),
                    bg,
                    game,
                    CompletableFuture.completedFuture(Unit.INSTANCE),
                    spec.logDebug());
            CompletableFuture<Void> chained = reload.done().thenCompose(ignored -> {
                List<PreparableReloadListener> followups;
                synchronized (DEFERRED_FOLLOWUP_LISTENERS) {
                    if (DEFERRED_FOLLOWUP_LISTENERS.isEmpty() || SHUTTING_DOWN.get()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    followups = List.copyOf(DEFERRED_FOLLOWUP_LISTENERS);
                    DEFERRED_FOLLOWUP_LISTENERS.clear();
                }
                LOGGER.info("Lightspeed running {} deferred model-dependent followup listener(s)", followups.size());
                ReloadInstance followupReload = SimpleReloadInstance.create(
                        spec.resources(),
                        followups,
                        bg,
                        game,
                        CompletableFuture.completedFuture(Unit.INSTANCE),
                        spec.logDebug());
                return followupReload.done().thenApply(v -> null);
            }).thenApply(ignored -> null);
            chained.whenComplete((ignored, error) -> {
                if (error == null) {
                    double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    LOGGER.info(
                            "Lightspeed deferred non-menu resource reload completed in {}s (listeners={})",
                            String.format(java.util.Locale.ROOT, "%.3f", elapsedSeconds),
                            count);
                } else if (!SHUTTING_DOWN.get()) {
                    LOGGER.error("Lightspeed deferred non-menu resource reload failed", error);
                }
                if (error == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(error);
                }
            });
        } catch (RuntimeException | Error error) {
            completion.completeExceptionally(error);
            throw error;
        }
    }

    public static boolean hasUnfinishedWork() {
        if (pendingSpec != null && !DEFERRED_STARTED.get()) {
            return true;
        }
        CompletableFuture<Void> active = activeCompletion;
        return active != null;
    }

    public static void awaitIfClientReloading(PackType packType) {
        if (packType == PackType.CLIENT_RESOURCES && hasUnfinishedWork()) {
            awaitReadyIfMainThread();
        }
    }

    public static void awaitReadyIfMainThread() {
        if (!hasUnfinishedWork() || Boolean.TRUE.equals(IN_DEFERRED_TASK.get()) || Boolean.TRUE.equals(IN_AWAIT.get())) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.isSameThread()) {
            awaitReady(client);
        }
    }

    public static void awaitOnShutdown(Minecraft minecraft) {
        CompletableFuture<Void> active;
        TaskTracker deferredTracker;
        synchronized (STATE_LOCK) {
            SHUTTING_DOWN.set(true);
            DEFERRED_STARTED.set(true);
            pendingSpec = null;
            DEFERRED_FOLLOWUP_LISTENERS.clear();
            active = activeCompletion;
            deferredTracker = activeTasks;
        }
        if (active == null) {
            CompletableFuture<?> immediate = immediateCompletion;
            if (immediate == null) {
                deferredSettlement.complete(null);
            } else {
                CompletableFuture<Void> settlement = deferredSettlement;
                immediate.whenComplete((ignored, error) -> immediateTasks.whenIdle().thenRun(() -> {
                    if (error == null) {
                        settlement.complete(null);
                    } else {
                        settlement.completeExceptionally(error);
                    }
                }));
            }
        }
        awaitTasks(minecraft, immediateTasks);
        if (active != null) {
            waitUntilDone(minecraft, active);
        }
        awaitTasks(minecraft, deferredTracker);
    }

    public static void awaitReady(Minecraft minecraft) {
        if (Boolean.TRUE.equals(IN_DEFERRED_TASK.get()) || Boolean.TRUE.equals(IN_AWAIT.get())) {
            return;
        }
        IN_AWAIT.set(Boolean.TRUE);
        try {
            CompletableFuture<?> immediate = immediateCompletion;
            if (pendingSpec != null && immediate != null) {
                waitUntilDone(minecraft, immediate);
                if (immediate.isCompletedExceptionally() || immediate.isCancelled()) {
                    awaitTasks(minecraft, immediateTasks);
                    synchronized (STATE_LOCK) {
                        pendingSpec = null;
                        DEFERRED_STARTED.set(true);
                    }
                    DEFERRED_FOLLOWUP_LISTENERS.clear();
                    immediate.join();
                }
                IMMEDIATE_DONE.set(true);
            }
            startIfPending(true);
            CompletableFuture<Void> active = activeCompletion;
            if (active == null) {
                return;
            }
            waitUntilDone(minecraft, active);
            awaitTasks(minecraft, activeTasks);
            active.join();
            activeCompletion = null;
        } finally {
            IN_AWAIT.set(Boolean.FALSE);
        }
    }

    private static void waitUntilDone(Minecraft minecraft, CompletableFuture<?> future) {
        if (!future.isDone() && minecraft != null && minecraft.isSameThread()) {
            minecraft.managedBlock(future::isDone);
        } else if (!future.isDone()) {
            future.handle((ignored, error) -> null).join();
        }
    }

    private static void awaitTasks(Minecraft minecraft, TaskTracker tracker) {
        if (tracker == null) {
            return;
        }
        if (minecraft != null && minecraft.isSameThread()) {
            minecraft.managedBlock(tracker::isIdle);
        } else {
            tracker.awaitIdle();
        }
    }

    private static final class TaskTracker {
        private final AtomicInteger tasks = new AtomicInteger();
        private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

        Executor wrap(Executor delegate) {
            return task -> {
                taskStarted();
                AtomicBoolean finished = new AtomicBoolean();
                try {
                    delegate.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            if (finished.compareAndSet(false, true)) {
                                taskFinished();
                            }
                        }
                    });
                } catch (RuntimeException | Error error) {
                    if (finished.compareAndSet(false, true)) {
                        taskFinished();
                    }
                    throw error;
                }
            };
        }

        boolean isIdle() {
            return tasks.get() == 0;
        }

        synchronized CompletableFuture<Void> whenIdle() {
            return idle;
        }

        private synchronized void taskStarted() {
            if (tasks.getAndIncrement() == 0) {
                idle = new CompletableFuture<>();
            }
        }

        synchronized void awaitIdle() {
            boolean interrupted = false;
            while (!isIdle()) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void taskFinished() {
            if (tasks.decrementAndGet() == 0) {
                notifyAll();
                idle.complete(null);
            }
        }
    }

    private static Executor wrapExecutor(Executor delegate) {
        return runnable -> delegate.execute(() -> {
            Boolean previous = IN_DEFERRED_TASK.get();
            IN_DEFERRED_TASK.set(Boolean.TRUE);
            try {
                runnable.run();
            } finally {
                IN_DEFERRED_TASK.set(previous);
            }
        });
    }

    private record TrackedImmediateListener(PreparableReloadListener delegate) implements PreparableReloadListener {
        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public CompletableFuture<Void> reload(
                PreparationBarrier barrier,
                ResourceManager manager,
                ProfilerFiller prepProfiler,
                ProfilerFiller applyProfiler,
                Executor backgroundExecutor,
                Executor gameExecutor) {
            PreparableReloadListener previous = enterImmediateListener(delegate);
            try {
                Executor trackedBg = wrapImmediateExecutor(delegate, backgroundExecutor);
                Executor trackedGame = wrapImmediateExecutor(delegate, gameExecutor);
                if (ResourceReloadFailureGuard.shouldIsolate(delegate)) {
                    return ResourceReloadFailureGuard.guard(
                            delegate, barrier,
                            tracked -> delegate.reload(tracked, manager, prepProfiler, applyProfiler, trackedBg, trackedGame));
                }
                return delegate.reload(barrier, manager, prepProfiler, applyProfiler, trackedBg, trackedGame);
            } finally {
                exitImmediateListener(previous);
            }
        }
    }

    private static boolean isWorldOnlyListener(PreparableReloadListener listener) {
        Class<?> type = listener.getClass();
        String name = type.getName();
        if (WORLD_ONLY_LISTENER_CLASSES.contains(name)
                || name.startsWith("software.bernie.geckolib.cache.GeckoLibCache$$")) {
            return true;
        }
        if (GUI_SPRITE_MANAGER.equals(name)) {
            return false;
        }
        for (Class<?> cursor = type.getSuperclass(); cursor != null; cursor = cursor.getSuperclass()) {
            String superName = cursor.getName();
            if (TEXTURE_ATLAS_HOLDER.equals(superName)
                    || BEWLR.equals(superName)
                    || WORLD_ONLY_LISTENER_CLASSES.contains(superName)) {
                return true;
            }
        }
        return false;
    }
}
