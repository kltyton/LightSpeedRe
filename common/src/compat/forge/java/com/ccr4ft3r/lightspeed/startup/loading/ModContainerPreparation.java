package com.ccr4ft3r.lightspeed.startup.loading;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parallelizes {@code ModLoader.buildMods(IModFile)} across FML's worker executor while preserving exact
 * {@link LoadingModList#getModFiles()} ordering.
 */
public final class ModContainerPreparation {
    private ModContainerPreparation() {
    }

    @SuppressWarnings("unchecked")
    public static List<ModContainer> buildAllMods(
            ModLoader loader,
            LoadingModList loadingModList,
            Executor parallelExecutor
    ) {
        List<ModFileInfo> modFileInfos = loadingModList.getModFiles();
        int count = modFileInfos.size();
        if (count == 0) {
            return List.of();
        }
        ModLoaderBridge bridge = (ModLoaderBridge) loader;
        if (count == 1 || parallelExecutor == null) {
            List<ModContainer> single = new ArrayList<>();
            for (ModFileInfo info : modFileInfos) {
                single.addAll(bridge.lightspeed$buildMods(info.getFile()));
            }
            return List.copyOf(single);
        }

        IModFile[] files = new IModFile[count];
        for (int i = 0; i < count; i++) {
            files[i] = modFileInfos.get(i).getFile();
        }

        List<ModContainer>[] buckets = (List<ModContainer>[]) new List<?>[count];
        AtomicInteger nextIndex = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int workerCount = Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), count - 1);
        CountDownLatch latch = new CountDownLatch(workerCount);
        Runnable workerTask = () -> {
            try {
                drainWork(bridge, files, buckets, nextIndex, failure);
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < workerCount; i++) {
            parallelExecutor.execute(workerTask);
        }
        drainWork(bridge, files, buckets, nextIndex, failure);

        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("Parallel mod container construction failed", error);
        }

        List<ModContainer> ordered = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            List<ModContainer> chunk = buckets[i];
            if (chunk != null && !chunk.isEmpty()) {
                ordered.addAll(chunk);
            }
        }
        return List.copyOf(ordered);
    }

    private static void drainWork(
            ModLoaderBridge bridge,
            IModFile[] files,
            List<ModContainer>[] buckets,
            AtomicInteger nextIndex,
            AtomicReference<Throwable> failure
    ) {
        while (failure.get() == null) {
            int index = nextIndex.getAndIncrement();
            if (index >= files.length) {
                return;
            }
            try {
                buckets[index] = bridge.lightspeed$buildMods(files[index]);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                return;
            }
        }
    }
}
