package com.ccr4ft3r.lightspeed.startup.compat;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-builds {@link PackResourcesCacheSupport#tryGetModPackIndex(IModFile)} across worker
 * threads during registry construction so the main thread does not scan 400+ mod packs serially
 * before resource reload.
 */
public final class ModPackPrewarmSupport {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile CompletableFuture<Void> prewarmFuture;

    private ModPackPrewarmSupport() {
    }

    public static void startAsyncPrewarm() {
        if (STARTED.get()) {
            return;
        }
        ModList modList = ModList.get();
        if (modList == null) {
            return;
        }
        List<? extends IModFileInfo> infos = modList.getModFiles();
        if (infos == null || infos.isEmpty()) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Set<IModFile> seen = Collections.newSetFromMap(new IdentityHashMap<>(infos.size()));
        List<IModFile> uniqueFiles = new ArrayList<>(infos.size());
        for (int i = 0, len = infos.size(); i < len; i++) {
            IModFile file = infos.get(i).getFile();
            if (file != null && seen.add(file)) {
                uniqueFiles.add(file);
            }
        }
        if (uniqueFiles.isEmpty()) {
            return;
        }
        IModFile[] modFiles = uniqueFiles.toArray(IModFile[]::new);
        prewarmFuture = CompletableFuture.runAsync(() -> runParallelPrewarm(modFiles), ForkJoinPool.commonPool());
    }

    public static void awaitPrewarm() {
        CompletableFuture<Void> future = prewarmFuture;
        if (future != null) {
            future.join();
        }
    }

    private static void runParallelPrewarm(IModFile[] modFiles) {
        int workers = Math.max(1, Math.min(12, ForkJoinPool.commonPool().getParallelism()));
        AtomicInteger cursor = new AtomicInteger();
        if (workers <= 1 || modFiles.length <= 4) {
            drain(modFiles, cursor);
            return;
        }
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers - 1);
        for (int w = 0; w < workers - 1; w++) {
            tasks.add(ForkJoinPool.commonPool().submit(() -> drain(modFiles, cursor)));
        }
        drain(modFiles, cursor);
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).join();
        }
    }

    private static void drain(IModFile[] modFiles, AtomicInteger cursor) {
        for (;;) {
            int idx = cursor.getAndIncrement();
            if (idx >= modFiles.length) {
                return;
            }
            PackResourcesCacheSupport.tryGetModPackIndex(modFiles[idx]);
        }
    }
}
