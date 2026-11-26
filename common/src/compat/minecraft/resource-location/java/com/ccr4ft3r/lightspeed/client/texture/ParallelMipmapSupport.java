package com.ccr4ft3r.lightspeed.client.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parallelizes per-sprite mipmap generation across a bounded auto-expiring daemon pool
 * using lock-free dynamic work stealing so large animated textures do not stall tail workers.
 */
public final class ParallelMipmapSupport {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("lightspeed.parallelMipmaps", "true"));
    private static final int MIN_SPRITES_FOR_PARALLEL = 32;
    private static final int WORKER_COUNT = Math.max(2,
            Math.min(Runtime.getRuntime().availableProcessors() - 1, 8));
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

    private ParallelMipmapSupport() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static int effectiveMipLevel(List<SpriteContents> contents, int requestedMipLevel) {
        if (requestedMipLevel <= 0 || contents == null || contents.isEmpty()) {
            return requestedMipLevel;
        }
        int minDim = Integer.MAX_VALUE;
        int minPow2 = 1 << requestedMipLevel;
        for (SpriteContents sprite : contents) {
            int width = sprite.width();
            int height = sprite.height();
            minDim = Math.min(minDim, Math.min(width, height));
            int lowest = Math.min(Integer.lowestOneBit(width), Integer.lowestOneBit(height));
            if (lowest < minPow2) {
                minPow2 = lowest;
            }
        }
        int clamped = Mth.log2(Math.min(minDim, minPow2));
        return clamped < requestedMipLevel ? clamped : requestedMipLevel;
    }

    public static CompletableFuture<Void> increaseMipLevelsAsync(
            Map<ResourceLocation, TextureAtlasSprite> sprites,
            int mipLevel) {
        if (sprites == null || sprites.isEmpty() || mipLevel <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        IdentityHashMap<SpriteContents, Boolean> seen = new IdentityHashMap<>(sprites.size());
        List<SpriteContents> unique = new ArrayList<>(sprites.size());
        for (TextureAtlasSprite sprite : sprites.values()) {
            if (sprite == null) {
                continue;
            }
            SpriteContents contents = sprite.contents();
            if (contents != null && seen.put(contents, Boolean.TRUE) == null) {
                unique.add(contents);
            }
        }
        int total = unique.size();
        if (total == 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (total < MIN_SPRITES_FOR_PARALLEL) {
            return CompletableFuture.runAsync(() -> {
                for (SpriteContents contents : unique) {
                    contents.increaseMipLevel(mipLevel);
                }
            }, PoolHolder.EXECUTOR);
        }

        int tasks = Math.min(WORKER_COUNT, total);
        AtomicInteger cursor = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[tasks];
        for (int worker = 0; worker < tasks; worker++) {
            futures[worker] = CompletableFuture.runAsync(() -> {
                while (failure.get() == null) {
                    int index = cursor.getAndIncrement();
                    if (index >= total) {
                        return;
                    }
                    try {
                        unique.get(index).increaseMipLevel(mipLevel);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        return;
                    }
                }
            }, PoolHolder.EXECUTOR);
        }
        return CompletableFuture.allOf(futures).thenRun(() -> {
            Throwable thrown = failure.get();
            if (thrown instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (thrown instanceof Error error) {
                throw error;
            }
            if (thrown != null) {
                throw new RuntimeException(thrown);
            }
        });
    }

    private static final class PoolHolder {
        private static final ThreadPoolExecutor EXECUTOR = createExecutor();

        private static ThreadPoolExecutor createExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    WORKER_COUNT, WORKER_COUNT, 15L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "Lightspeed-Mipmap-" + THREAD_ID.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    });
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }
}
