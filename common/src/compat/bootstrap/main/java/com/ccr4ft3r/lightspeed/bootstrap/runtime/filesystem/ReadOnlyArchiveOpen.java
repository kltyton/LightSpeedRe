package com.ccr4ft3r.lightspeed.bootstrap.runtime.filesystem;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Opens Union's read-only backing archives without probing their unused write access. */
public final class ReadOnlyArchiveOpen {
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final ThreadLocal<Path> OPENING = new ThreadLocal<>();
    private static final LongAdder SCOPES = new LongAdder();
    private static final LongAdder READ_ONLY_CHECKS = new LongAdder();
    private static final ConcurrentHashMap<String, CompletableFuture<FileSystem>> PREOPENED = new ConcurrentHashMap<>(512);
    private static final Set<String> CONSUMED = ConcurrentHashMap.newKeySet(512);
    private static final AtomicBoolean PREOPEN_STARTED = new AtomicBoolean(false);
    private static final CountDownLatch WORKERS_RELEASED = new CountDownLatch(1);
    private static final Object PREOPEN_LOCK = new Object();
    private static boolean preopenFinished;
    private static volatile boolean installed;

    private ReadOnlyArchiveOpen() { }

    public static void installed() {
        installed = true;
    }

    public static void preopenAsync(List<Path> jars) {
        if (!WINDOWS || jars == null || jars.isEmpty() || !PREOPEN_STARTED.compareAndSet(false, true)) {
            return;
        }
        List<Path> snapshot = List.copyOf(jars);
        int threads = Math.min(snapshot.size(), Math.min(10, Math.max(2, Runtime.getRuntime().availableProcessors())));
        AtomicInteger cursor = new AtomicInteger(0);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    while (true) {
                        int index = cursor.getAndIncrement();
                        if (index >= snapshot.size()) {
                            return;
                        }
                        Path jar = snapshot.get(index);
                        if (jar == null || jar.getFileSystem() != FileSystems.getDefault()) {
                            continue;
                        }
                        Path normalized = jar.toAbsolutePath().normalize();
                        String key = normalizeKey(normalized);
                        CompletableFuture<FileSystem> promise = new CompletableFuture<>();
                        synchronized (PREOPEN_LOCK) {
                            if (preopenFinished || CONSUMED.contains(key)
                                    || PREOPENED.putIfAbsent(key, promise) != null) {
                                continue;
                            }
                        }
                        try {
                            FileSystem fs = openDirect(normalized);
                            promise.complete(fs);
                        } catch (Throwable failure) {
                            synchronized (PREOPEN_LOCK) {
                                PREOPENED.remove(key, promise);
                            }
                            promise.completeExceptionally(failure);
                        }
                    }
                } finally {
                    // Avoid thread detach while the early display loads LWJGL's native library.
                    awaitWorkerRelease();
                }
            }, "Lightspeed-ArchivePreopen-" + (i + 1));
            worker.setDaemon(true);
            worker.start();
        }
    }

    public static void closeUnconsumed() {
        List<CompletableFuture<FileSystem>> unconsumed;
        synchronized (PREOPEN_LOCK) {
            preopenFinished = true;
            unconsumed = List.copyOf(PREOPENED.values());
            PREOPENED.clear();
        }
        for (CompletableFuture<FileSystem> future : unconsumed) {
            future.whenComplete((fs, failure) -> {
                if (fs == null) return;
                try {
                    fs.close();
                } catch (IOException exception) {
                    System.err.println("[Lightspeed Agent] Could not close an unconsumed preopened archive: "
                            + exception);
                }
            });
        }
        releaseWorkers();
    }

    public static void releaseWorkers() {
        WORKERS_RELEASED.countDown();
    }

    private static void awaitWorkerRelease() {
        boolean interrupted = false;
        while (true) {
            try {
                WORKERS_RELEASED.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static FileSystem open(Path path) throws IOException {
        if (!WINDOWS || path.getFileSystem() != FileSystems.getDefault()) {
            return FileSystems.newFileSystem(path);
        }
        String key = normalizeKey(path.toAbsolutePath().normalize());
        CompletableFuture<FileSystem> preopened = null;
        synchronized (PREOPEN_LOCK) {
            if (!preopenFinished) {
                CONSUMED.add(key);
                preopened = PREOPENED.remove(key);
            }
        }
        if (preopened != null) {
            try {
                FileSystem fs = preopened.join();
                if (fs != null && fs.isOpen()) {
                    return fs;
                }
            } catch (Throwable ignored) {
            }
        }
        return openDirect(path);
    }

    private static FileSystem openDirect(Path path) throws IOException {
        Path previous = OPENING.get();
        OPENING.set(path);
        SCOPES.increment();
        try {
            return FileSystems.newFileSystem(path);
        } finally {
            if (previous == null) OPENING.remove();
            else OPENING.set(previous);
        }
    }

    public static boolean writable(Path path) {
        Path opening = OPENING.get();
        if (opening != null && (opening == path || opening.equals(path))) {
            READ_ONLY_CHECKS.increment();
            return false;
        }
        return Files.isWritable(path);
    }

    private static String normalizeKey(Path normalized) {
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    public static long scopes() {
        return SCOPES.sum();
    }

    public static long readOnlyChecks() {
        return READ_ONLY_CHECKS.sum();
    }
}
